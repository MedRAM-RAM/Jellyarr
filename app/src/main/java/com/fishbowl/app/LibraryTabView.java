package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.services.JellyfinLibraryClient;
import com.fishbowl.app.services.LibraryAutoDelete;
import com.fishbowl.app.services.LibraryPrefs;
import com.fishbowl.app.services.MediaServiceManager;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "Library" tab: browse Jellyfin movies / series / seasons / episodes with delete actions,
 * a "recently watched" list (latest first) and the auto-delete-after-watching settings.
 *
 * <p>Sign-in is automatic: the client tries the saved token, the app's unified account, an API key and known
 * passwords. Only when all of them fail is a sign-in form shown (pre-filled with the unified username).
 */
public final class LibraryTabView {

    private static final int MODE_MOVIES = 0;
    private static final int MODE_SERIES = 1;
    private static final int MODE_RECENT = 2;
    private static final int MODE_AUTO = 3;
    private static final String[] MODE_NAMES = {"Movies", "Series", "Recently watched", "Auto-delete"};
    private static final int LIST_CHUNK = 60;

    private interface Job<T> { T run() throws Exception; }
    private interface Done<T> { void run(T value); }
    private interface BoolSetter { void set(boolean v); }
    private interface IntSetter { void set(int v); }
    private interface IntGetter { int get(); }

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final LinearLayout headerSlot;
    private final LinearLayout chipRow;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private final ExecutorService imgPool = Executors.newFixedThreadPool(3);
    private final Map<String, List<ImageView>> imgWaiting = new HashMap<>();
    private static LruCache<String, Bitmap> posterCache;

    private int mode = MODE_MOVIES;
    private int level = 0; // series: 0 = series list, 1 = seasons, 2 = episodes
    private String seriesId, seriesName, seasonId, seasonName;
    private int requestCounter = 0;
    private int listLimit = LIST_CHUNK;
    private String filterText = "";
    private List<JellyfinLibraryClient.LibItem> lastItems = null;
    private String lastCount = null;
    private boolean autoSignInTried = false;
    private boolean attached = true;
    private boolean starting = false;

    private LinearLayout rowsBox;
    private TextView countView;

    public LibraryTabView(Activity activity) {
        this.act = activity;

        root = UiKit.column(act);
        root.setBackgroundColor(AppTheme.bg());

        headerSlot = UiKit.column(act);
        headerSlot.setPadding(dp(16), dp(12), dp(16), 0);
        root.addView(headerSlot, new LinearLayout.LayoutParams(-1, -2));

        chipRow = UiKit.row(act);
        chipRow.setPadding(dp(16), 0, dp(16), dp(10));
        root.addView(UiKit.chipScroller(act, chipRow), new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        content = UiKit.column(act);
        content.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { attached = true; }
            @Override public void onViewDetachedFromWindow(View v) { attached = false; }
        });
        updateHeader();
        renderNav();
    }

    public View getView() { return root; }

    /** Returns true when the back press was consumed (navigated up inside the tab). */
    public boolean onBack() {
        if (mode == MODE_SERIES && level > 0) {
            level--;
            refresh();
            return true;
        }
        if (mode != MODE_MOVIES) {
            mode = MODE_MOVIES;
            level = 0;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        rowsBox = null;
        countView = null;
        renderNav();
        updateHeader();
        if (mode == MODE_AUTO) renderAutoDelete();
        else if (mode == MODE_RECENT) loadRecent();
        else loadBrowse();
    }

    // ================================================================== chrome

    private void updateHeader() {
        headerSlot.removeAllViews();
        String user = JellyfinLibraryClient.currentUserName();
        String sub;
        if (lastCount != null && user != null && !user.isEmpty()) sub = trf("{0} · signed in as {1}", lastCount, user);
        else if (lastCount != null) sub = lastCount;
        else if (user != null && !user.isEmpty()) sub = trf("Jellyfin · signed in as {0}", user);
        else sub = tr("Jellyfin movies and series");
        Button refreshBtn = UiKit.outline(act, tr("Refresh"), v -> refresh());
        headerSlot.addView(UiKit.header(act, tr("Library"), sub, refreshBtn));
    }

    private void renderNav() {
        chipRow.removeAllViews();
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int idx = i;
            TextView chip = UiKit.chip(act, tr(MODE_NAMES[i]), idx == mode);
            chip.setOnClickListener(v -> {
                if (idx == mode && level == 0) {
                    scroll.smoothScrollTo(0, 0);
                    return;
                }
                mode = idx;
                level = 0;
                listLimit = LIST_CHUNK;
                filterText = "";
                refresh();
                scroll.scrollTo(0, 0);
            });
            chipRow.addView(chip, UiKit.chipLp(act));
        }
    }

    // ================================================================== async plumbing

    private boolean dead() {
        return act.isFinishing() || act.isDestroyed();
    }

    private void ui(final Runnable r) {
        if (dead()) return;
        main.post(() -> {
            if (!dead()) r.run();
        });
    }

    private <T> void async(final Job<T> job, final Done<T> ok, final Done<Throwable> err) {
        try {
            bg.execute(() -> {
                T result = null;
                Throwable failure = null;
                try {
                    result = job.run();
                } catch (Throwable t) {
                    failure = t;
                }
                final T res = result;
                final Throwable fail = failure;
                ui(() -> {
                    if (fail == null) {
                        if (ok != null) ok.run(res);
                    } else if (err != null) {
                        err.run(fail);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // executor gone: the activity is being torn down
        }
    }

    private static void setBusy(View v, boolean busy) {
        if (v == null) return;
        v.setEnabled(!busy);
        v.setAlpha(busy ? 0.5f : 1f);
    }

    private void toast(String s) {
        if (dead() || s == null) return;
        Toast.makeText(act, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private static String tr(String s) { return I18n.tr(s); }

    private static String trf(String template, Object... args) {
        String s = template;
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return I18n.tr(s);
    }

    private int dp(float v) { return UiKit.dp(act, v); }

    /** First line of an error (the client appends diagnostics after a newline). */
    private static String firstLine(Throwable t) {
        String m = t == null ? null : t.getMessage();
        if (m == null || m.trim().isEmpty()) return tr("Unknown error");
        int nl = m.indexOf('\n');
        return tr(nl > 0 ? m.substring(0, nl) : m);
    }

    private static String details(Throwable t) {
        String m = t == null ? null : t.getMessage();
        if (m == null) return "";
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(nl + 1).trim() : "";
    }

    // ================================================================== states

    private void showLoading(String msg) {
        content.removeAllViews();
        content.addView(UiKit.loading(act, msg));
    }

    private void showError(Throwable err) {
        content.removeAllViews();
        if (err instanceof JellyfinLibraryClient.UnreachableException) {
            View v = UiKit.error(act, tr("Jellyfin is not running"),
                    tr("Start Jellyfin here or from the Services tab. The library loads as soon as it is up."), null, null);
            if (v instanceof LinearLayout) {
                final Button start = UiKit.primary(act, tr(starting ? "Starting…" : "Start Jellyfin"), null);
                start.setOnClickListener(x -> startJellyfin(start));
                if (starting) setBusy(start, true);
                ((LinearLayout) v).addView(UiKit.buttonRow(act, start, UiKit.outline(act, tr("Retry"), x -> refresh())));
            }
            content.addView(v);
            return;
        }
        if (err instanceof JellyfinLibraryClient.AuthException) {
            handleAuthError(err);
            return;
        }
        content.addView(UiKit.error(act, tr("Could not load the library"), firstLine(err), tr("Retry"), x -> refresh()));
    }

    /**
     * No automatic sign-in worked. Try the unified app account once (unless Jellyfin already refused it),
     * otherwise show the sign-in form.
     */
    private void handleAuthError(final Throwable err) {
        if (!autoSignInTried && UnifiedAccount.isConfigured(act) && !JellyfinLibraryClient.hasUnifiedLoginFailed(act)) {
            autoSignInTried = true;
            showLoading(tr("Signing in to Jellyfin…"));
            final String u = UnifiedAccount.getUsername(act).trim();
            final String p = UnifiedAccount.getPassword(act);
            this.<String>async(() -> JellyfinLibraryClient.loginAndStore(act, u, p), res -> {
                if (res == null) refresh();
                else renderSignIn(err, res);
            }, t -> renderSignIn(err, firstLine(t)));
            return;
        }
        renderSignIn(err, null);
    }

    private void renderSignIn(Throwable err, String lastError) {
        content.removeAllViews();
        LinearLayout card = UiKit.card(act);
        card.addView(UiKit.overline(act, tr("Sign in to Jellyfin")));
        boolean unifiedRefused = UnifiedAccount.isConfigured(act) && JellyfinLibraryClient.hasUnifiedLoginFailed(act);
        card.addView(UiKit.body(act, tr(unifiedRefused
                ? "Jellyfin did not accept the app account. Sign in once with a Jellyfin administrator account; the password stays on this device."
                : "Sign in once with a Jellyfin administrator account. The password stays on this device.")));

        final EditText user = UiKit.input(act, tr("Username"));
        String pre = UnifiedAccount.isConfigured(act) ? UnifiedAccount.getUsername(act).trim() : "";
        if (pre.isEmpty()) pre = JellyfinLibraryClient.suggestedUsername(act);
        user.setText(pre);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.topMargin = dp(12);
        card.addView(user, ulp);
        final EditText pass = UiKit.input(act, tr("Password (leave empty if none)"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(8);
        card.addView(pass, plp);

        final TextView errView = UiKit.caption(act, lastError == null ? "" : lastError);
        errView.setTextColor(AppTheme.danger());
        errView.setPadding(0, dp(8), 0, 0);
        errView.setVisibility(lastError == null ? View.GONE : View.VISIBLE);
        card.addView(errView);

        final Button signIn = UiKit.primary(act, tr("Sign in"), null);
        signIn.setOnClickListener(v -> {
            final String u = user.getText().toString().trim();
            final String pw = pass.getText().toString();
            if (u.isEmpty()) {
                errView.setText(tr("Enter a username."));
                errView.setVisibility(View.VISIBLE);
                return;
            }
            if (!signIn.isEnabled()) return;
            setBusy(signIn, true);
            signIn.setText(tr("Signing in…"));
            errView.setVisibility(View.GONE);
            this.<String>async(() -> JellyfinLibraryClient.loginAndStore(act, u, pw), res -> {
                setBusy(signIn, false);
                signIn.setText(tr("Sign in"));
                if (res == null) {
                    toast(tr("Signed in"));
                    refresh();
                } else {
                    errView.setText(tr(res));
                    errView.setVisibility(View.VISIBLE);
                }
            }, t -> {
                setBusy(signIn, false);
                signIn.setText(tr("Sign in"));
                errView.setText(firstLine(t));
                errView.setVisibility(View.VISIBLE);
            });
        });
        pass.setOnEditorActionListener((v, a, e) -> {
            signIn.performClick();
            return true;
        });
        card.addView(UiKit.buttonRow(act, signIn));

        final String diag = details(err);
        if (!diag.isEmpty()) {
            final TextView toggle = UiKit.caption(act, tr("Show details"));
            toggle.setTextColor(AppTheme.accent());
            toggle.setPadding(0, dp(12), 0, dp(4));
            final TextView detailText = UiKit.mono(act, diag);
            detailText.setTextColor(AppTheme.textSecondary());
            detailText.setVisibility(View.GONE);
            toggle.setOnClickListener(v -> {
                boolean show = detailText.getVisibility() != View.VISIBLE;
                detailText.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText(tr(show ? "Hide details" : "Show details"));
            });
            card.addView(toggle);
            card.addView(detailText);
        }
        content.addView(card);
    }

    private static boolean portOpen(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private void startJellyfin(final Button b) {
        if (starting) return;
        starting = true;
        setBusy(b, true);
        b.setText(tr("Starting…"));
        try {
            MediaServiceManager.getInstance(act.getApplicationContext()).startService("jellyfin");
        } catch (Throwable t) {
            starting = false;
            setBusy(b, false);
            b.setText(tr("Start Jellyfin"));
            toast(firstLine(t));
            return;
        }
        try {
            bg.execute(() -> {
                long end = System.currentTimeMillis() + 180000;
                boolean up = false;
                while (System.currentTimeMillis() < end && attached && !dead()) {
                    if (portOpen(8096) && JellyfinLibraryClient.isReachable()) {
                        up = true;
                        break;
                    }
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                }
                final boolean ok = up;
                ui(() -> {
                    starting = false;
                    if (!ok) toast(tr("Jellyfin has not started yet. Check the Services tab."));
                    refresh();
                });
            });
        } catch (RejectedExecutionException e) {
            starting = false;
        }
    }

    // ================================================================== browse

    private void loadBrowse() {
        final int req = ++requestCounter;
        final int m = mode;
        final int lv = level;
        final String sid = seriesId;
        final String seid = seasonId;
        showLoading(tr(m == MODE_MOVIES ? "Loading movies…" : lv == 0 ? "Loading series…" : lv == 1 ? "Loading seasons…" : "Loading episodes…"));
        this.<List<JellyfinLibraryClient.LibItem>>async(() -> {
            if (m == MODE_MOVIES) return JellyfinLibraryClient.movies(act);
            if (lv == 0) return JellyfinLibraryClient.series(act);
            if (lv == 1) return JellyfinLibraryClient.seasons(act, sid);
            return JellyfinLibraryClient.episodes(act, sid, seid);
        }, items -> {
            if (req != requestCounter || mode != m || level != lv) return;
            autoSignInTried = false;
            lastItems = items;
            if (lv == 0) {
                lastCount = trf(m == MODE_MOVIES ? "{0} movies" : "{0} series", items.size());
            }
            updateHeader();
            renderBrowse();
        }, t -> {
            if (req == requestCounter && mode == m && level == lv) showError(t);
        });
    }

    private void renderBrowse() {
        content.removeAllViews();
        if (mode == MODE_SERIES && level > 0) {
            LinearLayout crumbs = UiKit.row(act);
            crumbs.setPadding(0, 0, 0, dp(10));
            TextView back = UiKit.chip(act, tr("Back"), false);
            back.setOnClickListener(v -> onBack());
            crumbs.addView(back, UiKit.chipLp(act));
            String path = level == 2 && seasonName != null ? seriesName + " › " + seasonName : seriesName;
            TextView where = UiKit.singleLine(UiKit.subtitle(act, path == null ? "" : path));
            crumbs.addView(where, new LinearLayout.LayoutParams(0, -2, 1.0f));
            content.addView(crumbs, new LinearLayout.LayoutParams(-1, -2));
        }
        if (level == 0) {
            final EditText filter = UiKit.input(act, tr(mode == MODE_MOVIES ? "Search movies" : "Search series"));
            filter.setText(filterText);
            filter.setSelection(filter.getText().length());
            filter.setImeOptions(EditorInfo.IME_ACTION_DONE);
            content.addView(filter, new LinearLayout.LayoutParams(-1, -2));
            filter.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    filterText = e.toString();
                    listLimit = LIST_CHUNK;
                    fillRows();
                }
            });
        }
        countView = UiKit.caption(act, "");
        countView.setPadding(dp(2), dp(10), dp(2), dp(8));
        content.addView(countView);
        rowsBox = UiKit.column(act);
        content.addView(rowsBox, new LinearLayout.LayoutParams(-1, -2));
        fillRows();
    }

    private void fillRows() {
        if (rowsBox == null || countView == null || lastItems == null) return;
        rowsBox.removeAllViews();
        List<JellyfinLibraryClient.LibItem> list = new ArrayList<>();
        String f = level == 0 ? filterText.trim().toLowerCase(Locale.ROOT) : "";
        for (JellyfinLibraryClient.LibItem it : lastItems) {
            if (!f.isEmpty() && (it.name == null || !it.name.toLowerCase(Locale.ROOT).contains(f))) continue;
            list.add(it);
        }
        int total = lastItems.size();
        String noun = mode == MODE_MOVIES ? "movies" : level == 0 ? "series" : level == 1 ? "seasons" : "episodes";
        countView.setText(list.size() == total ? trf("{0} " + noun, total) : trf("{0} of {1} " + noun, list.size(), total));
        if (list.isEmpty()) {
            rowsBox.addView(total == 0
                    ? UiKit.empty(act, tr("Nothing here yet"), tr("Items appear once Jellyfin has scanned them into its library."))
                    : UiKit.empty(act, tr("No matches"), tr("Try another search.")));
            return;
        }
        LinearLayout card = listCard();
        int n = Math.min(list.size(), listLimit);
        boolean posters = !(mode == MODE_SERIES && level == 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) card.addView(separator(posters ? 64 : 12));
            card.addView(browseRow(list.get(i), posters));
        }
        rowsBox.addView(card);
        if (list.size() > n) {
            Button more = UiKit.outline(act, trf("Show {0} more", Math.min(list.size() - n, LIST_CHUNK)), v -> {
                listLimit += LIST_CHUNK;
                fillRows();
            });
            rowsBox.addView(more, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View browseRow(final JellyfinLibraryClient.LibItem it, boolean withPoster) {
        String meta;
        Runnable open = null;
        String title;
        if ("Series".equals(it.type)) {
            title = it.name;
            meta = joinDot(it.childCount > 0 ? trf("{0} seasons", it.childCount) : "", it.year > 0 ? String.valueOf(it.year) : "");
            open = () -> {
                seriesId = it.id;
                seriesName = it.name;
                level = 1;
                refresh();
                scroll.scrollTo(0, 0);
            };
        } else if ("Season".equals(it.type)) {
            title = it.name;
            meta = it.childCount > 0 ? trf("{0} episodes", it.childCount) : tr("Season");
            open = () -> {
                seasonId = it.id;
                seasonName = it.name;
                level = 2;
                refresh();
                scroll.scrollTo(0, 0);
            };
        } else if ("Episode".equals(it.type)) {
            title = (it.episodeNumber >= 0 ? trf("Episode {0}", it.episodeNumber) + " · " : "") + it.name;
            meta = tr(it.played ? "Watched" : "Not watched");
        } else {
            title = it.displayTitle();
            meta = tr(it.played ? "Watched" : "Not watched");
        }
        return itemRow(it, title, meta, withPoster ? it.id : null, withPoster ? it.imageTag : null, open,
                it.played && !"Series".equals(it.type) && !"Season".equals(it.type));
    }

    // ================================================================== recently watched

    private void loadRecent() {
        final int req = ++requestCounter;
        showLoading(tr("Loading watch history…"));
        this.<List<JellyfinLibraryClient.LibItem>>async(() -> JellyfinLibraryClient.recentlyWatched(act, 100), items -> {
            if (req != requestCounter || mode != MODE_RECENT) return;
            autoSignInTried = false;
            updateHeader();
            content.removeAllViews();
            if (items.isEmpty()) {
                content.addView(UiKit.empty(act, tr("Nothing watched yet"), tr("Movies and episodes appear here after someone watches them.")));
                return;
            }
            TextView c = UiKit.caption(act, trf("{0} watched items · latest first", items.size()));
            c.setPadding(dp(2), 0, 0, dp(8));
            content.addView(c);
            LinearLayout card = listCard();
            for (JellyfinLibraryClient.LibItem it : items) {
                if (card.getChildCount() > 0) card.addView(separator(64));
                boolean ep = "Episode".equals(it.type);
                String by = it.playedBy != null && !it.playedBy.isEmpty() ? it.playedBy : "";
                String meta = joinDot(trf("Watched {0}", relTime(it.lastPlayedMs)), by);
                card.addView(itemRow(it, it.displayTitle(), meta,
                        ep && it.seriesId != null ? it.seriesId : it.id,
                        ep && it.seriesId != null ? it.seriesImageTag : it.imageTag, null, false));
            }
            content.addView(card);
        }, t -> {
            if (req == requestCounter && mode == MODE_RECENT) showError(t);
        });
    }

    // ================================================================== rows, deletion

    private View itemRow(final JellyfinLibraryClient.LibItem item, String title, String meta, String imageItemId,
                         String imageTag, final Runnable open, boolean watchedBadge) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(UiKit.rowRipple(act));
        if (imageItemId != null) row.addView(poster(imageItemId, imageTag, 40), posterLp(40));
        LinearLayout tx = UiKit.column(act);
        tx.addView(UiKit.singleLine(UiKit.text(act, title, 14f, AppTheme.text(), UiKit.MEDIUM)));
        TextView m = UiKit.singleLine(UiKit.caption(act, meta));
        m.setPadding(0, dp(2), 0, 0);
        tx.addView(m);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        if (imageItemId != null) tlp.setMarginStart(dp(12));
        tlp.setMarginEnd(dp(8));
        row.addView(tx, tlp);
        if (watchedBadge) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.setMarginEnd(dp(8));
            row.addView(UiKit.badge(act, tr("Watched"), AppTheme.success()), blp);
        }
        final Button del = UiKit.danger(act, tr("Delete"), null);
        del.setOnClickListener(v -> confirmDelete(item, del));
        row.addView(del, new LinearLayout.LayoutParams(-2, dp(36)));
        if (open != null) {
            row.setClickable(true);
            row.setOnClickListener(v -> open.run());
        }
        return row;
    }

    private void confirmDelete(final JellyfinLibraryClient.LibItem item, final Button trigger) {
        if (dead() || !trigger.isEnabled()) return;
        String what;
        if ("Series".equals(item.type)) what = "The whole series and all of its episode files are deleted from disk.";
        else if ("Season".equals(item.type)) what = "This season and all of its episode files are deleted from disk.";
        else what = "The file is deleted from disk.";
        LinearLayout body = dialogBody(tr("Delete from Jellyfin?"), item.displayTitle());
        TextView m = UiKit.body(act, tr(what) + "\n\n" + tr("This cannot be undone. Radarr or Sonarr may download it again unless it is unmonitored there."));
        m.setPadding(0, dp(10), 0, dp(4));
        body.addView(m);
        final AlertDialog d = dialog(body);
        Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
        Button ok = UiKit.danger(act, tr("Delete"), v -> {
            d.dismiss();
            setBusy(trigger, true);
            this.<Boolean>async(() -> {
                JellyfinLibraryClient.deleteItemOrThrow(act, item.id);
                return Boolean.TRUE;
            }, res -> {
                toast(tr("Deleted"));
                if ("Series".equals(item.type)) level = 0;
                if ("Season".equals(item.type)) level = 1;
                refresh();
            }, t -> {
                setBusy(trigger, false);
                toast(firstLine(t));
            });
        });
        body.addView(UiKit.buttonRow(act, cancel, ok));
        show(d);
    }

    // ================================================================== auto-delete settings

    private void renderAutoDelete() {
        requestCounter++;
        content.removeAllViews();

        content.addView(autoSection(tr("Movies"), tr("Delete a movie this many hours after it was last watched."),
                LibraryPrefs.isMoviesEnabled(act),
                on -> LibraryPrefs.setMoviesEnabled(act, on),
                h -> LibraryPrefs.setMoviesHours(act, h),
                () -> LibraryPrefs.getMoviesHours(act)));
        content.addView(autoSection(tr("Episodes"), tr("Delete an episode this many hours after it was last watched."),
                LibraryPrefs.isSeriesEnabled(act),
                on -> LibraryPrefs.setSeriesEnabled(act, on),
                h -> LibraryPrefs.setSeriesHours(act, h),
                () -> LibraryPrefs.getSeriesHours(act)));

        LinearLayout run = UiKit.card(act);
        run.addView(UiKit.overline(act, tr("Last run")));
        final TextView status = UiKit.body(act, "");
        long last = LibraryPrefs.getLastRun(act);
        String summary = LibraryPrefs.getLastSummary(act);
        status.setText(last > 0
                ? joinLines(new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(last)), summary)
                : tr("Checks every 30 minutes while Jellyfin is running. It has not run yet."));
        run.addView(status);
        final Button preview = UiKit.outline(act, tr("Preview"), null);
        final Button runNow = UiKit.danger(act, tr("Run now"), null);
        preview.setOnClickListener(v -> runAuto(true, status, preview, runNow));
        runNow.setOnClickListener(v -> {
            LinearLayout body = dialogBody(tr("Delete watched items now?"), null);
            TextView m = UiKit.body(act, tr("Every watched movie or episode past its time limit is deleted from disk now. Use Preview to see the list first."));
            m.setPadding(0, dp(8), 0, dp(4));
            body.addView(m);
            final AlertDialog d = dialog(body);
            Button cancel = UiKit.outline(act, tr("Cancel"), x -> d.dismiss());
            Button ok = UiKit.danger(act, tr("Delete now"), x -> {
                d.dismiss();
                runAuto(false, status, preview, runNow);
            });
            body.addView(UiKit.buttonRow(act, cancel, ok));
            show(d);
        });
        run.addView(UiKit.buttonRow(act, preview, runNow));
        content.addView(run);

        TextView note = UiKit.caption(act, tr("Time is counted from the most recent watch by any Jellyfin user, so an item another user has not seen yet can still be deleted. Radarr or Sonarr may download it again unless it is unmonitored there."));
        note.setPadding(dp(2), dp(4), dp(2), 0);
        content.addView(note);
    }

    private static String joinLines(String a, String b) {
        if (b == null || b.trim().isEmpty()) return a;
        return a + "\n" + b.trim();
    }

    private View autoSection(String name, String help, boolean on,
                             final BoolSetter setOn, final IntSetter setHours, final IntGetter getHours) {
        LinearLayout card = UiKit.card(act);
        LinearLayout top = UiKit.row(act);
        LinearLayout tx = UiKit.column(act);
        tx.addView(UiKit.subtitle(act, name));
        TextView h = UiKit.caption(act, help);
        h.setPadding(0, dp(2), 0, 0);
        tx.addView(h);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginEnd(dp(12));
        top.addView(tx, tlp);
        Switch sw = new Switch(act);
        sw.setChecked(on);
        UiStyle.styleSwitch(act, sw);
        sw.setOnCheckedChangeListener((v, checked) -> {
            setOn.set(checked);
            toast(checked ? trf("Auto-delete on for {0}", name) : trf("Auto-delete off for {0}", name));
        });
        top.addView(sw);
        card.addView(top);

        LinearLayout period = UiKit.row(act);
        period.setPadding(0, dp(12), 0, 0);
        period.addView(UiKit.text(act, tr("Delete after (hours)"), 13.5f, AppTheme.text(), null),
                new LinearLayout.LayoutParams(0, -2, 1.0f));
        final EditText input = UiKit.input(act, String.valueOf(LibraryPrefs.DEFAULT_HOURS));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(getHours.get()));
        input.setGravity(Gravity.CENTER);
        period.addView(input, new LinearLayout.LayoutParams(dp(88), -2));
        final Button save = UiKit.tonal(act, tr("Save"), null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.setMarginStart(dp(8));
        period.addView(save, slp);
        final Runnable dirty = UiStyle.bindDirty(save, input, () -> String.valueOf(getHours.get()));
        save.setOnClickListener(v -> {
            int val = LibraryPrefs.DEFAULT_HOURS;
            try { val = Integer.parseInt(input.getText().toString().trim()); } catch (Throwable ignored) {}
            setHours.set(val);
            input.setText(String.valueOf(getHours.get()));
            dirty.run();
            toast(trf("Saved: {0} h", getHours.get()));
        });
        card.addView(period);
        return card;
    }

    private void runAuto(final boolean dryRun, final TextView status, final Button a, final Button b) {
        if (!a.isEnabled() || !b.isEnabled()) return;
        setBusy(a, true);
        setBusy(b, true);
        status.setText(tr(dryRun ? "Checking…" : "Deleting…"));
        this.<String>async(() -> LibraryAutoDelete.run(act, dryRun), msg -> {
            setBusy(a, false);
            setBusy(b, false);
            status.setText(msg == null || msg.isEmpty() ? tr("Done") : msg);
        }, t -> {
            setBusy(a, false);
            setBusy(b, false);
            status.setText(trf("Failed: {0}", firstLine(t)));
        });
    }

    // ================================================================== dialogs & building blocks

    private LinearLayout dialogBody(String title, String subtitle) {
        LinearLayout b = UiKit.column(act);
        b.setPadding(dp(20), dp(18), dp(20), dp(16));
        b.addView(UiKit.title(act, title));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = UiKit.caption(act, subtitle);
            st.setPadding(0, dp(2), 0, 0);
            st.setMaxLines(2);
            st.setEllipsize(TextUtils.TruncateAt.END);
            b.addView(st);
        }
        return b;
    }

    private AlertDialog dialog(View body) {
        ScrollView sv = new ScrollView(act);
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        AlertDialog d = new AlertDialog.Builder(act).setView(sv).create();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new InsetDrawable(
                    UiKit.shape(act, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD), dp(16)));
        }
        return d;
    }

    private void show(AlertDialog d) {
        if (dead()) return;
        try {
            d.show();
        } catch (Throwable ignored) {
            // window token gone (activity finishing)
        }
    }

    private LinearLayout listCard() {
        LinearLayout card = UiKit.card(act);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        return card;
    }

    private View separator(int insetStartDp) {
        View v = new View(act);
        v.setBackgroundColor(AppTheme.outline());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        lp.setMarginStart(dp(insetStartDp));
        lp.setMarginEnd(dp(12));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout.LayoutParams posterLp(int wDp) {
        return new LinearLayout.LayoutParams(dp(wDp), dp(wDp * 1.5f));
    }

    private static synchronized LruCache<String, Bitmap> cache() {
        if (posterCache == null) {
            long max = Math.min(Runtime.getRuntime().maxMemory() / 16, 16L * 1024 * 1024);
            posterCache = new LruCache<String, Bitmap>((int) Math.max(2L * 1024 * 1024, max)) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };
        }
        return posterCache;
    }

    /** 2:3 Jellyfin primary image, rounded 8dp, loaded off the UI thread into a bounded memory cache. */
    private ImageView poster(final String itemId, final String tag, int wDp) {
        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(UiKit.shape(act, AppTheme.inner(), 0, 8));
        iv.setClipToOutline(true);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (itemId == null || tag == null || tag.isEmpty()) return iv;
        final int px = dp(wDp);
        final String key = itemId + ":" + tag + "@" + px;
        iv.setTag(key);
        Bitmap hit = cache().get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return iv;
        }
        List<ImageView> waiting = imgWaiting.get(key);
        if (waiting != null) {
            waiting.add(iv);
            return iv;
        }
        waiting = new ArrayList<>();
        waiting.add(iv);
        imgWaiting.put(key, waiting);
        try {
            imgPool.execute(() -> {
                Bitmap b = cache().get(key);
                if (b == null) {
                    b = JellyfinLibraryClient.loadPrimaryImage(act, itemId, tag, px);
                    if (b != null) cache().put(key, b);
                }
                final Bitmap bmp = b;
                ui(() -> {
                    List<ImageView> ws = imgWaiting.remove(key);
                    if (bmp == null || ws == null) return;
                    for (ImageView x : ws) {
                        if (key.equals(x.getTag())) x.setImageBitmap(bmp);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            imgWaiting.remove(key);
        }
        return iv;
    }

    // ================================================================== formatting

    private static String joinDot(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String relTime(long ms) {
        if (ms <= 0) return "";
        long diff = System.currentTimeMillis() - ms;
        if (diff < 0) diff = 0;
        long min = diff / 60000;
        if (min < 1) return tr("just now");
        if (min < 60) return trf("{0} min ago", min);
        long h = min / 60;
        if (h < 24) return trf("{0} h ago", h);
        long d = h / 24;
        if (d < 7) return trf("{0} d ago", d);
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(ms));
    }
}
