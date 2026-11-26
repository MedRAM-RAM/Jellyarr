package com.jellyarr.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.jellyarr.app.permissions.AppPermissionManager;
import com.jellyarr.app.services.JellyfinLibraryClient;
import com.jellyarr.app.services.RadarrApi;
import com.jellyarr.app.services.SonarrApi;
import com.jellyarr.app.storage.MediaMover;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Media storage: the Movies and TV libraries exactly as Jellyfin sees them.
 *
 * <ul>
 *   <li>Every drive (internal storage, SD card, USB) with its free space.</li>
 *   <li>Every Jellyfin movie / TV library with each of its folders: size, file count, drive, and whether Radarr or
 *       Sonarr save into it.</li>
 *   <li>Problems found automatically: missing or unreadable folders, and Radarr / Sonarr folders that no Jellyfin
 *       library shows (one tap adds them).</li>
 *   <li>Move a library folder to another folder or drive. Files are moved safely (see {@link MediaMover}) and
 *       Jellyfin, Radarr and Sonarr are pointed to the new place afterwards.</li>
 *   <li>Add a folder to a library, remove one (files stay), create a missing Movies / TV library.</li>
 * </ul>
 *
 * Network and disk work runs off the UI thread. Used in the Media tab and in {@link JellyfinStorageActivity}.
 */
public final class MediaLibrariesView {

    static final String MOVIES = "movies";
    static final String TV = "tvshows";
    private static final long MAX_COUNT = 200000;

    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout body;
    private final Button refreshBtn;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private boolean loading = false;
    private long lastLoad = 0;
    private Snapshot snap;

    // ------------------------------------------------------------------ model

    private static final class Drive {
        String name;
        String root;
        long total;
        long free;
        boolean removable;
    }

    private static final class Loc {
        String library;
        String type;
        String path;
        long bytes;
        long files;
        boolean truncated;
        boolean missing;
        boolean noAccess;
        Drive drive;
        boolean radarr;
        boolean sonarr;
    }

    private static final class Lib {
        String name;
        String type;
        final List<Loc> locs = new ArrayList<>();
    }

    /** A Radarr / Sonarr root folder that no Jellyfin library of the right type shows. */
    private static final class Orphan {
        String service;
        String type;
        String path;
    }

    private static final class Snapshot {
        final List<Drive> drives = new ArrayList<>();
        final List<Lib> libs = new ArrayList<>();
        final List<Orphan> orphans = new ArrayList<>();
        String jellyfinError;
        boolean radarrUp;
        boolean sonarrUp;
    }

    public MediaLibrariesView(Activity activity) {
        this.act = activity;
        root = UiKit.card(act);
        LinearLayout head = UiKit.row(act);
        head.addView(UiKit.overline(act, I18n.tr("Media storage")), new LinearLayout.LayoutParams(0, -2, 1.0f));
        refreshBtn = UiKit.outline(act, I18n.tr("Refresh"), v -> refresh(true));
        refreshBtn.setTextSize(12.5f);
        refreshBtn.setPadding(dp(12), 0, dp(12), 0);
        head.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(32)));
        root.addView(head);
        body = UiKit.column(act);
        root.addView(body, new LinearLayout.LayoutParams(-1, -2));
        body.addView(UiKit.loading(act, I18n.tr("Reading libraries…")));
    }

    public View getView() {
        return root;
    }

    /** Reloads drives, libraries and folder sizes (at most every 30 s unless forced). */
    public void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (loading || (!force && snap != null && now - lastLoad < 30000)) return;
        loading = true;
        refreshBtn.setEnabled(false);
        refreshBtn.setAlpha(0.5f);
        if (snap == null) {
            body.removeAllViews();
            body.addView(UiKit.loading(act, I18n.tr("Reading libraries…")));
        }
        try {
            bg.execute(() -> {
                final Snapshot s = load();
                act.runOnUiThread(() -> {
                    loading = false;
                    refreshBtn.setEnabled(true);
                    refreshBtn.setAlpha(1f);
                    if (dead()) return;
                    lastLoad = System.currentTimeMillis();
                    snap = s;
                    render(s);
                });
            });
        } catch (RejectedExecutionException e) {
            loading = false;
        }
    }

    private boolean dead() {
        return act.isFinishing() || act.isDestroyed();
    }

    // ------------------------------------------------------------------ loading (background)

    private Snapshot load() {
        Snapshot s = new Snapshot();
        try {
            for (StorageDriveHelper.DriveInfo d : StorageDriveHelper.getMountedDrives(act)) {
                Drive dr = new Drive();
                dr.name = d.isPrimary ? I18n.tr("Internal storage") : (d.name == null || d.name.isEmpty() ? d.rootPath : d.name);
                dr.root = trim(d.rootPath);
                dr.total = d.totalBytes;
                dr.free = d.freeBytes;
                dr.removable = d.isRemovable;
                s.drives.add(dr);
            }
        } catch (Throwable ignored) {
            // shown as "no drives"
        }

        List<String> radarrRoots = new ArrayList<>();
        List<String> sonarrRoots = new ArrayList<>();
        try {
            if (RadarrApi.isPortOpen()) {
                JSONArray a = RadarrApi.get(act).rootFolders();
                for (int i = 0; i < a.length(); i++) radarrRoots.add(trim(a.optJSONObject(i).optString("path", "")));
                s.radarrUp = true;
            }
        } catch (Throwable ignored) {
            // Radarr not available
        }
        try {
            JSONArray a = SonarrApi.get(act).rootFolders();
            for (int i = 0; i < a.length(); i++) sonarrRoots.add(trim(a.optJSONObject(i).optString("path", "")));
            s.sonarrUp = true;
        } catch (Throwable ignored) {
            // Sonarr not available
        }

        try {
            JSONArray vf = JellyfinLibraryClient.virtualFolders(act);
            for (int i = 0; i < vf.length(); i++) {
                JSONObject o = vf.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("CollectionType", "").toLowerCase(Locale.ROOT);
                if (!MOVIES.equals(type) && !TV.equals(type)) continue;
                Lib lib = new Lib();
                lib.name = o.optString("Name", "");
                lib.type = type;
                JSONArray locs = o.optJSONArray("Locations");
                for (int j = 0; locs != null && j < locs.length(); j++) {
                    Loc l = new Loc();
                    l.library = lib.name;
                    l.type = type;
                    l.path = trim(locs.optString(j));
                    File dir = new File(l.path);
                    if (!dir.isDirectory()) {
                        l.missing = true;
                    } else if (dir.listFiles() == null) {
                        l.noAccess = true;
                    } else {
                        MediaMover.Stats st = MediaMover.measure(dir, MAX_COUNT);
                        l.bytes = st.bytes;
                        l.files = st.files;
                        l.truncated = st.truncated;
                    }
                    l.drive = driveOf(s.drives, l.path);
                    l.radarr = MOVIES.equals(type) && radarrRoots.contains(l.path);
                    l.sonarr = TV.equals(type) && sonarrRoots.contains(l.path);
                    lib.locs.add(l);
                }
                s.libs.add(lib);
            }
        } catch (Throwable t) {
            s.jellyfinError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }

        if (s.jellyfinError == null) {
            for (String r : radarrRoots) if (!r.isEmpty() && !shownBy(s, MOVIES, r)) s.orphans.add(orphan("Radarr", MOVIES, r));
            for (String r : sonarrRoots) if (!r.isEmpty() && !shownBy(s, TV, r)) s.orphans.add(orphan("Sonarr", TV, r));
        }
        return s;
    }

    private static Orphan orphan(String service, String type, String path) {
        Orphan o = new Orphan();
        o.service = service;
        o.type = type;
        o.path = path;
        return o;
    }

    /** True when a library of {@code type} contains {@code path} (or a parent of it). */
    private static boolean shownBy(Snapshot s, String type, String path) {
        for (Lib lib : s.libs) {
            if (!lib.type.equals(type)) continue;
            for (Loc l : lib.locs) if (path.equals(l.path) || path.startsWith(l.path + "/")) return true;
        }
        return false;
    }

    private static Drive driveOf(List<Drive> drives, String path) {
        Drive best = null;
        for (Drive d : drives) {
            if ((path.equals(d.root) || path.startsWith(d.root + "/")) && (best == null || d.root.length() > best.root.length())) {
                best = d;
            }
        }
        return best;
    }

    static String trim(String p) {
        if (p == null) return "";
        String t = p.trim();
        while (t.length() > 1 && t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    // ------------------------------------------------------------------ rendering

    private void render(Snapshot s) {
        body.removeAllViews();

        boolean perm = true;
        try {
            perm = AppPermissionManager.isStorageGranted(act);
        } catch (Throwable ignored) {
            // assume granted
        }
        if (!perm) {
            body.addView(UiKit.error(act, I18n.tr("Storage access is off"),
                    I18n.tr("Jellyarr needs All files access to measure and move media."),
                    I18n.tr("Allow Access"), v -> {
                        try {
                            AppPermissionManager.requestStorage(act);
                        } catch (Throwable t) {
                            toast(String.valueOf(t.getMessage()));
                        }
                    }));
        }

        // Drives
        for (Drive d : s.drives) {
            LinearLayout row = UiKit.column(act);
            row.setPadding(0, dp(10), 0, dp(4));
            LinearLayout line = UiKit.row(act);
            line.addView(UiKit.text(act, d.name, 14f, AppTheme.text(), UiKit.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1.0f));
            line.addView(UiKit.caption(act, tf("{0} free of {1}", fmt(d.free), fmt(d.total))));
            row.addView(line);
            int used = d.total > 0 ? (int) Math.max(0, Math.min(100, 100 * (d.total - d.free) / d.total)) : 0;
            row.addView(UiKit.progress(act, used, used >= 95 ? AppTheme.danger() : used >= 85 ? AppTheme.warning() : AppTheme.accent()));
            body.addView(row);
        }

        if (s.jellyfinError != null) {
            TextView t = UiKit.caption(act, I18n.tr("Start Jellyfin to see its libraries.") + "\n" + s.jellyfinError);
            t.setTextColor(AppTheme.warning());
            t.setPadding(0, dp(12), 0, 0);
            body.addView(t);
            return;
        }

        renderCategory(s, MOVIES, I18n.tr("Movies"));
        renderCategory(s, TV, I18n.tr("TV Shows"));
    }

    private void renderCategory(Snapshot s, final String type, String title) {
        TextView h = UiKit.subtitle(act, title);
        h.setPadding(0, dp(18), 0, dp(2));
        body.addView(h);

        List<Lib> libs = new ArrayList<>();
        long total = 0;
        for (Lib l : s.libs) {
            if (!l.type.equals(type)) continue;
            libs.add(l);
            for (Loc x : l.locs) total += x.bytes;
        }
        if (libs.isEmpty()) {
            TextView none = UiKit.caption(act, I18n.tr("No Jellyfin library of this type yet."));
            body.addView(none);
            final String def = defaultFolder(type, null);
            body.addView(UiKit.buttonRow(act, UiKit.tonal(act, I18n.tr(MOVIES.equals(type) ? "Create Movies library" : "Create TV library"),
                    v -> confirmCreateLibrary(type, def))));
        } else {
            body.addView(UiKit.caption(act, tf("{0} in {1} libraries", fmt(total), libs.size())));
        }

        for (final Lib lib : libs) {
            LinearLayout libHead = UiKit.row(act);
            libHead.setPadding(0, dp(12), 0, dp(2));
            libHead.addView(UiKit.text(act, lib.name, 15f, AppTheme.text(), UiKit.BOLD), new LinearLayout.LayoutParams(0, -2, 1.0f));
            Button add = UiKit.outline(act, I18n.tr("Add folder"), v -> showAddFolderDialog(lib));
            add.setTextSize(12f);
            add.setPadding(dp(10), 0, dp(10), 0);
            libHead.addView(add, new LinearLayout.LayoutParams(-2, dp(30)));
            body.addView(libHead);
            if (lib.locs.isEmpty()) body.addView(UiKit.caption(act, I18n.tr("This library has no folder.")));
            for (Loc l : lib.locs) body.addView(locRow(lib, l));
        }

        for (final Orphan o : s.orphans) {
            if (!o.type.equals(type)) continue;
            LinearLayout warn = UiKit.inset(act);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
            wl.topMargin = dp(10);
            warn.setLayoutParams(wl);
            TextView t = UiKit.caption(act, tf("{0} saves into {1}, but no Jellyfin library shows this folder.", o.service, o.path));
            t.setTextColor(AppTheme.warning());
            warn.addView(t);
            final Lib target = libs.isEmpty() ? null : libs.get(0);
            warn.addView(UiKit.buttonRow(act, UiKit.tonal(act,
                    target == null ? I18n.tr(MOVIES.equals(type) ? "Create Movies library" : "Create TV library")
                            : tf("Add to {0}", target.name),
                    v -> {
                        if (target == null) confirmCreateLibrary(type, o.path);
                        else libraryCall(I18n.tr("Folder added"), () -> {
                            JellyfinLibraryClient.addLibraryPath(act, target.name, o.path);
                            JellyfinLibraryClient.refreshLibraries(act);
                        });
                    })));
            body.addView(warn);
        }
    }

    private View locRow(final Lib lib, final Loc l) {
        LinearLayout row = UiKit.column(act);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.setBackground(UiKit.rowRipple(act));
        row.setClickable(true);
        row.setFocusable(true);

        TextView path = UiKit.mono(act, l.path);
        path.setTextSize(12f);
        row.addView(path);

        StringBuilder meta = new StringBuilder();
        int color = AppTheme.textMuted();
        if (l.missing) {
            meta.append(I18n.tr("Folder missing"));
            color = AppTheme.danger();
        } else if (l.noAccess) {
            meta.append(I18n.tr("No access"));
            color = AppTheme.warning();
        } else {
            meta.append(fmt(l.bytes)).append(" · ")
                    .append(l.truncated ? MAX_COUNT + "+" : String.valueOf(l.files)).append(' ').append(I18n.tr("files"));
        }
        if (l.drive != null) meta.append(" · ").append(l.drive.name);
        if (l.radarr) meta.append(" · Radarr");
        if (l.sonarr) meta.append(" · Sonarr");
        TextView m = UiKit.caption(act, meta.toString());
        m.setTextColor(color);
        m.setPadding(0, dp(3), 0, 0);
        row.addView(m);

        if (snap != null && !snap.libs.isEmpty()) {
            long max = 1;
            for (Lib x : snap.libs) for (Loc y : x.locs) max = Math.max(max, y.bytes);
            row.addView(UiKit.progress(act, (int) (100 * l.bytes / max), AppTheme.accent()));
        }

        Button move = UiKit.tonal(act, I18n.tr("Move…"), v -> showMoveDialog(l));
        move.setEnabled(!l.missing && !l.noAccess);
        move.setAlpha(move.isEnabled() ? 1f : 0.5f);
        Button more = UiKit.outline(act, I18n.tr("More"), v -> showLocMenu(lib, l));
        row.addView(UiKit.buttonRow(act, move, more));
        row.setOnClickListener(v -> copy(l.path));
        return row;
    }

    // ------------------------------------------------------------------ library actions

    private interface Call {
        void run() throws Exception;
    }

    private void libraryCall(final String ok, final Call call) {
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
                    if (dead()) return;
                    toast(fe != null ? fe : ok);
                    refresh(true);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void confirmCreateLibrary(final String type, final String folder) {
        final String name = I18n.tr(MOVIES.equals(type) ? "Movies" : "TV Shows");
        new AlertDialog.Builder(act)
                .setTitle(I18n.tr("Create library"))
                .setMessage(tf("Create the Jellyfin library \"{0}\" for:\n{1}", name, folder))
                .setPositiveButton(I18n.tr("Create"), (d, w) -> libraryCall(I18n.tr("Library created"), () -> {
                    File f = new File(folder);
                    if (!f.isDirectory()) f.mkdirs();
                    JellyfinLibraryClient.createLibrary(act, name, type, folder);
                }))
                .setNegativeButton(I18n.tr("Cancel"), null)
                .show();
    }

    private void showAddFolderDialog(final Lib lib) {
        LinearLayout box = dialogBox();
        box.addView(UiKit.caption(act, I18n.tr("Folder to add to this library")));
        final EditText input = UiKit.input(act, defaultFolder(lib.type, null));
        input.setText(defaultFolder(lib.type, null));
        box.addView(input, topMargin(6));
        new AlertDialog.Builder(act)
                .setTitle(lib.name)
                .setView(box)
                .setPositiveButton(I18n.tr("Add"), (d, w) -> {
                    final String p = trim(input.getText().toString());
                    if (p.isEmpty()) return;
                    libraryCall(I18n.tr("Folder added"), () -> {
                        File f = new File(p);
                        if (!f.isDirectory() && !f.mkdirs()) throw new Exception(I18n.tr("Cannot create the folder"));
                        JellyfinLibraryClient.addLibraryPath(act, lib.name, p);
                        JellyfinLibraryClient.refreshLibraries(act);
                    });
                })
                .setNegativeButton(I18n.tr("Cancel"), null)
                .show();
    }

    private void showLocMenu(final Lib lib, final Loc l) {
        String[] items = {I18n.tr("Copy path"), I18n.tr("Remove from library (files stay)")};
        new AlertDialog.Builder(act)
                .setTitle(l.path)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        copy(l.path);
                    } else {
                        new AlertDialog.Builder(act)
                                .setTitle(I18n.tr("Remove from library"))
                                .setMessage(tf("Jellyfin stops showing this folder in \"{0}\". The files are not deleted.", lib.name))
                                .setPositiveButton(I18n.tr("Remove"), (d2, w2) -> libraryCall(I18n.tr("Folder removed"), () -> {
                                    JellyfinLibraryClient.removeLibraryPath(act, lib.name, l.path);
                                    JellyfinLibraryClient.refreshLibraries(act);
                                }))
                                .setNegativeButton(I18n.tr("Cancel"), null)
                                .show();
                    }
                })
                .show();
    }

    /** {drive}/JellyarrMedia/Movies or /TV; the internal drive when {@code drive} is null. */
    private String defaultFolder(String type, Drive drive) {
        String sub = MOVIES.equals(type) ? MediaStorageManager.SUB_MOVIES : MediaStorageManager.SUB_TV;
        if (drive == null) {
            File f = MOVIES.equals(type) ? MediaStorageManager.getInstance(act).getMoviesDir() : MediaStorageManager.getInstance(act).getTvDir();
            return trim(f.getAbsolutePath());
        }
        return drive.root + "/JellyarrMedia/" + sub;
    }

    // ------------------------------------------------------------------ move

    private void showMoveDialog(final Loc src) {
        final Snapshot s = snap;
        if (s == null) return;
        final List<String> dests = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (Lib lib : s.libs) {
            if (!lib.type.equals(src.type)) continue;
            for (Loc l : lib.locs) {
                if (l.path.equals(src.path) || l.missing || dests.contains(l.path)) continue;
                dests.add(l.path);
                labels.add(lib.name + " · " + l.path);
            }
        }
        for (Drive d : s.drives) {
            String p = defaultFolder(src.type, d);
            if (p.equals(src.path) || dests.contains(p)) continue;
            dests.add(p);
            labels.add(d.name + " · " + p);
        }

        LinearLayout box = dialogBox();
        box.addView(UiKit.caption(act, tf("Move {0} ({1}) to:", src.path, fmt(src.bytes))));
        final RadioGroup group = new RadioGroup(act);
        final List<RadioButton> buttons = new ArrayList<>();
        for (String label : labels) {
            RadioButton rb = new RadioButton(act);
            rb.setText(label);
            rb.setTextColor(AppTheme.text());
            rb.setId(View.generateViewId());
            group.addView(rb);
            buttons.add(rb);
        }
        final RadioButton other = new RadioButton(act);
        other.setText(I18n.tr("Another folder"));
        other.setTextColor(AppTheme.text());
        other.setId(View.generateViewId());
        group.addView(other);
        box.addView(group, topMargin(6));
        final EditText custom = UiKit.input(act, "/storage/XXXX-XXXX/JellyarrMedia/" + (MOVIES.equals(src.type) ? "Movies" : "TV"));
        custom.setVisibility(View.GONE);
        box.addView(custom, topMargin(4));
        final TextView info = UiKit.caption(act, "");
        info.setPadding(0, dp(8), 0, 0);
        box.addView(info);
        final CheckBox update = UiStyle.newCheckBox(act);
        update.setText(I18n.tr("Update Jellyfin, Radarr and Sonarr to the new folder"));
        update.setTextColor(AppTheme.text());
        update.setChecked(true);
        box.addView(update, topMargin(6));

        group.setOnCheckedChangeListener((g, id) -> {
            custom.setVisibility(id == other.getId() ? View.VISIBLE : View.GONE);
            String d = destOf(group, buttons, dests, other, custom);
            info.setText(spaceInfo(s, src, d));
        });
        if (!buttons.isEmpty()) group.check(buttons.get(0).getId());
        else group.check(other.getId());

        new AlertDialog.Builder(act)
                .setTitle(I18n.tr("Move media"))
                .setView(box)
                .setPositiveButton(I18n.tr("Move"), (d, w) -> {
                    String dest = trim(destOf(group, buttons, dests, other, custom));
                    if (dest.isEmpty()) return;
                    String problem = checkMove(s, src, dest);
                    if (problem != null) {
                        new AlertDialog.Builder(act).setTitle(I18n.tr("Cannot move")).setMessage(problem)
                                .setPositiveButton(I18n.tr("OK"), null).show();
                        return;
                    }
                    runMove(src, dest, update.isChecked());
                })
                .setNegativeButton(I18n.tr("Cancel"), null)
                .show();
    }

    private static String destOf(RadioGroup g, List<RadioButton> buttons, List<String> dests, RadioButton other, EditText custom) {
        int id = g.getCheckedRadioButtonId();
        if (id == other.getId()) return custom.getText().toString();
        for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).getId() == id) return dests.get(i);
        return "";
    }

    private String spaceInfo(Snapshot s, Loc src, String dest) {
        Drive dd = driveOf(s.drives, trim(dest));
        if (dd == null) return "";
        if (src.drive != null && dd.root.equals(src.drive.root)) return I18n.tr("Same drive: the move is instant and needs no extra space.");
        return tf("Needs {0}; {1} free on {2}.", fmt(src.bytes), fmt(dd.free), dd.name);
    }

    private String checkMove(Snapshot s, Loc src, String dest) {
        if (!dest.startsWith("/")) return I18n.tr("Enter a full path starting with /");
        File sf = new File(src.path);
        File df = new File(dest);
        if (MediaMover.isInside(df, sf) || MediaMover.isInside(sf, df)) {
            return I18n.tr("The destination must not be inside the source folder (or the other way round).");
        }
        Drive dd = driveOf(s.drives, dest);
        boolean sameDrive = dd != null && src.drive != null && dd.root.equals(src.drive.root);
        if (!sameDrive && dd != null && dd.free < src.bytes + 200L * 1024 * 1024) {
            return tf("Not enough space on {0}: {1} needed, {2} free.", dd.name, fmt(src.bytes), fmt(dd.free));
        }
        return null;
    }

    private void runMove(final Loc src, final String dest, final boolean updateRefs) {
        LinearLayout box = dialogBox();
        final TextView status = UiKit.body(act, I18n.tr("Preparing…"));
        box.addView(status);
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(bar, topMargin(10));
        final TextView current = UiKit.caption(act, "");
        current.setPadding(0, dp(6), 0, 0);
        box.addView(current);
        final AtomicBoolean cancel = new AtomicBoolean(false);
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(I18n.tr("Moving media"))
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(I18n.tr("Stop"), null)
                .create();
        dlg.show();
        final Button stop = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (stop != null) {
            stop.setOnClickListener(v -> {
                cancel.set(true);
                stop.setEnabled(false);
                status.setText(I18n.tr("Stopping after the current file…"));
            });
        }

        final long[] lastUi = {0};
        new Thread(() -> {
            MediaMover.Result r = MediaMover.move(new File(src.path), new File(dest), (fd, ft, bd, bt, name) -> {
                long now = System.currentTimeMillis();
                if (now - lastUi[0] < 250 && fd < ft) return;
                lastUi[0] = now;
                final int pm = bt > 0 ? (int) (1000 * bd / bt) : (ft > 0 ? (int) (1000 * fd / ft) : 0);
                final String line = fd + " / " + ft + " · " + fmt(bd) + " / " + fmt(bt);
                act.runOnUiThread(() -> {
                    if (dead() || cancel.get()) return;
                    bar.setProgress(pm);
                    status.setText(line);
                    current.setText(name == null ? "" : name);
                });
            }, cancel);

            final StringBuilder report = new StringBuilder();
            report.append(tf("{0} files moved ({1}).", r.filesMoved, fmt(r.bytesMoved)));
            if (r.filesSkippedIdentical > 0) report.append('\n').append(tf("{0} were already there.", r.filesSkippedIdentical));
            if (r.filesRenamed > 0) report.append('\n').append(tf("{0} kept with a new name to avoid overwriting.", r.filesRenamed));
            if (r.cancelled) {
                report.append("\n\n").append(I18n.tr("Stopped. Start the move again to finish; files already moved are skipped. Jellyfin, Radarr and Sonarr were not changed."));
            } else if (!r.errors.isEmpty()) {
                report.append("\n\n").append(I18n.tr("Some files could not be moved. Jellyfin, Radarr and Sonarr were not changed; fix the problem and move again:"));
                for (int i = 0; i < r.errors.size() && i < 5; i++) report.append("\n• ").append(r.errors.get(i));
            } else if (updateRefs) {
                report.append("\n\n").append(updateReferences(src, dest));
            }
            act.runOnUiThread(() -> {
                if (dead()) return;
                try { dlg.dismiss(); } catch (Throwable ignored) {}
                new AlertDialog.Builder(act)
                        .setTitle(I18n.tr(r.ok() ? "Move finished" : "Move incomplete"))
                        .setMessage(report.toString())
                        .setPositiveButton(I18n.tr("OK"), null)
                        .show();
                refresh(true);
            });
        }, "jellyarr-media-move").start();
    }

    /** Points Jellyfin, Radarr and Sonarr at {@code dest}. Runs on the move thread. Returns a readable summary. */
    private String updateReferences(Loc src, String dest) {
        StringBuilder sb = new StringBuilder();
        // Jellyfin: the library keeps its content, now from the new folder.
        try {
            boolean destAlreadyShown = false;
            Snapshot s = snap;
            if (s != null) {
                for (Lib lib : s.libs) {
                    if (!lib.type.equals(src.type)) continue;
                    for (Loc l : lib.locs) if (l.path.equals(dest)) destAlreadyShown = true;
                }
            }
            if (!destAlreadyShown) JellyfinLibraryClient.addLibraryPath(act, src.library, dest);
            JellyfinLibraryClient.removeLibraryPath(act, src.library, src.path);
            JellyfinLibraryClient.refreshLibraries(act);
            sb.append(I18n.tr("Jellyfin: library updated, scan started."));
        } catch (Throwable t) {
            sb.append(I18n.tr("Jellyfin: not updated")).append(" (").append(t.getMessage()).append(')');
        }
        // Radarr / Sonarr: re-point the items stored under the old folder (files are already in place).
        if (MOVIES.equals(src.type)) {
            try {
                if (RadarrApi.isPortOpen()) {
                    RadarrApi api = RadarrApi.get(act);
                    int n = api.repointRoot(src.path, dest);
                    api.simpleCommand("RescanMovie");
                    sb.append('\n').append(tf("Radarr: {0} movies updated.", n));
                }
            } catch (Throwable t) {
                sb.append('\n').append(I18n.tr("Radarr: not updated")).append(" (").append(t.getMessage()).append(')');
            }
        } else {
            try {
                SonarrApi api = SonarrApi.get(act);
                int n = api.repointRoot(src.path, dest);
                api.simpleCommand("RescanSeries");
                sb.append('\n').append(tf("Sonarr: {0} series updated.", n));
            } catch (Throwable t) {
                if (!SonarrApi.isNotRunning(t)) {
                    sb.append('\n').append(I18n.tr("Sonarr: not updated")).append(" (").append(t.getMessage()).append(')');
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout dialogBox() {
        LinearLayout box = UiKit.column(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        return box;
    }

    private LinearLayout.LayoutParams topMargin(int dpv) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(dpv);
        return lp;
    }

    private void toast(String s) {
        if (s == null || dead()) return;
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("path", s));
            toast(I18n.tr("Path copied"));
        } catch (Throwable ignored) {
            // clipboard unavailable
        }
    }

    private static String tf(String template, Object... args) {
        String s = I18n.tr(template);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    private int dp(float v) {
        return UiKit.dp(act, v);
    }

    static String fmt(long b) {
        return com.jellyarr.app.util.Fmt.bytes(b);
    }
}
