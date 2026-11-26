package com.jellyarr.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.jellyarr.app.model.ManagedService;
import com.jellyarr.app.services.MediaServiceManager;
import com.jellyarr.app.services.RadarrApi;
import com.jellyarr.app.services.SonarrApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Media tab "File management": the file handling options of Radarr and Sonarr in one place.
 *
 * <ul>
 *   <li>how imported files are named / renamed / hard-linked / cleaned up (Radarr and Sonarr media management
 *       and naming settings, edited through their own APIs),</li>
 *   <li>Organize: rename every existing file to the current naming format,</li>
 *   <li>Import files from a folder with Move or Copy (Radarr / Sonarr "Manual Import").</li>
 * </ul>
 *
 * Everything is delegated to the two services, so the result is exactly what their own web UIs would do.
 * Network calls run off the UI thread; a service that is not installed is hidden and one that is not running
 * shows a hint instead of failing.
 */
public final class MediaToolsView {

    private final Activity act;
    private final LinearLayout root;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final List<Panel> panels = new ArrayList<>();
    private long lastLoad = 0;

    public MediaToolsView(Activity activity) {
        this.act = activity;
        root = UiKit.column(act);
        root.setPadding(0, dp(12), 0, 0);
        panels.add(new Panel(new RadarrArr()));
        panels.add(new Panel(new SonarrArr()));
        for (Panel p : panels) root.addView(p.card);
    }

    public View getView() {
        return root;
    }

    /** Reloads the settings of every installed service (at most every 20 s unless forced). */
    public void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastLoad < 20000) return;
        lastLoad = now;
        for (Panel p : panels) p.reload();
    }

    // ------------------------------------------------------------------ service adapters

    private static final class Field {
        final boolean naming;
        final String key;
        final String label;
        final String hint;
        final boolean bool;

        Field(boolean naming, String key, String label, String hint, boolean bool) {
            this.naming = naming;
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.bool = bool;
        }
    }

    /** What the panel needs from Radarr / Sonarr. All methods block: call them off the UI thread. */
    private interface Arr {
        String id();
        String title();
        String defaultImportFolder();
        List<Field> fields();
        JSONObject mediaManagement() throws Exception;
        void saveMediaManagement(JSONObject o) throws Exception;
        JSONObject naming() throws Exception;
        void saveNaming(JSONObject o) throws Exception;
        JSONArray candidates(String folder) throws Exception;
        void doImport(JSONArray files, String mode) throws Exception;
        int organize() throws Exception;
        void rescanAll() throws Exception;
        /** Turns one manual-import candidate into the command's file entry, or null when it cannot be imported. */
        JSONObject toFile(JSONObject candidate);
    }

    private static boolean hasPermanentRejection(JSONObject c) {
        JSONArray rej = c.optJSONArray("rejections");
        for (int i = 0; rej != null && i < rej.length(); i++) {
            JSONObject r = rej.optJSONObject(i);
            if (r != null && "permanent".equalsIgnoreCase(r.optString("type", "permanent"))) return true;
        }
        return false;
    }

    private static void copyKey(JSONObject from, JSONObject to, String key) throws Exception {
        if (from.has(key) && !from.isNull(key)) to.put(key, from.get(key));
    }

    private final class RadarrArr implements Arr {
        public String id() { return "radarr"; }
        public String title() { return "Radarr · Movies"; }
        public String defaultImportFolder() {
            return MediaStorageManager.getInstance(act).getDownloadsDir().getAbsolutePath();
        }
        public List<Field> fields() {
            List<Field> f = new ArrayList<>();
            f.add(new Field(true, "renameMovies", "Rename movies on import", "Use the naming format below", true));
            f.add(new Field(true, "replaceIllegalCharacters", "Replace illegal characters", "Needed for FAT / exFAT storage", true));
            f.add(new Field(false, "copyUsingHardlinks", "Hardlink instead of copy", "Not supported on shared phone storage", true));
            f.add(new Field(false, "deleteEmptyFolders", "Delete empty folders", "After a movie is moved out", true));
            f.add(new Field(false, "importExtraFiles", "Import extra files", "Subtitles and other files next to the movie", true));
            f.add(new Field(true, "standardMovieFormat", "File name format", "{Movie Title} ({Release Year})", false));
            f.add(new Field(true, "movieFolderFormat", "Folder name format", "{Movie Title} ({Release Year})", false));
            return f;
        }
        public JSONObject mediaManagement() throws Exception { return RadarrApi.get(act).mediaManagementConfig(); }
        public void saveMediaManagement(JSONObject o) throws Exception { RadarrApi.get(act).saveMediaManagementConfig(o); }
        public JSONObject naming() throws Exception { return RadarrApi.get(act).namingConfig(); }
        public void saveNaming(JSONObject o) throws Exception { RadarrApi.get(act).saveNamingConfig(o); }
        public JSONArray candidates(String folder) throws Exception { return RadarrApi.get(act).manualImportCandidates(folder); }
        public void doImport(JSONArray files, String mode) throws Exception { RadarrApi.get(act).manualImport(files, mode); }
        public int organize() throws Exception { return RadarrApi.get(act).renameAllMovies(); }
        public void rescanAll() throws Exception { RadarrApi.get(act).simpleCommand("RescanMovie"); }
        public JSONObject toFile(JSONObject c) {
            try {
                JSONObject movie = c.optJSONObject("movie");
                String path = c.optString("path", "");
                if (movie == null || path.isEmpty() || hasPermanentRejection(c)) return null;
                JSONObject f = new JSONObject();
                f.put("path", path);
                f.put("movieId", movie.optInt("id"));
                copyKey(c, f, "quality");
                copyKey(c, f, "languages");
                copyKey(c, f, "releaseGroup");
                copyKey(c, f, "indexerFlags");
                copyKey(c, f, "downloadId");
                return f;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private final class SonarrArr implements Arr {
        public String id() { return "sonarr"; }
        public String title() { return "Sonarr · TV Shows"; }
        public String defaultImportFolder() {
            return MediaStorageManager.getInstance(act).getDownloadsDir().getAbsolutePath();
        }
        public List<Field> fields() {
            List<Field> f = new ArrayList<>();
            f.add(new Field(true, "renameEpisodes", "Rename episodes on import", "Use the naming format below", true));
            f.add(new Field(true, "replaceIllegalCharacters", "Replace illegal characters", "Needed for FAT / exFAT storage", true));
            f.add(new Field(false, "copyUsingHardlinks", "Hardlink instead of copy", "Not supported on shared phone storage", true));
            f.add(new Field(false, "deleteEmptyFolders", "Delete empty folders", "After an episode is moved out", true));
            f.add(new Field(false, "importExtraFiles", "Import extra files", "Subtitles and other files next to the episode", true));
            f.add(new Field(true, "standardEpisodeFormat", "Episode file format", "{Series Title} - S{season:00}E{episode:00}", false));
            f.add(new Field(true, "seasonFolderFormat", "Season folder format", "Season {season}", false));
            f.add(new Field(true, "seriesFolderFormat", "Series folder format", "{Series Title}", false));
            return f;
        }
        public JSONObject mediaManagement() throws Exception { return SonarrApi.get(act).mediaManagementConfig(); }
        public void saveMediaManagement(JSONObject o) throws Exception { SonarrApi.get(act).saveMediaManagementConfig(o); }
        public JSONObject naming() throws Exception { return SonarrApi.get(act).namingConfig(); }
        public void saveNaming(JSONObject o) throws Exception { SonarrApi.get(act).saveNamingConfig(o); }
        public JSONArray candidates(String folder) throws Exception { return SonarrApi.get(act).manualImportCandidates(folder); }
        public void doImport(JSONArray files, String mode) throws Exception { SonarrApi.get(act).manualImport(files, mode); }
        public int organize() throws Exception { return SonarrApi.get(act).renameAllSeries(); }
        public void rescanAll() throws Exception { SonarrApi.get(act).simpleCommand("RescanSeries"); }
        public JSONObject toFile(JSONObject c) {
            try {
                JSONObject series = c.optJSONObject("series");
                JSONArray eps = c.optJSONArray("episodes");
                String path = c.optString("path", "");
                if (series == null || eps == null || eps.length() == 0 || path.isEmpty() || hasPermanentRejection(c)) return null;
                JSONArray epIds = new JSONArray();
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e != null) epIds.put(e.optInt("id"));
                }
                JSONObject f = new JSONObject();
                f.put("path", path);
                f.put("seriesId", series.optInt("id"));
                f.put("episodeIds", epIds);
                copyKey(c, f, "quality");
                copyKey(c, f, "languages");
                copyKey(c, f, "releaseGroup");
                copyKey(c, f, "releaseType");
                copyKey(c, f, "downloadId");
                return f;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ one service card

    private final class Panel {
        final Arr arr;
        final LinearLayout card;
        final LinearLayout body;
        final TextView stateBadge;
        final Map<Field, View> inputs = new LinkedHashMap<>();
        JSONObject mmCfg;
        JSONObject namingCfg;
        Button saveBtn;
        int gen = 0;

        Panel(Arr arr) {
            this.arr = arr;
            card = UiKit.card(act);
            LinearLayout head = UiKit.row(act);
            head.addView(UiKit.overline(act, I18n.tr(arr.title())), new LinearLayout.LayoutParams(0, -2, 1.0f));
            stateBadge = UiKit.caption(act, "");
            head.addView(stateBadge);
            card.addView(head);
            body = UiKit.column(act);
            card.addView(body, new LinearLayout.LayoutParams(-1, -2));
            card.setVisibility(View.GONE);
            reload();
        }

        private boolean installed() {
            try {
                ManagedService s = MediaServiceManager.getInstance(act).getService(arr.id());
                return s != null && s.isInstalled();
            } catch (Throwable t) {
                return false;
            }
        }

        private boolean running() {
            try {
                ManagedService s = MediaServiceManager.getInstance(act).getService(arr.id());
                return s != null && s.isRunning();
            } catch (Throwable t) {
                return false;
            }
        }

        void reload() {
            if (!installed()) {
                card.setVisibility(View.GONE);
                return;
            }
            card.setVisibility(View.VISIBLE);
            final int my = ++gen;
            body.removeAllViews();
            if (!running()) {
                stateBadge.setText(I18n.tr("Stopped"));
                stateBadge.setTextColor(AppTheme.textMuted());
                body.addView(UiKit.caption(act, I18n.tr("Start the service from Home to manage its files.")));
                return;
            }
            stateBadge.setText(I18n.tr("Running"));
            stateBadge.setTextColor(AppTheme.success());
            body.addView(UiKit.loading(act, I18n.tr("Loading settings…")));
            try {
                bg.execute(() -> {
                    JSONObject mm = null;
                    JSONObject nm = null;
                    String err = null;
                    try {
                        mm = arr.mediaManagement();
                        nm = arr.naming();
                    } catch (Throwable t) {
                        err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    }
                    final JSONObject fmm = mm;
                    final JSONObject fnm = nm;
                    final String ferr = err;
                    act.runOnUiThread(() -> {
                        if (act.isFinishing() || act.isDestroyed() || my != gen) return;
                        render(fmm, fnm, ferr);
                    });
                });
            } catch (RejectedExecutionException e) {
                // shutting down
            }
        }

        private void render(JSONObject mm, JSONObject nm, String err) {
            body.removeAllViews();
            inputs.clear();
            if (err != null || mm == null || nm == null) {
                body.addView(UiKit.error(act, I18n.tr("Could not load settings"), err == null ? "" : err,
                        I18n.tr("Retry"), v -> reload()));
                return;
            }
            mmCfg = mm;
            namingCfg = nm;

            TextView sec = UiKit.caption(act, I18n.tr("How files are handled"));
            sec.setPadding(0, dp(8), 0, dp(2));
            body.addView(sec);
            for (Field f : arr.fields()) {
                JSONObject src = f.naming ? nm : mm;
                if (!src.has(f.key)) continue;
                body.addView(f.bool ? boolRow(f, src.optBoolean(f.key)) : textRow(f, src.optString(f.key, "")));
            }

            saveBtn = UiKit.primary(act, I18n.tr("Save"), v -> save());
            Button organize = UiKit.outline(act, I18n.tr("Organize files"), v -> confirmOrganize());
            body.addView(UiKit.buttonRow(act, saveBtn, organize));
            Button importBtn = UiKit.tonal(act, I18n.tr("Import files…"), v -> showImportDialog());
            Button rescan = UiKit.outline(act, I18n.tr("Rescan folders"), v -> runAction(
                    I18n.tr("Rescan started"), () -> arr.rescanAll()));
            body.addView(UiKit.buttonRow(act, importBtn, rescan));
        }

        private View boolRow(Field f, boolean value) {
            LinearLayout row = UiKit.row(act);
            row.setPadding(0, dp(8), 0, dp(8));
            LinearLayout col = UiKit.column(act);
            col.addView(UiKit.text(act, I18n.tr(f.label), 14f, AppTheme.text(), UiKit.MEDIUM));
            col.addView(UiKit.caption(act, I18n.tr(f.hint)));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));
            Switch sw = UiStyle.newSwitch(act);
            sw.setChecked(value);
            row.addView(sw);
            inputs.put(f, sw);
            return row;
        }

        private View textRow(Field f, String value) {
            LinearLayout col = UiKit.column(act);
            col.setPadding(0, dp(8), 0, dp(4));
            col.addView(UiKit.text(act, I18n.tr(f.label), 14f, AppTheme.text(), UiKit.MEDIUM));
            EditText e = UiKit.input(act, f.hint);
            e.setText(value);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            col.addView(e, lp);
            inputs.put(f, e);
            return col;
        }

        private void save() {
            if (mmCfg == null || namingCfg == null) return;
            final JSONObject mm = mmCfg;
            final JSONObject nm = namingCfg;
            final String mmBefore = mm.toString();
            final String nmBefore = nm.toString();
            try {
                for (Map.Entry<Field, View> en : inputs.entrySet()) {
                    Field f = en.getKey();
                    JSONObject dst = f.naming ? nm : mm;
                    if (f.bool) dst.put(f.key, ((Switch) en.getValue()).isChecked());
                    else dst.put(f.key, ((EditText) en.getValue()).getText().toString().trim());
                }
            } catch (Throwable t) {
                toast(String.valueOf(t.getMessage()));
                return;
            }
            final boolean mmChanged = !mm.toString().equals(mmBefore);
            final boolean nmChanged = !nm.toString().equals(nmBefore);
            if (!mmChanged && !nmChanged) {
                toast(I18n.tr("Nothing changed"));
                return;
            }
            runAction(I18n.tr("Saved"), () -> {
                if (mmChanged) arr.saveMediaManagement(mm);
                if (nmChanged) arr.saveNaming(nm);
            });
        }

        private void confirmOrganize() {
            new AlertDialog.Builder(act)
                    .setTitle(I18n.tr("Organize files"))
                    .setMessage(I18n.tr("Rename every existing file and folder to the current naming format? Files stay where they are; only their names change."))
                    .setPositiveButton(I18n.tr("Organize"), (d, w) -> runAction(null, () -> {
                        final int n = arr.organize();
                        act.runOnUiThread(() -> toast(n == 0
                                ? I18n.tr("No files to rename")
                                : I18n.tr("Renaming started for ") + n));
                    }))
                    .setNegativeButton(I18n.tr("Cancel"), null)
                    .show();
        }

        // ---- manual import (Move / Copy)

        private void showImportDialog() {
            LinearLayout box = UiKit.column(act);
            box.setPadding(dp(20), dp(8), dp(20), 0);
            TextView hint = UiKit.caption(act, I18n.tr("Folder to import from (video files are matched to your library)"));
            box.addView(hint);
            final EditText path = UiKit.input(act, arr.defaultImportFolder());
            path.setText(arr.defaultImportFolder());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.topMargin = dp(6);
            box.addView(path, plp);
            final RadioGroup mode = new RadioGroup(act);
            final RadioButton move = new RadioButton(act);
            move.setText(I18n.tr("Move (the original is removed)"));
            move.setTextColor(AppTheme.text());
            move.setId(View.generateViewId());
            final RadioButton copy = new RadioButton(act);
            copy.setText(I18n.tr("Copy (the original stays)"));
            copy.setTextColor(AppTheme.text());
            copy.setId(View.generateViewId());
            mode.addView(move);
            mode.addView(copy);
            mode.check(move.getId());
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.topMargin = dp(10);
            box.addView(mode, mlp);
            new AlertDialog.Builder(act)
                    .setTitle(I18n.tr("Import files"))
                    .setView(box)
                    .setPositiveButton(I18n.tr("Scan"), (d, w) -> scan(path.getText().toString().trim(),
                            copy.isChecked() ? "copy" : "move"))
                    .setNegativeButton(I18n.tr("Cancel"), null)
                    .show();
        }

        private void scan(final String folder, final String mode) {
            if (folder.isEmpty()) return;
            toast(I18n.tr("Scanning…"));
            try {
                bg.execute(() -> {
                    JSONArray files = new JSONArray();
                    int total = 0;
                    String err = null;
                    try {
                        JSONArray cands = arr.candidates(folder);
                        total = cands.length();
                        for (int i = 0; i < cands.length(); i++) {
                            JSONObject c = cands.optJSONObject(i);
                            JSONObject f = c == null ? null : arr.toFile(c);
                            if (f != null) files.put(f);
                        }
                    } catch (Throwable t) {
                        err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    }
                    final JSONArray ff = files;
                    final int ft = total;
                    final String fe = err;
                    act.runOnUiThread(() -> {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        if (fe != null) {
                            toast(fe);
                            return;
                        }
                        if (ff.length() == 0) {
                            toast(ft == 0 ? I18n.tr("No video files found in that folder")
                                    : I18n.tr("Nothing could be matched to your library"));
                            return;
                        }
                        confirmImport(ff, ft, mode);
                    });
                });
            } catch (RejectedExecutionException e) {
                // shutting down
            }
        }

        private void confirmImport(final JSONArray files, int total, final String mode) {
            boolean move = "move".equals(mode);
            String msg = files.length() + " / " + total + " " + I18n.tr("files matched your library.")
                    + (total > files.length() ? "\n" + I18n.tr("The rest are skipped.") : "");
            new AlertDialog.Builder(act)
                    .setTitle(I18n.tr(move ? "Move files" : "Copy files"))
                    .setMessage(msg)
                    .setPositiveButton(I18n.tr(move ? "Move" : "Copy"), (d, w) -> runAction(
                            I18n.tr("Import started"), () -> arr.doImport(files, mode)))
                    .setNegativeButton(I18n.tr("Cancel"), null)
                    .show();
        }

        private void runAction(final String okMessage, final Call call) {
            try {
                bg.execute(() -> {
                    String err = null;
                    try {
                        call.run();
                    } catch (Throwable t) {
                        err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    }
                    final String fe = err;
                    act.runOnUiThread(() -> {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        if (fe != null) toast(fe);
                        else if (okMessage != null) toast(okMessage);
                    });
                });
            } catch (RejectedExecutionException e) {
                // shutting down
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface Call {
        void run() throws Exception;
    }

    private void toast(String s) {
        if (s == null || act.isFinishing() || act.isDestroyed()) return;
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return UiKit.dp(act, v);
    }
}
