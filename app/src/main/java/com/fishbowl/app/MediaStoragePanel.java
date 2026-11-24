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
 * Media storage panel (free space, usage per library folder, retention policy). It is shown inside the
 * Sync Stack dialog so storage and synchronisation live in one place.
 *
 * Folder sizes are computed off the UI thread; missing folders and missing storage permission are shown
 * as states instead of silently reading as 0 B.
 */
public final class MediaStoragePanel {

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

    private LinearLayout storageBody;
    private Button refreshBtn;
    private long lastScan = 0;
    private boolean scanning = false;

    public MediaStoragePanel(Activity activity) {
        this.act = activity;
        root = UiKit.column(act);
        root.addView(buildStorageCard());
    }

    public View getView() {
        return root;
    }

    /** Re-measures the storage sizes at most every 30 s (unless forced). */
    public void refresh(boolean force) {
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

    // ------------------------------------------------------------------ small UI helpers

    /** Adds an inset statistic tile to the row and returns its value view. */
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
