package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.StatFs;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.importer.DownloadDetectionManager;
import com.fishbowl.app.importer.MovieImportActivity;
import com.fishbowl.app.importer.MovieImportJournal;
import com.fishbowl.app.permissions.AppPermissionManager;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Media tab tools: a Media Storage card (free space, usage per library folder, retention policy) and a
 * Movie Importer card (download detection switches, journal statistics, quick actions).
 *
 * Folder sizes and journal statistics are computed off the UI thread; missing folders and missing
 * storage permission are shown as states instead of silently reading as 0 B.
 */
public final class MediaToolsView {

    private static final int MAX_FILES_PER_FOLDER = 60000;
    private static final int MAX_DEPTH = 12;
    private static final long SCAN_TTL_MS = 30000L;

    private static final int ST_OK = 0;
    private static final int ST_MISSING = 1;
    private static final int ST_NO_ACCESS = 2;
    private static final int ST_TRUNCATED = 3;

    private final Activity act;
    private final LinearLayout root;
    private final ExecutorService scanBg = Executors.newSingleThreadExecutor();
    private final ExecutorService statsBg = Executors.newSingleThreadExecutor();

    private LinearLayout storageBody;
    private Button refreshBtn;
    private LinearLayout importerBody;
    private long lastScan = 0;
    private boolean scanning = false;
    private int statsGen = 0;

    public MediaToolsView(Activity activity) {
        this.act = activity;
        root = UiKit.column(act);
        root.setPadding(0, dp(12), 0, 0);
        root.addView(buildStorageCard());
        root.addView(buildImporterCard());
    }

    public View getView() {
        return root;
    }

    /** Re-reads the importer statistics immediately and the storage sizes at most every 30 s (unless forced). */
    public void refresh(boolean force) {
        renderImporter();
        long now = System.currentTimeMillis();
        if (force || now - lastScan > SCAN_TTL_MS) scanStorage();
    }

    private boolean dead() {
        return act.isFinishing() || act.isDestroyed();
    }

    // ------------------------------------------------------------------ storage card

    private View buildStorageCard() {
        LinearLayout c = UiKit.card(act);
        LinearLayout head = UiKit.row(act);
        TextView title = UiKit.overline(act, I18n.tr("Media storage"));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        refreshBtn = compact(UiKit.outline(act, I18n.tr("Refresh"), v -> {
            showStorageLoading();
            scanStorage();
        }));
        head.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(32)));
        c.addView(head);
        storageBody = UiKit.column(act);
        c.addView(storageBody, new LinearLayout.LayoutParams(-1, -2));
        showStorageLoading();
        return c;
    }

    private void showStorageLoading() {
        storageBody.removeAllViews();
        storageBody.addView(UiKit.loading(act, I18n.tr("Measuring folders…")));
    }

    private static final class Entry {
        final String label;
        final File dir;
        long bytes;
        long files;
        int state = ST_OK;

        Entry(String label, File dir) {
            this.label = label;
            this.dir = dir;
        }
    }

    private static final class ScanResult {
        Entry[] entries = new Entry[0];
        long free = 0;
        long total = 0;
        String rootPath = "";
        boolean rootExists = false;
        String error = null;
    }

    private void scanStorage() {
        if (scanning) return;
        final MediaStorageManager sm = MediaStorageManager.getInstance(act);
        if (sm == null) return;
        scanning = true;
        setBusy(refreshBtn, true);
        try {
            scanBg.execute(() -> {
                final ScanResult res = new ScanResult();
                try {
                    res.entries = new Entry[]{
                            new Entry("Movies", sm.getMoviesDir()),
                            new Entry("TV Shows", sm.getTvDir()),
                            new Entry("Music", sm.getMusicDir()),
                            new Entry("Books", sm.getBooksDir()),
                            new Entry("Downloads", sm.getDownloadsDir()),
                            new Entry("Backups", sm.getBackupsDir()),
                    };
                    for (Entry e : res.entries) measure(e);
                    File mediaRoot = sm.getMediaRoot();
                    res.rootPath = mediaRoot.getAbsolutePath();
                    res.rootExists = mediaRoot.isDirectory();
                    File probe = mediaRoot;
                    while (probe != null && !probe.exists()) probe = probe.getParentFile();
                    if (probe != null) {
                        StatFs fs = new StatFs(probe.getAbsolutePath());
                        res.free = fs.getAvailableBytes();
                        res.total = fs.getTotalBytes();
                    }
                } catch (Throwable t) {
                    res.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                act.runOnUiThread(() -> {
                    scanning = false;
                    setBusy(refreshBtn, false);
                    if (dead()) return;
                    lastScan = System.currentTimeMillis();
                    renderStorage(res);
                });
            });
        } catch (RejectedExecutionException e) {
            scanning = false;
            setBusy(refreshBtn, false);
        }
    }

    private static void measure(Entry e) {
        File d = e.dir;
        if (d == null || !d.isDirectory()) {
            e.state = ST_MISSING;
            return;
        }
        if (d.listFiles() == null) {
            e.state = ST_NO_ACCESS;
            return;
        }
        long[] acc = new long[2];
        walk(d, acc, 0, new HashSet<String>());
        e.bytes = acc[0];
        e.files = acc[1];
        if (acc[1] > MAX_FILES_PER_FOLDER) e.state = ST_TRUNCATED;
    }

    /** Bounded recursive size; canonical paths stop symlink loops and double counting. */
    private static void walk(File dir, long[] acc, int depth, Set<String> seen) {
        if (acc[1] > MAX_FILES_PER_FOLDER || depth > MAX_DEPTH) return;
        String canon;
        try {
            canon = dir.getCanonicalPath();
        } catch (IOException ex) {
            return;
        }
        if (!seen.add(canon)) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (acc[1] > MAX_FILES_PER_FOLDER) return;
            if (k.isDirectory()) {
                walk(k, acc, depth + 1, seen);
            } else if (k.isFile()) {
                acc[0] += k.length();
                acc[1]++;
            }
        }
    }

    private void renderStorage(ScanResult r) {
        storageBody.removeAllViews();
        final MediaStorageManager sm = MediaStorageManager.getInstance(act);

        boolean perm = true;
        try {
            perm = AppPermissionManager.isStorageGranted(act);
        } catch (Throwable ignored) {
            // assume granted
        }
        if (!perm) {
            storageBody.addView(UiKit.error(act, I18n.tr("Storage access is off"),
                    I18n.tr("Folder sizes cannot be measured until Jellyarr may read your storage."),
                    I18n.tr("Allow Access"), v -> {
                        try {
                            AppPermissionManager.requestStorage(act);
                        } catch (Throwable t) {
                            toast(String.valueOf(t.getMessage()));
                        }
                    }));
        }
        if (r.error != null) {
            storageBody.addView(UiKit.error(act, I18n.tr("Could not measure storage"), r.error, I18n.tr("Retry"), v -> {
                showStorageLoading();
                scanStorage();
            }));
        }

        long used = 0;
        long max = 1;
        for (Entry e : r.entries) {
            used += e.bytes;
            max = Math.max(max, e.bytes);
        }

        if (r.total > 0) {
            storageBody.addView(UiKit.subtitle(act, tf("{0} free of {1}", fmt(r.free), fmt(r.total))));
            int usedPct = (int) Math.max(0, Math.min(100, 100 * (r.total - r.free) / r.total));
            int color = usedPct >= 95 ? AppTheme.danger() : usedPct >= 85 ? AppTheme.warning() : AppTheme.accent();
            storageBody.addView(UiKit.progress(act, usedPct, color));
            TextView u = UiKit.caption(act, tf("{0} in media folders", fmt(used)));
            u.setPadding(0, dp(6), 0, 0);
            storageBody.addView(u);
        }
        if (!r.rootPath.isEmpty()) {
            TextView path = UiKit.mono(act, r.rootPath);
            path.setTextSize(11.5f);
            path.setTextColor(AppTheme.textMuted());
            path.setPadding(0, dp(8), 0, dp(4));
            storageBody.addView(path);
        }
        if (!r.rootExists && r.error == null) {
            TextView miss = UiKit.caption(act, I18n.tr("The media folder does not exist yet. It is created when the services start."));
            miss.setTextColor(AppTheme.warning());
            miss.setPadding(0, 0, 0, dp(4));
            storageBody.addView(miss);
        }

        LinearLayout list = UiKit.column(act);
        list.setPadding(0, dp(6), 0, 0);
        for (Entry e : r.entries) {
            LinearLayout row = UiKit.column(act);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setBackground(UiKit.rowRipple(act));
            row.setClickable(true);
            row.setFocusable(true);
            LinearLayout line = UiKit.row(act);
            line.addView(UiKit.singleLine(UiKit.text(act, I18n.tr(e.label), 14f, AppTheme.text(), UiKit.MEDIUM)),
                    new LinearLayout.LayoutParams(0, -2, 1.0f));
            TextView meta;
            if (e.state == ST_MISSING) {
                meta = UiKit.caption(act, I18n.tr("Folder missing"));
            } else if (e.state == ST_NO_ACCESS) {
                meta = UiKit.caption(act, I18n.tr("No access"));
                meta.setTextColor(AppTheme.warning());
            } else {
                String files = e.state == ST_TRUNCATED ? MAX_FILES_PER_FOLDER + "+" : String.valueOf(e.files);
                meta = UiKit.caption(act, fmt(e.bytes) + " · " + (e.files == 1 ? I18n.tr("1 file") : tf("{0} files", files)));
            }
            line.addView(meta);
            row.addView(line);
            int pct = e.state == ST_OK || e.state == ST_TRUNCATED ? (int) (100 * e.bytes / max) : 0;
            row.addView(UiKit.progress(act, pct, e.state == ST_OK || e.state == ST_TRUNCATED ? AppTheme.accent() : AppTheme.textMuted()));
            final String p = e.dir == null ? "" : e.dir.getAbsolutePath();
            row.setOnClickListener(v -> copy(p));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        storageBody.addView(list);

        if (sm != null) {
            View ret = UiKit.kv(act, I18n.tr("Staging retention"), I18n.tr(sm.getRetentionPolicyDisplayName()));
            ret.setPadding(0, dp(10), 0, 0);
            storageBody.addView(ret);
        }
        Button retention = UiKit.outline(act, I18n.tr("Retention"), v -> showRetentionDialog());
        Button manage = UiKit.outline(act, I18n.tr("Manage Storage"),
                v -> act.startActivity(new Intent(act, JellyfinStorageActivity.class)));
        storageBody.addView(UiKit.buttonRow(act, retention, manage));
    }

    private void showRetentionDialog() {
        final MediaStorageManager sm = MediaStorageManager.getInstance(act);
        if (sm == null) return;
        final String[] values = {
                MediaStorageManager.RETENTION_IMMEDIATE_ON_IMPORT,
                MediaStorageManager.RETENTION_UNTIL_TORRENT_REMOVED,
                MediaStorageManager.RETENTION_24_HOURS,
                MediaStorageManager.RETENTION_3_DAYS,
                MediaStorageManager.RETENTION_7_DAYS,
                MediaStorageManager.RETENTION_MANUAL};
        final String[] labels = {
                "Immediately after import (no duplicates)",
                "Until the torrent is removed",
                "Keep for 24 hours",
                "Keep for 3 days",
                "Keep for 7 days",
                "Manual cleanup"};
        String cur = sm.getRetentionPolicy();

        LinearLayout box = UiKit.column(act);
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView t = UiKit.title(act, I18n.tr("Staging retention"));
        t.setPadding(0, 0, 0, dp(4));
        box.addView(t);
        TextView hint = UiKit.body(act, I18n.tr("How long the copy in Downloads stays after a file is imported into the library."));
        hint.setPadding(0, 0, 0, dp(8));
        box.addView(hint);
        ScrollView sv = new ScrollView(act);
        sv.addView(box, new ViewGroup.LayoutParams(-1, -2));
        final AlertDialog d = new AlertDialog.Builder(act).setView(I18n.attach(sv)).create();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(UiKit.shape(act, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));
        }
        for (int i = 0; i < values.length; i++) {
            final String val = values[i];
            boolean sel = val.equals(cur);
            TextView row = UiKit.text(act, (sel ? "✓  " : "") + I18n.tr(labels[i]), 14f,
                    sel ? AppTheme.accent() : AppTheme.text(), sel ? UiKit.BOLD : UiKit.MEDIUM);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(UiKit.rowRipple(act));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                sm.setRetentionPolicy(val);
                try { d.dismiss(); } catch (Throwable ignored) { }
                refresh(true);
            });
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        Button close = UiKit.outline(act, I18n.tr("Close"), v -> {
            try { d.dismiss(); } catch (Throwable ignored) { }
        });
        box.addView(UiKit.buttonRow(act, close));
        if (dead()) return;
        try { d.show(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ importer card

    private View buildImporterCard() {
        LinearLayout c = UiKit.card(act);
        c.addView(UiKit.overline(act, I18n.tr("Movie importer")));
        importerBody = UiKit.column(act);
        c.addView(importerBody, new LinearLayout.LayoutParams(-1, -2));
        renderImporter();
        return c;
    }

    private void renderImporter() {
        if (importerBody == null) return;
        importerBody.removeAllViews();
        final DownloadDetectionManager det = DownloadDetectionManager.getInstance(act);

        TextView desc = UiKit.body(act, I18n.tr("Organises downloaded video files into Jellyfin movie folders with automatic metadata matching."));
        desc.setPadding(0, 0, 0, dp(10));
        importerBody.addView(desc);

        LinearLayout stats = UiKit.row(act);
        final TextView pendingV = stat(stats, I18n.tr("Pending"), false);
        final TextView doneV = stat(stats, I18n.tr("Imported"), true);
        final TextView failedV = stat(stats, I18n.tr("Failed"), true);
        importerBody.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        final TextView lastV = UiKit.singleLine(UiKit.caption(act, ""));
        lastV.setPadding(0, dp(8), 0, 0);
        lastV.setVisibility(View.GONE);
        importerBody.addView(lastV);
        loadStats(pendingV, doneV, failedV, lastV);

        if (det == null) {
            importerBody.addView(UiKit.error(act, I18n.tr("Importer unavailable"),
                    I18n.tr("Download detection could not start."), null, null));
            return;
        }
        importerBody.addView(switchRow(I18n.tr("Detect new downloads"),
                I18n.tr("Watch for new videos and offer to import them."),
                det.isDetectionEnabled(), on -> det.setDetectionEnabled(on)));
        importerBody.addView(switchRow(I18n.tr("Keep the screen on while importing"),
                I18n.tr("Prevents the phone from sleeping during long copies."),
                det.isKeepScreenOnEnabled(), on -> det.setKeepScreenOnEnabled(on)));

        final Button scan = UiKit.outline(act, I18n.tr("Scan Now"), null);
        scan.setOnClickListener(v -> {
            if (!det.isDetectionEnabled()) {
                toast(I18n.tr("Turn on “Detect new downloads” first."));
                return;
            }
            try {
                det.scanForNewDownloads();
                toast(I18n.tr("Scanning for new downloads…"));
            } catch (Throwable t) {
                toast(String.valueOf(t.getMessage()));
            }
            setBusy(scan, true);
            importerBody.postDelayed(() -> {
                setBusy(scan, false);
                loadStats(pendingV, doneV, failedV, lastV);
            }, 2500);
        });
        Button open = UiKit.primary(act, I18n.tr("Open Importer"),
                v -> act.startActivity(new Intent(act, MovieImportActivity.class)));
        importerBody.addView(UiKit.buttonRow(act, scan, open));
    }

    /** Journal statistics are read off the UI thread (the journal loads from disk on first use). */
    private void loadStats(final TextView pendingV, final TextView doneV, final TextView failedV, final TextView lastV) {
        final int g = ++statsGen;
        try {
            statsBg.execute(() -> {
                int pending = 0, done = 0, failed = 0;
                String last = null;
                boolean ok = true;
                try {
                    MovieImportJournal j = MovieImportJournal.getInstance(act);
                    List<MovieImportJournal.ImportRecord> all = j == null ? null : j.getAllRecords();
                    if (all != null) {
                        for (MovieImportJournal.ImportRecord r : all) {
                            if (r == null || r.status == null) continue;
                            if ("PENDING".equals(r.status) || "IN_PROGRESS".equals(r.status)) pending++;
                            else if ("COMPLETED".equals(r.status)) {
                                done++;
                                if (last == null) {
                                    last = r.movieTitle != null && !r.movieTitle.isEmpty() ? r.movieTitle : r.sourceFilename;
                                }
                            } else if ("FAILED".equals(r.status)) failed++;
                        }
                    }
                } catch (Throwable t) {
                    ok = false;
                }
                final int p = pending, d = done, f = failed;
                final String l = last;
                final boolean fine = ok;
                act.runOnUiThread(() -> {
                    if (g != statsGen || dead()) return;
                    pendingV.setText(fine ? String.valueOf(p) : "—");
                    pendingV.setTextColor(p > 0 ? AppTheme.warning() : AppTheme.text());
                    doneV.setText(fine ? String.valueOf(d) : "—");
                    doneV.setTextColor(d > 0 ? AppTheme.success() : AppTheme.text());
                    failedV.setText(fine ? String.valueOf(f) : "—");
                    failedV.setTextColor(f > 0 ? AppTheme.danger() : AppTheme.text());
                    if (l != null && !l.isEmpty()) {
                        lastV.setText(tf("Last import: {0}", l));
                        lastV.setVisibility(View.VISIBLE);
                    } else {
                        lastV.setVisibility(View.GONE);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    // ------------------------------------------------------------------ small UI helpers

    private interface Toggle {
        void on(boolean checked);
    }

    private View switchRow(String label, String hint, boolean checked, final Toggle t) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(0, dp(12), 0, 0);
        LinearLayout col = UiKit.column(act);
        col.addView(UiKit.text(act, label, 14f, AppTheme.text(), null));
        TextView h = UiKit.caption(act, hint);
        h.setPadding(0, dp(2), 0, 0);
        col.addView(h);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        clp.setMarginEnd(dp(12));
        row.addView(col, clp);
        final Switch sw = new Switch(act);
        sw.setChecked(checked);
        UiStyle.styleSwitch(act, sw);
        sw.setOnCheckedChangeListener((b, on) -> {
            try {
                t.on(on);
            } catch (Throwable e) {
                toast(String.valueOf(e.getMessage()));
            }
        });
        row.addView(sw);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** Adds an inset statistic tile to the row and returns its value view. */
    private TextView stat(LinearLayout parent, String label, boolean gap) {
        LinearLayout c = UiKit.inset(act);
        c.setGravity(android.view.Gravity.CENTER);
        TextView v = UiKit.text(act, "—", 20f, AppTheme.text(), UiKit.BOLD);
        v.setGravity(android.view.Gravity.CENTER);
        c.addView(v, new LinearLayout.LayoutParams(-1, -2));
        TextView l = UiKit.singleLine(UiKit.caption(act, label));
        l.setGravity(android.view.Gravity.CENTER);
        c.addView(l, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        if (gap) lp.setMarginStart(dp(8));
        parent.addView(c, lp);
        return v;
    }

    private Button compact(Button b) {
        b.setTextSize(12.5f);
        b.setMinHeight(dp(32));
        b.setMinimumHeight(dp(32));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private static void setBusy(View b, boolean busy) {
        if (b == null) return;
        b.setEnabled(!busy);
        b.setAlpha(busy ? 0.5f : 1f);
    }

    private void toast(String s) {
        if (s == null || dead()) return;
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    private void copy(String s) {
        if (s == null || s.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("path", s));
            toast(I18n.tr("Path copied"));
        } catch (Throwable ignored) { }
    }

    private static String tf(String template, Object... args) {
        String s = I18n.tr(template);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    private int dp(float v) {
        return UiKit.dp(act, v);
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
