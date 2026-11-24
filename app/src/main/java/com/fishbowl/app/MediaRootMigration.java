package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time rename of the shared media folder {@code FishbowlMedia} to {@code JellyarrMedia}.
 *
 * <p>The folder is renamed (same volume, so no data is copied) and every stored reference to the old path is
 * rewritten: app preferences, the Radarr / Sonarr / Prowlarr / Jellyfin / Transmission databases and config files.
 * Both names have the same length (13 characters), so even binary files such as Transmission resume data stay valid
 * when the name is replaced byte for byte; SQLite databases are updated through SQL so their indexes stay correct.
 *
 * <p>It must run before any service starts. If the rename is not possible the old folder keeps being used.
 */
final class MediaRootMigration {

    private static final String TAG = "MediaRootMigration";
    private static final String OLD = "FishbowlMedia";
    private static final String NEW = "JellyarrMedia";
    private static final String DONE_PREFS = "jellyarr_media_root_migration";
    private static final String K_DONE = "renamed_v1";
    private static final long MAX_TEXT_FILE_BYTES = 20L * 1024 * 1024;

    private MediaRootMigration() {}

    static void run(Context context) {
        try {
            SharedPreferences done = context.getSharedPreferences(DONE_PREFS, Context.MODE_PRIVATE);
            if (done.getBoolean(K_DONE, false)) return;

            boolean renamedAny = false;
            boolean pending = false;
            for (File oldDir : candidates(context)) {
                File newDir = new File(oldDir.getParentFile(), NEW);
                if (newDir.exists() && !isEmptyScaffold(newDir)) {
                    Log.w(TAG, "Both " + oldDir + " and " + newDir + " hold data; leaving the old folder untouched");
                    pending = true;
                    continue;
                }
                if (newDir.exists()) deleteTree(newDir);
                if (oldDir.renameTo(newDir)) {
                    Log.i(TAG, "Renamed " + oldDir + " -> " + newDir);
                    renamedAny = true;
                } else {
                    Log.w(TAG, "Could not rename " + oldDir + "; the old folder stays in use");
                    pending = true;
                }
            }

            if (renamedAny) rewriteReferences(context);
            // Retry on the next launch only when something could not be moved; nothing left to do otherwise.
            if (!pending) done.edit().putBoolean(K_DONE, true).apply();
        } catch (Throwable t) {
            Log.e(TAG, "Media folder rename failed", t);
        }
    }

    // ------------------------------------------------------------------ folders

    private static List<File> candidates(Context context) {
        List<File> out = new ArrayList<>();
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) {
            for (File v : vols) {
                if ("self".equals(v.getName())) continue; // alias of emulated/<user>
                if ("emulated".equals(v.getName())) {
                    File[] users = v.listFiles();
                    if (users != null) {
                        for (File u : users) {
                            File f = new File(u, OLD);
                            if (f.isDirectory()) out.add(f);
                        }
                    }
                    continue;
                }
                File f = new File(v, OLD);
                if (f.isDirectory()) out.add(f);
            }
        }
        // The saved root may point somewhere that is not under /storage (e.g. /sdcard alias).
        try {
            SharedPreferences sp = context.getSharedPreferences("fishbowl_storage", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String && ((String) v).endsWith("/" + OLD)) {
                    File f = new File((String) v);
                    if (f.isDirectory() && !containsCanonical(out, f)) out.add(f);
                }
            }
        } catch (Throwable ignored) {
            // best effort
        }
        return out;
    }

    private static boolean containsCanonical(List<File> list, File f) {
        try {
            String c = f.getCanonicalPath();
            for (File x : list) if (x.getCanonicalPath().equals(c)) return true;
        } catch (IOException ignored) {
            // treat as not contained
        }
        return false;
    }

    /** True when the folder only holds (empty) directories, i.e. the scaffold created by a fresh start. */
    private static boolean isEmptyScaffold(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.isFile()) return false;
            if (k.isDirectory() && !isEmptyScaffold(k)) return false;
        }
        return true;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ------------------------------------------------------------------ references

    private static void rewriteReferences(Context context) {
        rewritePreferences(context);
        File home = TermuxConstants.TERMUX_HOME_DIR;
        String[] dirs = {".config/Radarr", ".config/Sonarr", ".config/Prowlarr", ".config/transmission-daemon",
                ".config/jellyfin", ".local/share/jellyfin"};
        for (String d : dirs) {
            try {
                walk(new File(home, d), 0);
            } catch (Throwable t) {
                Log.w(TAG, "Rewrite failed in " + d + ": " + t.getMessage());
            }
        }
    }

    private static void rewritePreferences(Context context) {
        try {
            File dir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(".xml")) continue;
                SharedPreferences sp = context.getSharedPreferences(n.substring(0, n.length() - 4), Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = null;
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String && ((String) v).contains(OLD)) {
                        if (ed == null) ed = sp.edit();
                        ed.putString(e.getKey(), ((String) v).replace(OLD, NEW));
                    } else if (v instanceof Set) {
                        Set<?> set = (Set<?>) v;
                        boolean hit = false;
                        java.util.HashSet<String> repl = new java.util.HashSet<>();
                        for (Object o : set) {
                            String s = String.valueOf(o);
                            if (s.contains(OLD)) hit = true;
                            repl.add(s.replace(OLD, NEW));
                        }
                        if (hit) {
                            if (ed == null) ed = sp.edit();
                            ed.putStringSet(e.getKey(), repl);
                        }
                    }
                }
                if (ed != null) ed.commit();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Preference rewrite failed: " + t.getMessage());
        }
    }

    private static boolean skipDir(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("logs") || n.equals("log") || n.equals("cache") || n.equals("mediacover")
                || n.equals("metadata") || n.equals("transcodes") || n.equals("trickplay") || n.equals("backups");
    }

    private static void walk(File dir, int depth) {
        if (depth > 8 || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (!skipDir(k.getName())) walk(k, depth + 1);
            } else if (k.isFile()) {
                processFile(k);
            }
        }
    }

    private static void processFile(File f) {
        try {
            String name = f.getName();
            if (name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal")) return;
            if (isSqlite(f)) {
                rewriteDatabase(f);
            } else if (f.length() > 0 && f.length() <= MAX_TEXT_FILE_BYTES) {
                rewriteBytes(f);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Skipped " + f.getName() + ": " + t.getMessage());
        }
    }

    private static boolean isSqlite(File f) {
        if (f.length() < 100) return false;
        byte[] head = new byte[16];
        try (FileInputStream in = new FileInputStream(f)) {
            if (in.read(head) != 16) return false;
        } catch (IOException e) {
            return false;
        }
        return new String(head, 0, 15, StandardCharsets.ISO_8859_1).equals("SQLite format 3");
    }

    /** Equal-length byte replacement; the file is only rewritten when it actually contains the old name. */
    private static void rewriteBytes(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        byte[] oldB = OLD.getBytes(StandardCharsets.US_ASCII);
        byte[] newB = NEW.getBytes(StandardCharsets.US_ASCII);
        boolean changed = false;
        for (int i = 0; i <= data.length - oldB.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldB.length; j++) {
                if (data[i + j] != oldB[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(newB, 0, data, i, newB.length);
                changed = true;
                i += oldB.length - 1;
            }
        }
        if (!changed) return;
        File tmp = new File(f.getParentFile(), f.getName() + ".rewrite.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            // rename over an existing file can fail on some filesystems: overwrite in place instead
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            tmp.delete();
        }
    }

    private static void rewriteDatabase(File f) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            List<String[]> targets = new ArrayList<>();
            try (Cursor tables = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null)) {
                while (tables.moveToNext()) {
                    String table = tables.getString(0);
                    try (Cursor cols = db.rawQuery("PRAGMA table_info(\"" + table.replace("\"", "\"\"") + "\")", null)) {
                        while (cols.moveToNext()) {
                            String col = cols.getString(1);
                            String type = cols.getString(2) == null ? "" : cols.getString(2).toUpperCase(java.util.Locale.ROOT);
                            if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB") || type.isEmpty()) {
                                targets.add(new String[]{table, col});
                            }
                        }
                    }
                }
            }
            int updated = 0;
            for (String[] t : targets) {
                String q = "\"" + t[0].replace("\"", "\"\"") + "\"";
                String c = "\"" + t[1].replace("\"", "\"\"") + "\"";
                try {
                    db.execSQL("UPDATE " + q + " SET " + c + " = REPLACE(" + c + ", '" + OLD + "', '" + NEW + "') WHERE "
                            + c + " LIKE '%" + OLD + "%'");
                    updated++;
                } catch (Throwable e) {
                    // a constraint or a virtual table: leave that column, the services re-sync paths on SYNC STACK
                    Log.w(TAG, f.getName() + " " + t[0] + "." + t[1] + ": " + e.getMessage());
                }
            }
            Log.i(TAG, "Rewrote " + f.getName() + " (" + updated + " columns checked)");
        } catch (Throwable e) {
            Log.w(TAG, "Could not open " + f.getName() + ": " + e.getMessage());
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
