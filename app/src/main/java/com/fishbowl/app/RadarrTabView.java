package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
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

import com.fishbowl.app.services.MediaServiceManager;
import com.fishbowl.app.services.RadarrApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "Radarr" tab: a native client for Radarr's web UI, the movie twin of {@link SonarrTabView}.
 *
 * <ul>
 *   <li>Movies - search / filter the library, open a movie (monitor, search, interactive search,
 *       refresh / rescan, delete file, delete movie); "Add movie" looks a title up on TMDb.</li>
 *   <li>Calendar - cinema / digital / physical releases of monitored movies.</li>
 *   <li>Queue - live downloads (polled while visible) with remove / blocklist.</li>
 *   <li>History - grabbed / imported / failed events.</li>
 *   <li>Wanted - monitored missing and cutoff unmet movies, search one or all.</li>
 *   <li>System - status, health, disk space, root folders, profiles, indexers, clients, tasks.</li>
 * </ul>
 */
public final class RadarrTabView {

    private static final int S_MOVIES = 0;
    private static final int S_CALENDAR = 1;
    private static final int S_QUEUE = 2;
    private static final int S_HISTORY = 3;
    private static final int S_WANTED = 4;
    private static final int S_SYSTEM = 5;

    private static final String[] SECTION_NAMES = {"Movies", "Calendar", "Queue", "History", "Wanted", "System"};
    private static final String[] FILTER_NAMES = {"All", "Monitored", "Missing", "Downloaded"};
    private static final String[] AVAIL_LABELS = {"Announced", "In cinemas", "Released"};
    private static final String[] AVAIL_VALUES = {"announced", "inCinemas", "released"};
    private static final int PAGE_SIZE = 40;
    private static final int LIST_CHUNK = 60;
    private static final long QUEUE_POLL_MS = 5000;
    private static final String PREFS = "radarr_tab";

    private interface Job<T> { T run(RadarrApi api) throws Exception; }
    private interface Done<T> { void run(T value); }

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

    private int section = S_MOVIES;
    private int page = 1;
    private int detailMovieId = 0;
    private int requestCounter = 0;
    private int filterMode = 0;
    private int movieLimit = LIST_CHUNK;
    private boolean wantedCutoff = false;
    private String movieFilter = "";
    private JSONArray movieCache = null;
    private String version = null;
    private boolean versionLoading = false;
    private boolean attached = true;
    private boolean starting = false;
    private boolean monitorBusy = false;
    private boolean queueOfflineShown = false;
    private long lastOpenMs = 0;

    private LinearLayout movieRowsBox;
    private TextView movieCountView;

    private final Runnable queueTicker = new Runnable() {
        @Override public void run() {
            if (section == S_QUEUE && detailMovieId == 0 && attached && root.isShown() && !dead()) loadQueue(true);
        }
    };

    private final Runnable filterTicker = new Runnable() {
        @Override public void run() { fillMovieRows(); }
    };

    public RadarrTabView(Activity activity) {
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
            @Override public void onViewDetachedFromWindow(View v) {
                attached = false;
                stopPolling();
                main.removeCallbacks(filterTicker);
            }
        });
        updateHeader();
        renderNav();
    }

    public View getView() { return root; }

    /** Returns true when the back press was consumed. */
    public boolean onBack() {
        if (detailMovieId > 0) {
            detailMovieId = 0;
            refresh();
            return true;
        }
        if ((section == S_WANTED || section == S_HISTORY) && page > 1) {
            page--;
            refresh();
            return true;
        }
        if (section != S_MOVIES) {
            section = S_MOVIES;
            page = 1;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        stopPolling();
        main.removeCallbacks(filterTicker);
        movieRowsBox = null;
        movieCountView = null;
        renderNav();
        updateHeader();
        loadVersionOnce();
        if (detailMovieId > 0) {
            loadDetail();
            return;
        }
        switch (section) {
            case S_CALENDAR: loadCalendar(); break;
            case S_QUEUE: queueOfflineShown = false; loadQueue(false); break;
            case S_HISTORY: loadHistory(); break;
            case S_WANTED: loadWanted(); break;
            case S_SYSTEM: loadSystem(); break;
            default: loadMovies(); break;
        }
    }

    // ================================================================== chrome

    private void updateHeader() {
        headerSlot.removeAllViews();
        String sub;
        if (version != null && movieCache != null) sub = trf("Radarr v{0} · {1} movies", version, movieCache.length());
        else if (version != null) sub = trf("Radarr v{0}", version);
        else sub = tr("Movies, downloads and releases");
        Button refreshBtn = UiKit.outline(act, tr("Refresh"), v -> refresh());
        headerSlot.addView(UiKit.header(act, tr("Radarr"), sub, refreshBtn));
    }

    private void renderNav() {
        chipRow.removeAllViews();
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int idx = i;
            TextView chip = UiKit.chip(act, tr(SECTION_NAMES[i]), idx == section);
            chip.setOnClickListener(v -> {
                if (idx == section && detailMovieId == 0 && page == 1) {
                    scroll.smoothScrollTo(0, 0);
                    return;
                }
                section = idx;
                page = 1;
                detailMovieId = 0;
                refresh();
                scroll.scrollTo(0, 0);
            });
            chipRow.addView(chip, UiKit.chipLp(act));
        }
    }

    private void loadVersionOnce() {
        if (version != null || versionLoading) return;
        versionLoading = true;
        async(api -> api.status(), st -> {
            versionLoading = false;
            String v = st.optString("version", "");
            if (!v.isEmpty()) {
                version = v;
                updateHeader();
            }
        }, t -> versionLoading = false);
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
                    result = job.run(RadarrApi.get(act));
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
                    } else {
                        toast(errText(fail));
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // executor gone: the activity is being torn down
        }
    }

    /** Runs a one-shot action with {@code trigger} disabled until it finishes, then toasts the outcome. */
    private void action(final View trigger, final Job<Void> job, final String okMsg, final Runnable after) {
        if (trigger != null && !trigger.isEnabled()) return;
        setBusy(trigger, true);
        async(job, v -> {
            setBusy(trigger, false);
            if (okMsg != null) toast(okMsg);
            if (after != null) after.run();
        }, t -> {
            setBusy(trigger, false);
            toast(errText(t));
        });
    }

    private static void setBusy(View v, boolean busy) {
        if (v == null) return;
        v.setEnabled(!busy);
        v.setAlpha(busy ? 0.5f : 1f);
    }

    /** Swallows a second tap that arrives while a dialog is opening. */
    private boolean debounce() {
        long now = SystemClock.uptimeMillis();
        if (now - lastOpenMs < 700) return true;
        lastOpenMs = now;
        return false;
    }

    private static String errText(Throwable t) {
        if (RadarrApi.isOffline(t)) return tr("Radarr is not running");
        if (t instanceof JSONException) return tr("Radarr sent an unexpected answer.");
        String m = t == null ? null : t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t == null ? "Unknown error" : t.getClass().getSimpleName();
        return tr(m);
    }

    private void toast(String s) {
        if (dead() || s == null) return;
        Toast.makeText(act, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private static String tr(String s) { return I18n.tr(s); }

    /** Fills {0}, {1}... then translates the result (the Arabic dictionary holds the same template). */
    private static String trf(String template, Object... args) {
        String s = template;
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return I18n.tr(s);
    }

    private int dp(float v) { return UiKit.dp(act, v); }

    // ================================================================== states

    private void showLoading(String msg) {
        content.removeAllViews();
        content.addView(UiKit.loading(act, msg));
    }

    private void showError(Throwable t) {
        content.removeAllViews();
        content.addView(errorView(t, true));
    }

    private View errorView(Throwable t, boolean withRetry) {
        if (RadarrApi.isOffline(t)) {
            View v = UiKit.error(act, tr("Radarr is not running"),
                    tr("Start Radarr here or from the Services tab. This screen loads as soon as it is up."), null, null);
            if (v instanceof LinearLayout) {
                final Button start = UiKit.primary(act, tr(starting ? "Starting…" : "Start Radarr"), null);
                start.setOnClickListener(x -> startRadarr(start));
                if (starting) setBusy(start, true);
                if (withRetry) {
                    ((LinearLayout) v).addView(UiKit.buttonRow(act, start, UiKit.outline(act, tr("Retry"), x -> refresh())));
                } else {
                    ((LinearLayout) v).addView(UiKit.buttonRow(act, start));
                }
            }
            return v;
        }
        if (!withRetry) return UiKit.error(act, tr("Radarr reported an error"), errText(t), null, null);
        return UiKit.error(act, tr("Could not load Radarr"), errText(t), tr("Retry"), x -> refresh());
    }

    private void startRadarr(final Button b) {
        if (starting) return;
        starting = true;
        setBusy(b, true);
        b.setText(tr("Starting…"));
        try {
            MediaServiceManager.getInstance(act.getApplicationContext()).startService("radarr");
        } catch (Throwable t) {
            starting = false;
            setBusy(b, false);
            b.setText(tr("Start Radarr"));
            toast(errText(t));
            return;
        }
        try {
            bg.execute(() -> {
                long end = System.currentTimeMillis() + 120000;
                boolean up = false;
                while (System.currentTimeMillis() < end && attached && !dead()) {
                    if (RadarrApi.isPortOpen()) {
                        up = true;
                        break;
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
                }
                if (up) {
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                }
                final boolean ok = up;
                ui(() -> {
                    starting = false;
                    if (!ok) toast(tr("Radarr has not started yet. Check the Services tab."));
                    refresh();
                });
            });
        } catch (RejectedExecutionException e) {
            starting = false;
        }
    }

    // ================================================================== polling

    private void schedulePoll() {
        main.removeCallbacks(queueTicker);
        if (section == S_QUEUE && detailMovieId == 0 && attached && root.isShown()) {
            main.postDelayed(queueTicker, QUEUE_POLL_MS);
        }
    }

    private void stopPolling() {
        main.removeCallbacks(queueTicker);
    }

    // ================================================================== MOVIES list

    private void loadMovies() {
        final int req = ++requestCounter;
        if (movieCache != null) renderMovies();
        else showLoading(tr("Loading movies…"));
        async(api -> api.movies(), arr -> {
            if (req != requestCounter || detailMovieId > 0 || section != S_MOVIES) return;
            boolean fresh = movieCache == null || movieRowsBox == null;
            movieCache = arr;
            updateHeader();
            if (fresh) renderMovies();
            else fillMovieRows();
        }, t -> {
            if (req != requestCounter || section != S_MOVIES || detailMovieId > 0) return;
            if (movieCache == null || RadarrApi.isOffline(t)) {
                movieCache = null;
                showError(t);
            } else {
                toast(errText(t));
            }
        });
    }

    private void renderMovies() {
        content.removeAllViews();
        LinearLayout bar = UiKit.row(act);
        final EditText filter = UiKit.input(act, tr("Search your movies"));
        filter.setText(movieFilter);
        filter.setSelection(filter.getText().length());
        filter.setImeOptions(EditorInfo.IME_ACTION_DONE);
        bar.addView(filter, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button add = UiKit.primary(act, tr("Add movie"), v -> {
            if (!debounce()) showAddDialog();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.setMarginStart(dp(8));
        bar.addView(add, alp);
        content.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout chips = UiKit.row(act);
        chips.setPadding(0, dp(12), 0, dp(4));
        content.addView(UiKit.chipScroller(act, chips), new LinearLayout.LayoutParams(-1, -2));
        drawFilterChips(chips);

        movieCountView = UiKit.caption(act, "");
        movieCountView.setPadding(dp(2), dp(6), dp(2), dp(8));
        content.addView(movieCountView);
        movieRowsBox = UiKit.column(act);
        content.addView(movieRowsBox, new LinearLayout.LayoutParams(-1, -2));
        fillMovieRows();

        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                movieFilter = e.toString();
                movieLimit = LIST_CHUNK;
                main.removeCallbacks(filterTicker);
                main.postDelayed(filterTicker, 180);
            }
        });
    }

    private void drawFilterChips(final LinearLayout chips) {
        chips.removeAllViews();
        for (int i = 0; i < FILTER_NAMES.length; i++) {
            final int idx = i;
            TextView c = UiKit.chip(act, tr(FILTER_NAMES[i]), idx == filterMode);
            c.setOnClickListener(v -> {
                if (filterMode == idx) return;
                filterMode = idx;
                movieLimit = LIST_CHUNK;
                drawFilterChips(chips);
                fillMovieRows();
            });
            chips.addView(c, UiKit.chipLp(act));
        }
    }

    private List<JSONObject> filteredMovies() {
        List<JSONObject> list = new ArrayList<>();
        if (movieCache == null) return list;
        String f = movieFilter.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < movieCache.length(); i++) {
            JSONObject m = movieCache.optJSONObject(i);
            if (m == null) continue;
            if (!f.isEmpty()) {
                String hay = (m.optString("title", "") + " " + m.optString("originalTitle", "") + " " + m.optInt("year"))
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(f)) continue;
            }
            boolean mon = m.optBoolean("monitored");
            boolean has = m.optBoolean("hasFile");
            if (filterMode == 1 && !mon) continue;
            if (filterMode == 2 && (has || !mon)) continue;
            if (filterMode == 3 && !has) continue;
            list.add(m);
        }
        Collections.sort(list, (a, b) -> sortKey(a).compareToIgnoreCase(sortKey(b)));
        return list;
    }

    private static String sortKey(JSONObject m) {
        String s = RadarrApi.str(m, "sortTitle");
        return s.isEmpty() ? m.optString("title", "") : s;
    }

    private void fillMovieRows() {
        if (movieRowsBox == null || movieCountView == null || movieCache == null) return;
        movieRowsBox.removeAllViews();
        List<JSONObject> list = filteredMovies();
        int total = movieCache.length();
        movieCountView.setText(list.size() == total ? trf("{0} movies", total) : trf("{0} of {1} movies", list.size(), total));
        if (list.isEmpty()) {
            movieRowsBox.addView(total == 0
                    ? UiKit.empty(act, tr("No movies yet"), tr("Tap Add movie to find a movie on TMDb and add it."))
                    : UiKit.empty(act, tr("No matches"), tr("Try another search or filter.")));
            return;
        }
        LinearLayout card = listCard();
        int n = Math.min(list.size(), movieLimit);
        for (int i = 0; i < n; i++) {
            if (i > 0) card.addView(separator(64));
            card.addView(movieRow(list.get(i)));
        }
        movieRowsBox.addView(card);
        if (list.size() > n) {
            int more = Math.min(list.size() - n, LIST_CHUNK);
            Button b = UiKit.outline(act, trf("Show {0} more", more), v -> {
                movieLimit += LIST_CHUNK;
                fillMovieRows();
            });
            movieRowsBox.addView(b, fullLp(0));
        }
    }

    private View movieRow(final JSONObject m) {
        LinearLayout row = listRow();
        row.addView(poster(m, 40), posterLp(40));
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(titleYear(m)));
        tx.addView(rowMeta(movieMeta(m)));
        row.addView(tx, textLp());
        View badge = movieBadge(m);
        if (badge != null) row.addView(badge);
        final int id = m.optInt("id");
        row.setOnClickListener(v -> openMovie(id));
        return row;
    }

    private String movieMeta(JSONObject m) {
        String runtime = fmtRuntime(m.optInt("runtime"));
        if (m.optBoolean("hasFile")) {
            JSONObject f = m.optJSONObject("movieFile");
            long size = f != null ? f.optLong("size") : m.optLong("sizeOnDisk");
            return joinDot(runtime, qualityName(f), size > 0 ? fmtSize(size) : "");
        }
        return joinDot(runtime, RadarrApi.str(m, "studio"), tr(statusLabel(RadarrApi.str(m, "status"))));
    }

    private View movieBadge(JSONObject m) {
        boolean mon = m.optBoolean("monitored");
        if (m.optBoolean("hasFile")) return UiKit.badge(act, tr("Downloaded"), AppTheme.success());
        if (!mon) return UiKit.badge(act, tr("Unmonitored"), AppTheme.textMuted());
        if (m.optBoolean("isAvailable", true)) return UiKit.badge(act, tr("Missing"), AppTheme.warning());
        return UiKit.badge(act, tr("Not released"), AppTheme.textMuted());
    }

    private void openMovie(int id) {
        if (id <= 0) return;
        detailMovieId = id;
        refresh();
        scroll.scrollTo(0, 0);
    }

    // ================================================================== MOVIE detail

    private void loadDetail() {
        final int req = ++requestCounter;
        final int id = detailMovieId;
        showLoading(tr("Loading movie…"));
        async(api -> {
            JSONObject pack = new JSONObject();
            pack.put("movie", api.movie(id));
            try { pack.put("profiles", api.qualityProfiles()); } catch (Throwable ignored) {}
            return pack;
        }, pack -> {
            if (req != requestCounter || detailMovieId != id) return;
            renderDetail(pack.optJSONObject("movie"), pack.optJSONArray("profiles"));
        }, t -> {
            if (req != requestCounter || detailMovieId != id) return;
            if (RadarrApi.statusOf(t) == 404) {
                detailMovieId = 0;
                movieCache = null;
                toast(tr("This movie is no longer in Radarr."));
                refresh();
                return;
            }
            showError(t);
        });
    }

    private void renderDetail(final JSONObject movie, JSONArray profiles) {
        content.removeAllViews();
        if (movie == null || movie.optInt("id") <= 0) {
            content.addView(UiKit.empty(act, tr("Movie not found"), tr("It may have been removed from Radarr.")));
            return;
        }
        final int id = movie.optInt("id");
        final String title = titleYear(movie);

        TextView back = UiKit.chip(act, tr("Back"), false);
        back.setOnClickListener(v -> onBack());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.bottomMargin = dp(12);
        content.addView(back, blp);

        // ---- hero
        LinearLayout hero = UiKit.card(act);
        LinearLayout top = UiKit.row(act);
        top.setGravity(Gravity.TOP);
        top.addView(poster(movie, 96), posterLp(96));
        LinearLayout info = UiKit.column(act);
        info.addView(UiKit.title(act, title));
        String rating = "";
        JSONObject ratings = movie.optJSONObject("ratings");
        JSONObject tmdb = ratings == null ? null : ratings.optJSONObject("tmdb");
        if (tmdb != null && tmdb.optDouble("value", 0) > 0) rating = String.format(Locale.US, "TMDb %.1f", tmdb.optDouble("value"));
        TextView meta = UiKit.caption(act, joinDot(fmtRuntime(movie.optInt("runtime")), RadarrApi.str(movie, "certification"),
                RadarrApi.str(movie, "studio"), rating));
        meta.setPadding(0, dp(4), 0, 0);
        info.addView(meta);
        LinearLayout badges = UiKit.row(act);
        badges.setPadding(0, dp(10), 0, 0);
        View b1 = movieBadge(movie);
        if (b1 != null) badges.addView(b1);
        if (movie.optBoolean("monitored")) {
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2);
            lp2.setMarginStart(dp(6));
            badges.addView(UiKit.badge(act, tr("Monitored"), AppTheme.accent()), lp2);
        }
        info.addView(badges);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        ilp.setMarginStart(dp(14));
        top.addView(info, ilp);
        hero.addView(top);

        String ov = RadarrApi.str(movie, "overview");
        if (!ov.isEmpty()) {
            final TextView o = UiKit.body(act, ov);
            o.setMaxLines(4);
            o.setEllipsize(TextUtils.TruncateAt.END);
            o.setPadding(0, dp(12), 0, 0);
            o.setOnClickListener(v -> {
                boolean collapsed = o.getMaxLines() == 4;
                o.setMaxLines(collapsed ? 200 : 4);
            });
            hero.addView(o);
        }

        final Button search = UiKit.primary(act, tr("Search"), null);
        search.setOnClickListener(v -> action(search, api -> {
            api.searchMovie(id);
            return null;
        }, tr("Search started. Radarr grabs the best release it finds."), null));
        Button interactive = UiKit.outline(act, tr("Interactive search"), v -> {
            if (!debounce()) showReleases(id, title);
        });
        hero.addView(UiKit.buttonRow(act, search, interactive));

        final Button refreshB = UiKit.outline(act, tr("Refresh"), null);
        refreshB.setOnClickListener(v -> action(refreshB, api -> {
            api.refreshMovie(id);
            return null;
        }, tr("Refreshing metadata…"), () -> movieCache = null));
        final Button rescan = UiKit.outline(act, tr("Rescan"), null);
        rescan.setOnClickListener(v -> action(rescan, api -> {
            api.rescanMovie(id);
            return null;
        }, tr("Rescanning the movie folder…"), () -> movieCache = null));
        Button del = UiKit.danger(act, tr("Delete"), v -> {
            if (!debounce()) confirmDeleteMovie(movie);
        });
        hero.addView(UiKit.buttonRow(act, refreshB, rescan, del));
        content.addView(hero);

        // ---- monitored
        LinearLayout monCard = UiKit.card(act);
        final Switch sw = switchRow(monCard, tr("Monitored"),
                tr("Radarr searches for monitored movies and upgrades them automatically."), movie.optBoolean("monitored"));
        sw.setOnCheckedChangeListener((b, checked) -> setMonitored(movie, sw, checked));
        content.addView(monCard);

        // ---- file
        final JSONObject file = movie.optJSONObject("movieFile");
        LinearLayout fc = UiKit.card(act);
        fc.addView(UiKit.overline(act, tr("File")));
        if (movie.optBoolean("hasFile") && file != null) {
            fc.addView(UiKit.kv(act, tr("Quality"), orDash(qualityName(file))));
            fc.addView(UiKit.kv(act, tr("Size"), fmtSize(file.optLong("size"))));
            JSONObject mi = file.optJSONObject("mediaInfo");
            if (mi != null) {
                String video = joinDot(RadarrApi.str(mi, "videoCodec"), RadarrApi.str(mi, "resolution"),
                        RadarrApi.str(mi, "videoDynamicRangeType"));
                String audio = joinDot(RadarrApi.str(mi, "audioCodec"), RadarrApi.str(mi, "audioChannels"),
                        RadarrApi.str(mi, "audioLanguages"));
                if (!video.isEmpty()) fc.addView(UiKit.kv(act, tr("Video"), video));
                if (!audio.isEmpty()) fc.addView(UiKit.kv(act, tr("Audio"), audio));
            }
            String added = fmtDateTime(RadarrApi.str(file, "dateAdded"));
            if (!added.isEmpty()) fc.addView(UiKit.kv(act, tr("Added"), added));
            String rel = RadarrApi.str(file, "relativePath");
            if (!rel.isEmpty()) {
                TextView rp = UiKit.mono(act, rel);
                rp.setTextColor(AppTheme.textSecondary());
                rp.setPadding(0, dp(6), 0, 0);
                fc.addView(rp);
            }
            final int fileId = file.optInt("id");
            final Button delFile = UiKit.danger(act, tr("Delete file"), null);
            delFile.setOnClickListener(v -> confirm(tr("Delete file?"),
                    tr("The downloaded file is deleted from disk. The movie stays in Radarr and can be downloaded again."),
                    tr("Delete file"), true, () -> action(delFile, api -> {
                        api.deleteMovieFile(fileId);
                        return null;
                    }, tr("File deleted"), () -> {
                        movieCache = null;
                        refresh();
                    })));
            fc.addView(UiKit.buttonRow(act, delFile));
        } else {
            fc.addView(UiKit.body(act, tr(movie.optBoolean("isAvailable", true)
                    ? "No file yet. Use Search to look for a release."
                    : "No file yet. The movie is not available for download yet.")));
        }
        content.addView(fc);

        // ---- details
        String profName = "";
        int pid = movie.optInt("qualityProfileId");
        for (int i = 0; profiles != null && i < profiles.length(); i++) {
            JSONObject p = profiles.optJSONObject(i);
            if (p != null && p.optInt("id") == pid) {
                profName = p.optString("name");
                break;
            }
        }
        LinearLayout dc = UiKit.card(act);
        dc.addView(UiKit.overline(act, tr("Details")));
        dc.addView(UiKit.kv(act, tr("Status"), tr(statusLabel(RadarrApi.str(movie, "status")))));
        dc.addView(UiKit.kv(act, tr("Quality profile"), profName.isEmpty() ? (pid > 0 ? "#" + pid : "-") : profName));
        dc.addView(UiKit.kv(act, tr("Minimum availability"), tr(availabilityLabel(RadarrApi.str(movie, "minimumAvailability")))));
        addDateKv(dc, tr("In cinemas"), RadarrApi.str(movie, "inCinemas"));
        addDateKv(dc, tr("Digital release"), RadarrApi.str(movie, "digitalRelease"));
        addDateKv(dc, tr("Physical release"), RadarrApi.str(movie, "physicalRelease"));
        String addedM = fmtDateTime(RadarrApi.str(movie, "added"));
        if (!addedM.isEmpty()) dc.addView(UiKit.kv(act, tr("Added to Radarr"), addedM));
        String path = RadarrApi.str(movie, "path");
        if (!path.isEmpty()) {
            TextView pl = UiKit.caption(act, tr("Path"));
            pl.setPadding(0, dp(8), 0, dp(2));
            dc.addView(pl);
            dc.addView(UiKit.mono(act, path));
        }
        content.addView(dc);
    }

    private void addDateKv(LinearLayout card, String label, String iso) {
        String d = fmtDay(iso);
        if (!d.isEmpty()) card.addView(UiKit.kv(act, label, d));
    }

    private void setMonitored(final JSONObject movie, final Switch sw, final boolean checked) {
        if (monitorBusy) return;
        final JSONObject copy = copyWith(movie, "monitored", checked);
        if (copy == null) return;
        monitorBusy = true;
        sw.setEnabled(false);
        async(api -> api.putMovie(copy), updated -> {
            monitorBusy = false;
            sw.setEnabled(true);
            try { movie.put("monitored", checked); } catch (Exception ignored) {}
            movieCache = null;
            toast(tr(checked ? "Monitoring on" : "Monitoring off"));
        }, t -> {
            monitorBusy = false;
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(!checked);
            sw.setOnCheckedChangeListener((b, c2) -> setMonitored(movie, sw, c2));
            sw.setEnabled(true);
            toast(errText(t));
        });
    }

    private void confirmDeleteMovie(final JSONObject movie) {
        final LinearLayout body = dialogBody(tr("Delete movie?"), titleYear(movie));
        TextView m = UiKit.body(act, tr("The movie is removed from Radarr."));
        m.setPadding(0, dp(10), 0, dp(4));
        body.addView(m);
        final Switch files = switchRow(body, tr("Also delete the files"), tr("Removes the movie folder from disk."), false);
        final Switch excl = switchRow(body, tr("Don't add it again"),
                tr("Adds an import list exclusion so lists cannot re-add it."), false);
        final AlertDialog d = dialog(body);
        final Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
        final Button del = UiKit.danger(act, tr("Delete"), null);
        del.setOnClickListener(v -> {
            final int id = movie.optInt("id");
            final boolean df = files.isChecked();
            final boolean ex = excl.isChecked();
            action(del, api -> {
                api.deleteMovie(id, df, ex);
                return null;
            }, tr("Movie deleted"), () -> {
                d.dismiss();
                movieCache = null;
                detailMovieId = 0;
                refresh();
            });
        });
        body.addView(UiKit.buttonRow(act, cancel, del));
        show(d);
    }

    // ================================================================== interactive search

    private void showReleases(final int movieId, String title) {
        final LinearLayout body = dialogBody(tr("Interactive search"), title);
        final LinearLayout list = UiKit.column(act);
        list.addView(UiKit.loading(act, tr("Searching your indexers… this can take a minute.")));
        body.addView(scrollBox(list));
        final AlertDialog d = listDialog(body);
        body.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Close"), v -> d.dismiss())));
        final boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        show(d);
        async(api -> api.releasesForMovie(movieId), arr -> {
            if (!open[0]) return;
            list.removeAllViews();
            List<JSONObject> ok = new ArrayList<>();
            List<JSONObject> rejected = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                if (isRejected(r)) rejected.add(r); else ok.add(r);
            }
            ok.addAll(rejected); // keep Radarr's ranking, accepted releases first
            if (ok.isEmpty()) {
                list.addView(UiKit.empty(act, tr("No releases found"), tr("None of your indexers returned a result for this movie.")));
                return;
            }
            TextView c = UiKit.caption(act, trf("{0} releases · {1} accepted", ok.size(), ok.size() - rejected.size()));
            c.setPadding(dp(2), dp(4), 0, dp(4));
            list.addView(c);
            int n = 0;
            for (JSONObject r : ok) {
                if (++n > 80) break;
                list.addView(releaseRow(r));
            }
        }, t -> {
            if (!open[0]) return;
            list.removeAllViews();
            list.addView(errorView(t, false));
        });
    }

    private static boolean isRejected(JSONObject r) {
        if (r.optBoolean("rejected", false)) return true;
        JSONArray rej = r.optJSONArray("rejections");
        return rej != null && rej.length() > 0;
    }

    private View releaseRow(final JSONObject r) {
        LinearLayout box = UiKit.column(act);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(UiKit.shape(act, AppTheme.inner(), 0, UiKit.R_INNER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        box.setLayoutParams(lp);
        TextView t = UiKit.text(act, r.optString("title"), 13f, AppTheme.text(), UiKit.MEDIUM);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        boolean torrent = "torrent".equalsIgnoreCase(RadarrApi.str(r, "protocol"));
        TextView meta = UiKit.caption(act, joinDot(qualityName(r), fmtSize(r.optLong("size")),
                RadarrApi.str(r, "indexer"), fmtAge(r.optDouble("ageMinutes", 0))));
        meta.setPadding(0, dp(3), 0, 0);
        box.addView(meta);
        if (torrent) {
            TextView peers = UiKit.caption(act, trf("{0} seeders · {1} peers", r.optInt("seeders"), r.optInt("leechers")));
            if (r.optInt("seeders") == 0) peers.setTextColor(AppTheme.danger());
            box.addView(peers);
        }
        final boolean rejected = isRejected(r);
        JSONArray rej = r.optJSONArray("rejections");
        for (int i = 0; rej != null && i < rej.length() && i < 2; i++) {
            Object item = rej.opt(i);
            String reason = item instanceof JSONObject ? ((JSONObject) item).optString("reason", "") : String.valueOf(item);
            if (reason.isEmpty() || "null".equals(reason)) continue;
            TextView w = UiKit.caption(act, "• " + reason);
            w.setTextColor(AppTheme.warning());
            w.setPadding(0, dp(2), 0, 0);
            box.addView(w);
        }
        final Button grab = rejected ? UiKit.outline(act, tr("Grab anyway"), null) : UiKit.primary(act, tr("Grab"), null);
        grab.setOnClickListener(v -> {
            final Runnable go = () -> action(grab, api -> {
                api.grabRelease(r);
                return null;
            }, tr("Sent to the download client"), () -> {
                grab.setText(tr("Sent"));
                setBusy(grab, true);
            });
            if (rejected) {
                confirm(tr("Grab a rejected release?"), tr("Radarr rejected this release for the reasons shown. Download it anyway?"),
                        tr("Grab anyway"), false, go);
            } else {
                go.run();
            }
        });
        LinearLayout actions = UiKit.row(act);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(grab, new LinearLayout.LayoutParams(-2, dp(38)));
        box.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ================================================================== ADD movie

    private void showAddDialog() {
        final LinearLayout body = dialogBody(tr("Add movie"), tr("Search TMDb through Radarr"));
        LinearLayout bar = UiKit.row(act);
        bar.setPadding(0, dp(12), 0, 0);
        final EditText term = UiKit.input(act, tr("Movie title"));
        term.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        bar.addView(term, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Button go = UiKit.primary(act, tr("Search"), null);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.setMarginStart(dp(8));
        bar.addView(go, glp);
        body.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout results = UiKit.column(act);
        TextView hint = UiKit.caption(act, tr("Tip: tmdb:603 or imdb:tt0133093 finds an exact movie."));
        hint.setPadding(dp(2), dp(10), 0, 0);
        results.addView(hint);
        body.addView(scrollBox(results));
        final AlertDialog d = listDialog(body);
        body.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Close"), v -> d.dismiss())));
        final int[] seq = {0};
        final Runnable run = () -> {
            final String q = term.getText().toString().trim();
            if (q.isEmpty() || !go.isEnabled()) return;
            final int my = ++seq[0];
            results.removeAllViews();
            results.addView(UiKit.loading(act, tr("Searching…")));
            setBusy(go, true);
            async(api -> api.lookup(q), arr -> {
                if (my != seq[0]) return;
                setBusy(go, false);
                results.removeAllViews();
                if (arr.length() == 0) {
                    results.addView(UiKit.empty(act, tr("No results"), tr("Check the spelling or try the original title.")));
                    return;
                }
                for (int i = 0; i < Math.min(arr.length(), 30); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r != null) results.addView(lookupRow(r, d));
                }
            }, t -> {
                if (my != seq[0]) return;
                setBusy(go, false);
                results.removeAllViews();
                results.addView(errorView(t, false));
            });
        };
        go.setOnClickListener(v -> run.run());
        term.setOnEditorActionListener((v, actionId, ev) -> {
            run.run();
            return true;
        });
        show(d);
        term.requestFocus();
    }

    private View lookupRow(final JSONObject r, final AlertDialog parent) {
        LinearLayout row = listRow();
        row.setGravity(Gravity.TOP);
        row.addView(poster(r, 40), posterLp(40));
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(titleYear(r)));
        tx.addView(rowMeta(joinDot(fmtRuntime(r.optInt("runtime")), RadarrApi.str(r, "studio"))));
        String ov = RadarrApi.str(r, "overview");
        if (!ov.isEmpty()) {
            TextView o = UiKit.caption(act, ov);
            o.setMaxLines(2);
            o.setEllipsize(TextUtils.TruncateAt.END);
            o.setPadding(0, dp(2), 0, 0);
            tx.addView(o);
        }
        row.addView(tx, textLp());
        final int existing = r.optInt("id");
        if (existing > 0) row.addView(UiKit.badge(act, tr("In library"), AppTheme.success()));
        row.setOnClickListener(v -> {
            if (debounce()) return;
            if (existing > 0) {
                parent.dismiss();
                section = S_MOVIES;
                openMovie(existing);
            } else {
                showAddOptions(r, parent);
            }
        });
        return row;
    }

    private SharedPreferences prefs() {
        return act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void showAddOptions(final JSONObject lookup, final AlertDialog parent) {
        final String title = titleYear(lookup);
        final LinearLayout body = dialogBody(tr("Add movie"), title);
        final LinearLayout form = UiKit.column(act);
        form.addView(UiKit.loading(act, tr("Loading options…")));
        body.addView(form, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog d = dialog(body);
        show(d);
        async(api -> {
            JSONObject o = new JSONObject();
            o.put("roots", api.rootFolders());
            o.put("profiles", api.qualityProfiles());
            return o;
        }, o -> {
            form.removeAllViews();
            JSONArray roots = o.optJSONArray("roots");
            JSONArray profiles = o.optJSONArray("profiles");
            if (roots == null || roots.length() == 0 || profiles == null || profiles.length() == 0) {
                form.addView(UiKit.error(act, tr(roots == null || roots.length() == 0 ? "No root folder" : "No quality profile"),
                        tr(roots == null || roots.length() == 0
                                ? "Add a root folder in the System section first, then try again."
                                : "Radarr has no quality profile. Create one in Radarr's web interface."), null, null));
                form.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Close"), v -> d.dismiss())));
                return;
            }
            SharedPreferences sp = prefs();
            final String[] rootPaths = new String[roots.length()];
            final String[] rootLabels = new String[roots.length()];
            int rootSel = 0;
            for (int i = 0; i < roots.length(); i++) {
                JSONObject rf = roots.optJSONObject(i);
                rootPaths[i] = rf == null ? "" : rf.optString("path");
                long free = rf == null ? 0 : rf.optLong("freeSpace");
                rootLabels[i] = rootPaths[i] + (free > 0 ? "  ·  " + trf("{0} free", fmtSize(free)) : "");
                if (rootPaths[i].equals(sp.getString("root", ""))) rootSel = i;
            }
            final String[] profNames = new String[profiles.length()];
            final int[] profIds = new int[profiles.length()];
            int profSel = 0;
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.optJSONObject(i);
                profNames[i] = p == null ? "?" : p.optString("name");
                profIds[i] = p == null ? 0 : p.optInt("id");
                if (profIds[i] == sp.getInt("profile", -1)) profSel = i;
            }
            final String[] availShown = new String[AVAIL_LABELS.length];
            int availSel = 2;
            for (int i = 0; i < AVAIL_LABELS.length; i++) {
                availShown[i] = tr(AVAIL_LABELS[i]);
                if (AVAIL_VALUES[i].equals(sp.getString("availability", "released"))) availSel = i;
            }
            final int[] selRoot = {rootSel};
            final int[] selProf = {profSel};
            final int[] selAvail = {availSel};
            form.addView(pickField(tr("Root folder"), rootLabels, selRoot, true));
            form.addView(pickField(tr("Quality profile"), profNames, selProf, false));
            form.addView(pickField(tr("Minimum availability"), availShown, selAvail, false));
            final Switch monitored = switchRow(form, tr("Monitored"), tr("Search for it now and upgrade it later."), true);
            final Switch search = switchRow(form, tr("Start search now"), tr("Look for a release right after adding."), true);
            final TextView err = UiKit.caption(act, "");
            err.setTextColor(AppTheme.danger());
            err.setVisibility(View.GONE);
            err.setPadding(0, dp(8), 0, 0);
            form.addView(err);
            final Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
            final Button add = UiKit.primary(act, tr("Add movie"), null);
            add.setOnClickListener(v -> {
                if (!add.isEnabled()) return;
                final String root0 = rootPaths[selRoot[0]];
                final int prof0 = profIds[selProf[0]];
                final String avail0 = AVAIL_VALUES[selAvail[0]];
                final boolean mon0 = monitored.isChecked();
                final boolean search0 = search.isChecked() && mon0;
                setBusy(add, true);
                setBusy(cancel, true);
                add.setText(tr("Adding…"));
                err.setVisibility(View.GONE);
                async(api -> api.addMovie(lookup, root0, prof0, mon0, avail0, search0), created -> {
                    prefs().edit().putString("root", root0).putInt("profile", prof0).putString("availability", avail0).apply();
                    d.dismiss();
                    parent.dismiss();
                    movieCache = null;
                    toast(trf("{0} added", title));
                    int nid = created.optInt("id");
                    section = S_MOVIES;
                    if (nid > 0) openMovie(nid);
                    else refresh();
                }, t -> {
                    setBusy(add, false);
                    setBusy(cancel, false);
                    add.setText(tr("Add movie"));
                    err.setText(errText(t));
                    err.setVisibility(View.VISIBLE);
                });
            });
            form.addView(UiKit.buttonRow(act, cancel, add));
        }, t -> {
            form.removeAllViews();
            form.addView(errorView(t, false));
            form.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Close"), v -> d.dismiss())));
        });
    }

    /** Label + tappable value box that opens a single-choice list. */
    private View pickField(final String label, final String[] options, final int[] selected, boolean mono) {
        LinearLayout box = UiKit.column(act);
        TextView l = UiKit.caption(act, label);
        l.setPadding(dp(2), dp(12), 0, dp(6));
        box.addView(l);
        LinearLayout field = UiKit.row(act);
        field.setPadding(dp(14), dp(11), dp(14), dp(11));
        field.setBackground(UiKit.pressable(act, AppTheme.inner(), AppTheme.outline(), UiKit.R_INNER));
        final TextView value = mono ? UiKit.mono(act, options[selected[0]])
                : UiKit.text(act, options[selected[0]], 14f, AppTheme.text(), null);
        value.setTextIsSelectable(false);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        field.addView(value, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView caret = UiKit.caption(act, "▾");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMarginStart(dp(8));
        field.addView(caret, clp);
        field.setClickable(true);
        field.setOnClickListener(v -> {
            if (dead()) return;
            new AlertDialog.Builder(act).setTitle(label).setSingleChoiceItems(options, selected[0], (dd, which) -> {
                selected[0] = which;
                value.setText(options[which]);
                dd.dismiss();
            }).show();
        });
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ================================================================== CALENDAR

    private void loadCalendar() {
        final int req = ++requestCounter;
        showLoading(tr("Loading calendar…"));
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -7);
        final String start = f.format(c.getTime());
        c.add(Calendar.DAY_OF_YEAR, 7 + 60);
        final String end = f.format(c.getTime());
        async(api -> api.calendar(start, end), arr -> {
            if (req != requestCounter || section != S_CALENDAR || detailMovieId > 0) return;
            content.removeAllViews();
            TreeMap<String, List<Object[]>> byDay = new TreeMap<>();
            String[] keys = {"inCinemas", "digitalRelease", "physicalRelease"};
            String[] kinds = {"In cinemas", "Digital release", "Physical release"};
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                for (int k = 0; k < keys.length; k++) {
                    String iso = RadarrApi.str(m, keys[k]);
                    if (iso.length() < 10) continue;
                    String day = iso.substring(0, 10);
                    if (day.compareTo(start) < 0 || day.compareTo(end) > 0) continue;
                    List<Object[]> l = byDay.get(day);
                    if (l == null) {
                        l = new ArrayList<>();
                        byDay.put(day, l);
                    }
                    l.add(new Object[]{m, kinds[k]});
                }
            }
            if (byDay.isEmpty()) {
                content.addView(UiKit.empty(act, tr("Nothing scheduled"),
                        tr("No monitored movie is released between last week and the next two months.")));
                return;
            }
            String today = f.format(new Date());
            for (Map.Entry<String, List<Object[]>> en : byDay.entrySet()) {
                String label = fmtDay(en.getKey());
                if (en.getKey().equals(today)) label = trf("Today · {0}", label);
                TextView h = UiKit.overline(act, label);
                if (en.getKey().equals(today)) h.setTextColor(AppTheme.accent());
                h.setPadding(dp(2), dp(6), 0, dp(8));
                content.addView(h);
                LinearLayout card = listCard();
                int n = 0;
                for (Object[] e : en.getValue()) {
                    if (n++ > 0) card.addView(separator(56));
                    card.addView(calendarRow((JSONObject) e[0], (String) e[1]));
                }
                content.addView(card);
            }
        }, t -> {
            if (req == requestCounter && section == S_CALENDAR) showError(t);
        });
    }

    private View calendarRow(final JSONObject m, String kind) {
        LinearLayout row = listRow();
        row.addView(poster(m, 32), posterLp(32));
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(titleYear(m)));
        tx.addView(rowMeta(joinDot(tr(kind), fmtRuntime(m.optInt("runtime")))));
        row.addView(tx, textLp());
        View b = movieBadge(m);
        if (b != null) row.addView(b);
        final int mid = m.optInt("id");
        if (mid > 0) row.setOnClickListener(v -> openMovie(mid));
        return row;
    }

    // ================================================================== QUEUE

    private void loadQueue(final boolean silent) {
        final int req = ++requestCounter;
        if (!silent) showLoading(tr("Loading queue…"));
        async(api -> api.queue(1, 100), o -> {
            if (req != requestCounter || section != S_QUEUE || detailMovieId > 0) return;
            queueOfflineShown = false;
            final int y = scroll.getScrollY();
            renderQueue(o);
            if (silent) scroll.post(() -> scroll.scrollTo(0, y));
            schedulePoll();
        }, t -> {
            if (req != requestCounter || section != S_QUEUE || detailMovieId > 0) return;
            if (!silent || (RadarrApi.isOffline(t) && !queueOfflineShown)) {
                showError(t);
                queueOfflineShown = RadarrApi.isOffline(t);
            }
            schedulePoll();
        });
    }

    private void renderQueue(JSONObject o) {
        content.removeAllViews();
        JSONArray recs = o.optJSONArray("records");
        int count = recs == null ? 0 : recs.length();
        int total = o.optInt("totalRecords", count);
        if (count == 0) {
            content.addView(UiKit.empty(act, tr("The queue is empty"),
                    tr("Downloads Radarr sends to the download client show up here.")));
            return;
        }
        TextView c = UiKit.caption(act, total > count ? trf("{0} in queue · showing {1}", total, count) : trf("{0} in queue", total));
        c.setPadding(dp(2), 0, 0, dp(8));
        content.addView(c);
        LinearLayout card = listCard();
        for (int i = 0; i < count; i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r == null) continue;
            if (card.getChildCount() > 0) card.addView(separator(12));
            card.addView(queueRow(r));
        }
        content.addView(card);
    }

    private View queueRow(final JSONObject r) {
        LinearLayout box = UiKit.column(act);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackground(UiKit.rowRipple(act));
        JSONObject m = r.optJSONObject("movie");
        final String movieTitle = m != null ? titleYear(m) : r.optString("title");

        LinearLayout top = UiKit.row(act);
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(movieTitle));
        TextView rel = UiKit.caption(act, r.optString("title"));
        rel.setSingleLine(true);
        rel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        rel.setPadding(0, dp(2), 0, 0);
        tx.addView(rel);
        top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Object[] st = queueStatus(r);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        top.addView(UiKit.badge(act, (String) st[0], (Integer) st[1]), blp);
        box.addView(top);

        double size = r.optDouble("size", 0);
        double left = r.has("sizeleft") ? r.optDouble("sizeleft", 0) : r.optDouble("sizeLeft", 0);
        int pct = size > 0 ? (int) Math.max(0, Math.min(100, Math.round((size - left) * 100.0 / size))) : 0;
        box.addView(UiKit.progress(act, pct, (Integer) st[1]));
        String timeleft = RadarrApi.str(r, "timeleft");
        if (timeleft.isEmpty()) timeleft = RadarrApi.str(r, "timeLeft");
        String eta = fmtTimeLeft(timeleft);
        TextView meta = UiKit.caption(act, joinDot(pct + "%",
                size > 0 ? trf("{0} of {1}", fmtSize((long) (size - left)), fmtSize((long) size)) : "",
                eta.isEmpty() ? "" : trf("{0} left", eta), RadarrApi.str(r, "downloadClient")));
        meta.setPadding(0, dp(6), 0, 0);
        box.addView(meta);

        JSONArray msgs = r.optJSONArray("statusMessages");
        int shown = 0;
        for (int i = 0; msgs != null && i < msgs.length() && shown < 2; i++) {
            JSONObject m0 = msgs.optJSONObject(i);
            if (m0 == null) continue;
            JSONArray mm = m0.optJSONArray("messages");
            String w = mm != null && mm.length() > 0 ? mm.optString(0) : m0.optString("title");
            if (w == null || w.isEmpty()) continue;
            TextView wt = UiKit.caption(act, "• " + w);
            wt.setTextColor(AppTheme.warning());
            wt.setPadding(0, dp(3), 0, 0);
            box.addView(wt);
            shown++;
        }
        String em = RadarrApi.str(r, "errorMessage");
        if (!em.isEmpty()) {
            TextView et = UiKit.caption(act, em);
            et.setTextColor(AppTheme.danger());
            et.setPadding(0, dp(3), 0, 0);
            box.addView(et);
        }
        final int qid = r.optInt("id");
        LinearLayout actions = UiKit.row(act);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button remove = UiKit.danger(act, tr("Remove"), v -> {
            if (!debounce()) showRemoveQueue(qid, movieTitle);
        });
        actions.addView(remove, new LinearLayout.LayoutParams(-2, dp(36)));
        box.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        final int mid = r.optInt("movieId");
        if (mid > 0) box.setOnClickListener(v -> openMovie(mid));
        return box;
    }

    /** [label, colour] for a queue record. */
    private static Object[] queueStatus(JSONObject r) {
        String status = RadarrApi.str(r, "status");
        String tracked = RadarrApi.str(r, "trackedDownloadStatus");
        String state = RadarrApi.str(r, "trackedDownloadState");
        if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(tracked) || "failedPending".equals(state)) {
            return new Object[]{tr("Failed"), AppTheme.danger()};
        }
        if ("importBlocked".equals(state)) return new Object[]{tr("Import blocked"), AppTheme.warning()};
        if ("warning".equalsIgnoreCase(tracked) || "warning".equalsIgnoreCase(status)) return new Object[]{tr("Warning"), AppTheme.warning()};
        if ("importPending".equals(state)) return new Object[]{tr("Import pending"), AppTheme.accent()};
        if ("importing".equals(state)) return new Object[]{tr("Importing"), AppTheme.accent()};
        if ("imported".equals(state)) return new Object[]{tr("Imported"), AppTheme.success()};
        if ("downloadClientUnavailable".equalsIgnoreCase(status)) return new Object[]{tr("Client offline"), AppTheme.danger()};
        if ("completed".equalsIgnoreCase(status)) return new Object[]{tr("Completed"), AppTheme.success()};
        if ("paused".equalsIgnoreCase(status)) return new Object[]{tr("Paused"), AppTheme.textMuted()};
        if ("queued".equalsIgnoreCase(status)) return new Object[]{tr("Queued"), AppTheme.textMuted()};
        if ("delay".equalsIgnoreCase(status)) return new Object[]{tr("Delayed"), AppTheme.textMuted()};
        return new Object[]{tr("Downloading"), AppTheme.accent()};
    }

    private void showRemoveQueue(final int id, String title) {
        final LinearLayout body = dialogBody(tr("Remove from queue?"), title);
        final Switch client = switchRow(body, tr("Remove from the download client"),
                tr("Also deletes the torrent and its data in the download client."), true);
        final Switch block = switchRow(body, tr("Block this release"),
                tr("Adds it to the blocklist so Radarr does not grab it again."), false);
        final AlertDialog d = dialog(body);
        final Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
        final Button rm = UiKit.danger(act, tr("Remove"), null);
        rm.setOnClickListener(v -> {
            final boolean rc = client.isChecked();
            final boolean bl = block.isChecked();
            action(rm, api -> {
                api.removeQueueItem(id, rc, bl);
                return null;
            }, tr("Removed from the queue"), () -> {
                d.dismiss();
                if (section == S_QUEUE) loadQueue(true);
            });
        });
        body.addView(UiKit.buttonRow(act, cancel, rm));
        show(d);
    }

    // ================================================================== HISTORY

    private void loadHistory() {
        final int req = ++requestCounter;
        final int pg = page;
        showLoading(tr("Loading history…"));
        async(api -> api.history(pg, PAGE_SIZE), o -> {
            if (req != requestCounter || section != S_HISTORY || detailMovieId > 0) return;
            content.removeAllViews();
            JSONArray recs = o.optJSONArray("records");
            int total = o.optInt("totalRecords", recs == null ? 0 : recs.length());
            if (recs == null || recs.length() == 0) {
                content.addView(UiKit.empty(act, tr("No history yet"), tr("Grabs, imports and failures appear here.")));
                return;
            }
            TextView c = UiKit.caption(act, trf("{0} events", total));
            c.setPadding(dp(2), 0, 0, dp(8));
            content.addView(c);
            LinearLayout card = listCard();
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r == null) continue;
                if (card.getChildCount() > 0) card.addView(separator(12));
                card.addView(historyRow(r));
            }
            content.addView(card);
            addPager(pg, total);
        }, t -> {
            if (req == requestCounter && section == S_HISTORY) showError(t);
        });
    }

    private View historyRow(final JSONObject r) {
        LinearLayout row = listRow();
        JSONObject m = r.optJSONObject("movie");
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(m != null ? titleYear(m) : r.optString("sourceTitle")));
        JSONObject data = r.optJSONObject("data");
        String reason = data == null ? "" : RadarrApi.str(data, "reason");
        tx.addView(rowMeta(joinDot(qualityName(r), relTime(parseIso(RadarrApi.str(r, "date"))), reason)));
        if (m != null) {
            TextView src = UiKit.caption(act, r.optString("sourceTitle"));
            src.setSingleLine(true);
            src.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            src.setPadding(0, dp(2), 0, 0);
            tx.addView(src);
        }
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        String ev = RadarrApi.str(r, "eventType");
        String label;
        int color;
        if ("grabbed".equals(ev)) { label = "Grabbed"; color = AppTheme.accent(); }
        else if ("downloadFolderImported".equals(ev) || "movieFolderImported".equals(ev)) { label = "Imported"; color = AppTheme.success(); }
        else if ("downloadFailed".equals(ev)) { label = "Failed"; color = AppTheme.danger(); }
        else if ("movieFileDeleted".equals(ev)) { label = "File deleted"; color = AppTheme.warning(); }
        else if ("movieFileRenamed".equals(ev)) { label = "Renamed"; color = AppTheme.textMuted(); }
        else if ("downloadIgnored".equals(ev)) { label = "Ignored"; color = AppTheme.textMuted(); }
        else { label = ev.isEmpty() ? "Event" : capitalize(ev); color = AppTheme.textMuted(); }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        row.addView(UiKit.badge(act, tr(label), color), blp);
        final int mid = m != null ? m.optInt("id") : r.optInt("movieId");
        if (mid > 0) row.setOnClickListener(v -> openMovie(mid));
        return row;
    }

    // ================================================================== WANTED

    private void loadWanted() {
        final int req = ++requestCounter;
        final int pg = page;
        final boolean cutoff = wantedCutoff;
        showLoading(tr("Loading wanted movies…"));
        async(api -> {
            try {
                return cutoff ? api.wantedCutoff(pg, PAGE_SIZE) : api.wantedMissing(pg, PAGE_SIZE);
            } catch (RadarrApi.ApiException e) {
                // Older Radarr builds have no /wanted endpoints: compute the same list from the library.
                if (e.status == 404) return api.wantedFromLibrary(cutoff, pg, PAGE_SIZE);
                throw e;
            }
        }, o -> {
            if (req != requestCounter || section != S_WANTED || detailMovieId > 0) return;
            renderWanted(o, pg, cutoff);
        }, t -> {
            if (req == requestCounter && section == S_WANTED) showError(t);
        });
    }

    private void renderWanted(JSONObject o, int pg, final boolean cutoff) {
        content.removeAllViews();
        int total = o.optInt("totalRecords");

        LinearLayout toggle = UiKit.row(act);
        toggle.setPadding(0, 0, 0, dp(10));
        TextView pm = UiKit.chip(act, tr("Missing"), !cutoff);
        pm.setOnClickListener(v -> {
            if (!wantedCutoff) return;
            wantedCutoff = false;
            page = 1;
            refresh();
        });
        toggle.addView(pm, UiKit.chipLp(act));
        TextView pc = UiKit.chip(act, tr("Cutoff unmet"), cutoff);
        pc.setOnClickListener(v -> {
            if (wantedCutoff) return;
            wantedCutoff = true;
            page = 1;
            refresh();
        });
        toggle.addView(pc, UiKit.chipLp(act));
        content.addView(UiKit.chipScroller(act, toggle), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = UiKit.row(act);
        bar.setPadding(0, 0, 0, dp(10));
        TextView count = UiKit.caption(act, trf("{0} movies", total));
        bar.addView(count, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Button all = UiKit.primary(act, tr("Search all"), null);
        all.setEnabled(total > 0);
        if (total == 0) all.setAlpha(0.5f);
        all.setOnClickListener(v -> confirm(tr("Search all?"),
                tr(cutoff ? "Radarr searches for an upgrade for every monitored movie below its quality cutoff."
                        : "Radarr searches for every monitored missing movie. This can send many requests to your indexers."),
                tr("Search all"), false, () -> action(all, api -> {
                    if (cutoff) api.searchCutoffUnmet(); else api.searchMissing();
                    return null;
                }, tr("Search started"), null)));
        bar.addView(all, new LinearLayout.LayoutParams(-2, -2));
        content.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        JSONArray recs = o.optJSONArray("records");
        if (recs == null || recs.length() == 0) {
            content.addView(UiKit.empty(act, tr(cutoff ? "Nothing below cutoff" : "Nothing missing"),
                    tr(cutoff ? "Every monitored movie meets its quality cutoff." : "Every monitored, available movie has a file.")));
            return;
        }
        LinearLayout card = listCard();
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r == null) continue;
            if (card.getChildCount() > 0) card.addView(separator(60));
            card.addView(wantedRow(r, cutoff));
        }
        content.addView(card);
        addPager(pg, total);
    }

    private View wantedRow(final JSONObject m, boolean cutoff) {
        LinearLayout row = listRow();
        row.addView(poster(m, 36), posterLp(36));
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(titleYear(m)));
        String when;
        String dig = fmtDay(RadarrApi.str(m, "digitalRelease"));
        String phys = fmtDay(RadarrApi.str(m, "physicalRelease"));
        String cin = fmtDay(RadarrApi.str(m, "inCinemas"));
        if (!dig.isEmpty()) when = trf("Digital {0}", dig);
        else if (!phys.isEmpty()) when = trf("Physical {0}", phys);
        else if (!cin.isEmpty()) when = trf("In cinemas {0}", cin);
        else when = tr(statusLabel(RadarrApi.str(m, "status")));
        String q = cutoff ? qualityName(m.optJSONObject("movieFile")) : "";
        tx.addView(rowMeta(joinDot(q, when)));
        row.addView(tx, textLp());
        final int mid = m.optInt("id");
        final Button s = UiKit.tonal(act, tr("Search"), null);
        s.setOnClickListener(v -> action(s, api -> {
            api.searchMovie(mid);
            return null;
        }, tr("Search started"), () -> {
            s.setText(tr("Queued"));
            setBusy(s, true);
        }));
        row.addView(s, new LinearLayout.LayoutParams(-2, dp(36)));
        if (mid > 0) row.setOnClickListener(v -> openMovie(mid));
        return row;
    }

    private void addPager(int pg, int total) {
        if (total <= PAGE_SIZE) return;
        final int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        Button prev = UiKit.outline(act, tr("Previous"), v -> {
            page = Math.max(1, page - 1);
            refresh();
            scroll.scrollTo(0, 0);
        });
        Button next = UiKit.outline(act, tr("Next"), v -> {
            page = Math.min(pages, page + 1);
            refresh();
            scroll.scrollTo(0, 0);
        });
        if (pg <= 1) setBusy(prev, true);
        if (pg >= pages) setBusy(next, true);
        TextView label = UiKit.caption(act, trf("Page {0} of {1}", pg, pages));
        label.setGravity(Gravity.CENTER);
        content.addView(UiKit.buttonRow(act, prev, label, next));
    }

    // ================================================================== SYSTEM

    private void loadSystem() {
        final int req = ++requestCounter;
        showLoading(tr("Loading system status…"));
        async(api -> {
            JSONObject o = new JSONObject();
            o.put("status", api.status());
            try { o.put("health", api.health()); } catch (Throwable ignored) {}
            try { o.put("disk", api.diskSpace()); } catch (Throwable ignored) {}
            try { o.put("roots", api.rootFolders()); } catch (Throwable ignored) {}
            try { o.put("profiles", api.qualityProfiles()); } catch (Throwable ignored) {}
            try { o.put("indexers", api.indexers()); } catch (Throwable ignored) {}
            try { o.put("clients", api.downloadClients()); } catch (Throwable ignored) {}
            return o;
        }, o -> {
            if (req != requestCounter || section != S_SYSTEM || detailMovieId > 0) return;
            renderSystem(o);
        }, t -> {
            if (req == requestCounter && section == S_SYSTEM) showError(t);
        });
    }

    private void renderSystem(JSONObject o) {
        content.removeAllViews();
        JSONObject st = o.optJSONObject("status");
        LinearLayout c = UiKit.card(act);
        c.addView(UiKit.overline(act, tr("Status")));
        if (st != null) {
            String v = RadarrApi.str(st, "version");
            if (!v.isEmpty() && !v.equals(version)) {
                version = v;
                updateHeader();
            }
            c.addView(UiKit.kv(act, tr("Version"), orDash(v)));
            c.addView(UiKit.kv(act, tr("Operating system"), orDash(joinSpace(RadarrApi.str(st, "osName"), RadarrApi.str(st, "osVersion")))));
            String rt = joinSpace(RadarrApi.str(st, "runtimeName"), RadarrApi.str(st, "runtimeVersion"));
            if (!rt.isEmpty()) c.addView(UiKit.kv(act, tr("Runtime"), rt));
            String started = fmtDateTime(RadarrApi.str(st, "startTime"));
            if (!started.isEmpty()) c.addView(UiKit.kv(act, tr("Started"), started));
            String appData = RadarrApi.str(st, "appData");
            if (!appData.isEmpty()) {
                TextView l = UiKit.caption(act, tr("App data"));
                l.setPadding(0, dp(8), 0, dp(2));
                c.addView(l);
                c.addView(UiKit.mono(act, appData));
            }
        }
        content.addView(c);

        addHealthCard(o.optJSONArray("health"));

        JSONArray disk = o.optJSONArray("disk");
        if (disk != null && disk.length() > 0) {
            c = UiKit.card(act);
            c.addView(UiKit.overline(act, tr("Disk space")));
            for (int i = 0; i < disk.length(); i++) {
                JSONObject dsk = disk.optJSONObject(i);
                if (dsk == null) continue;
                long total = dsk.optLong("totalSpace");
                long free = dsk.optLong("freeSpace");
                String label = RadarrApi.str(dsk, "label");
                TextView p = UiKit.mono(act, RadarrApi.str(dsk, "path") + (label.isEmpty() ? "" : "  (" + label + ")"));
                p.setTextIsSelectable(false);
                p.setPadding(0, i > 0 ? dp(10) : 0, 0, 0);
                c.addView(p);
                int used = total > 0 ? (int) ((total - free) * 100 / total) : 0;
                c.addView(UiKit.progress(act, used, used >= 90 ? AppTheme.danger() : used >= 75 ? AppTheme.warning() : AppTheme.accent()));
                TextView fr = UiKit.caption(act, trf("{0} free of {1}", fmtSize(free), fmtSize(total)));
                fr.setPadding(0, dp(4), 0, 0);
                c.addView(fr);
            }
            content.addView(c);
        }

        // root folders
        c = UiKit.card(act);
        c.addView(UiKit.overline(act, tr("Root folders")));
        JSONArray roots = o.optJSONArray("roots");
        if (roots == null || roots.length() == 0) {
            c.addView(UiKit.body(act, tr("No root folder yet. Movies cannot be added until you add one.")));
        }
        for (int i = 0; roots != null && i < roots.length(); i++) {
            final JSONObject rf = roots.optJSONObject(i);
            if (rf == null) continue;
            LinearLayout row = UiKit.row(act);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout tx = UiKit.column(act);
            TextView path = UiKit.mono(act, rf.optString("path"));
            path.setTextIsSelectable(false);
            tx.addView(path);
            boolean ok = rf.optBoolean("accessible", true);
            TextView free = UiKit.caption(act, ok ? trf("{0} free", fmtSize(rf.optLong("freeSpace"))) : tr("Not accessible"));
            if (!ok) free.setTextColor(AppTheme.danger());
            tx.addView(free);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
            final Button rm = UiKit.danger(act, tr("Remove"), null);
            rm.setOnClickListener(v -> confirm(tr("Remove root folder?"),
                    tr("Radarr stops using this folder. Files on disk are not deleted."), tr("Remove"), true,
                    () -> action(rm, api -> {
                        api.deleteRootFolder(rf.optInt("id"));
                        return null;
                    }, tr("Root folder removed"), this::refresh)));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, dp(36));
            rlp.setMarginStart(dp(8));
            row.addView(rm, rlp);
            c.addView(row);
        }
        c.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Add root folder"), v -> {
            if (!debounce()) showAddRoot();
        })));
        content.addView(c);

        JSONArray profs = o.optJSONArray("profiles");
        if (profs != null && profs.length() > 0) {
            c = UiKit.card(act);
            c.addView(UiKit.overline(act, tr("Quality profiles")));
            List<String> names = new ArrayList<>();
            for (int i = 0; i < profs.length(); i++) {
                JSONObject p = profs.optJSONObject(i);
                if (p != null) names.add(p.optString("name"));
            }
            c.addView(UiKit.body(act, TextUtils.join(" · ", names)));
            content.addView(c);
        }

        addProviderCard(tr("Indexers"), o.optJSONArray("indexers"), "indexer",
                tr("Indexers are synced from Prowlarr."));
        addProviderCard(tr("Download clients"), o.optJSONArray("clients"), "downloadclient", null);

        c = UiKit.card(act);
        c.addView(UiKit.overline(act, tr("Tasks")));
        c.addView(UiKit.buttonRow(act,
                commandButton(tr("RSS sync"), "RssSync", tr("RSS sync started")),
                commandButton(tr("Check downloads"), "RefreshMonitoredDownloads", tr("Checking downloads…"))));
        c.addView(UiKit.buttonRow(act,
                commandButton(tr("Refresh all"), "RefreshMovie", tr("Refreshing all movies…")),
                commandButton(tr("Rescan all"), "RescanMovie", tr("Rescanning all movie folders…"))));
        c.addView(UiKit.buttonRow(act, commandButton(tr("Backup"), "Backup", tr("Backup started"))));
        content.addView(c);

        TextView note = UiKit.caption(act, tr("Advanced settings are in Radarr's own web interface."));
        note.setPadding(dp(2), dp(4), dp(2), 0);
        content.addView(note);
    }

    private Button commandButton(String label, final String command, final String okMsg) {
        final Button b = UiKit.outline(act, label, null);
        b.setOnClickListener(v -> action(b, api -> {
            api.simpleCommand(command);
            return null;
        }, okMsg, null));
        return b;
    }

    private void addHealthCard(JSONArray health) {
        LinearLayout c = UiKit.card(act);
        c.addView(UiKit.overline(act, tr("Health")));
        if (health == null || health.length() == 0) {
            LinearLayout row = UiKit.row(act);
            row.addView(UiKit.badge(act, tr("Healthy"), AppTheme.success()));
            TextView t = UiKit.caption(act, tr("No issues found."));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(10));
            row.addView(t, lp);
            c.addView(row);
        } else {
            for (int i = 0; i < health.length(); i++) {
                JSONObject h = health.optJSONObject(i);
                if (h == null) continue;
                String type = RadarrApi.str(h, "type");
                int color = "error".equalsIgnoreCase(type) ? AppTheme.danger()
                        : "warning".equalsIgnoreCase(type) ? AppTheme.warning() : AppTheme.accent();
                String label = "error".equalsIgnoreCase(type) ? "Error" : "warning".equalsIgnoreCase(type) ? "Warning" : "Notice";
                LinearLayout row = UiKit.row(act);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, dp(6), 0, dp(6));
                row.addView(UiKit.badge(act, tr(label), color));
                TextView t = UiKit.body(act, RadarrApi.str(h, "message"));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                lp.setMarginStart(dp(10));
                row.addView(t, lp);
                c.addView(row);
            }
        }
        content.addView(c);
    }

    private void addProviderCard(String title, final JSONArray arr, final String kind, String note) {
        LinearLayout c = UiKit.card(act);
        c.addView(UiKit.overline(act, title));
        if (arr == null || arr.length() == 0) {
            c.addView(UiKit.body(act, tr("None configured.")));
        } else {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                boolean on = "indexer".equals(kind)
                        ? (o.optBoolean("enableRss") || o.optBoolean("enableAutomaticSearch") || o.optBoolean("enableInteractiveSearch"))
                        : o.optBoolean("enable", true);
                LinearLayout row = UiKit.row(act);
                row.setPadding(0, dp(6), 0, dp(6));
                LinearLayout tx = UiKit.column(act);
                tx.addView(rowTitle(o.optString("name")));
                tx.addView(rowMeta(joinDot(o.optString("implementationName", o.optString("implementation")),
                        tr(RadarrApi.str(o, "protocol").isEmpty() ? "" : capitalize(RadarrApi.str(o, "protocol"))))));
                row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
                row.addView(UiKit.badge(act, tr(on ? "Enabled" : "Disabled"), on ? AppTheme.success() : AppTheme.textMuted()));
                c.addView(row);
            }
            final Button test = UiKit.outline(act, tr("Test all"), null);
            test.setOnClickListener(v -> testAll(test, kind, arr));
            c.addView(UiKit.buttonRow(act, test));
        }
        if (note != null) {
            TextView n = UiKit.caption(act, note);
            n.setPadding(0, dp(8), 0, 0);
            c.addView(n);
        }
        content.addView(c);
    }

    private void testAll(final Button b, final String kind, final JSONArray providers) {
        if (!b.isEnabled()) return;
        setBusy(b, true);
        b.setText(tr("Testing…"));
        async(api -> api.testAll(kind), res -> {
            setBusy(b, false);
            b.setText(tr("Test all"));
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < res.length(); i++) {
                JSONObject r = res.optJSONObject(i);
                if (r == null || r.optBoolean("isValid", true)) continue;
                String name = "#" + r.optInt("id");
                for (int j = 0; providers != null && j < providers.length(); j++) {
                    JSONObject p = providers.optJSONObject(j);
                    if (p != null && p.optInt("id") == r.optInt("id")) name = p.optString("name", name);
                }
                JSONArray vf = r.optJSONArray("validationFailures");
                JSONObject f0 = vf == null ? null : vf.optJSONObject(0);
                failed.add(name + (f0 == null ? "" : ": " + f0.optString("errorMessage", "")));
            }
            if (failed.isEmpty()) {
                toast(trf("All {0} passed the test", res.length()));
            } else {
                info(trf("{0} of {1} failed", failed.size(), res.length()), TextUtils.join("\n\n", failed));
            }
        }, t -> {
            setBusy(b, false);
            b.setText(tr("Test all"));
            toast(errText(t));
        });
    }

    private void showAddRoot() {
        final LinearLayout body = dialogBody(tr("Add root folder"), tr("Folder where Radarr keeps your movies"));
        final EditText path = UiKit.input(act, "/storage/emulated/0/Movies");
        path.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(12);
        body.addView(path, plp);
        final TextView err = UiKit.caption(act, "");
        err.setTextColor(AppTheme.danger());
        err.setVisibility(View.GONE);
        err.setPadding(0, dp(8), 0, 0);
        body.addView(err);
        final AlertDialog d = dialog(body);
        final Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
        final Button add = UiKit.primary(act, tr("Add"), null);
        add.setOnClickListener(v -> {
            final String p = path.getText().toString().trim();
            if (p.isEmpty() || !add.isEnabled()) return;
            setBusy(add, true);
            err.setVisibility(View.GONE);
            async(api -> {
                api.addRootFolder(p);
                return Boolean.TRUE;
            }, ok -> {
                d.dismiss();
                toast(tr("Root folder added"));
                refresh();
            }, t -> {
                setBusy(add, false);
                err.setText(errText(t));
                err.setVisibility(View.VISIBLE);
            });
        });
        body.addView(UiKit.buttonRow(act, cancel, add));
        show(d);
    }

    // ================================================================== dialogs

    private LinearLayout dialogBody(String title, String subtitle) {
        LinearLayout b = UiKit.column(act);
        b.setPadding(dp(20), dp(18), dp(20), dp(16));
        b.addView(UiKit.title(act, title));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = UiKit.caption(act, subtitle);
            s.setPadding(0, dp(2), 0, 0);
            s.setMaxLines(2);
            s.setEllipsize(TextUtils.TruncateAt.END);
            b.addView(s);
        }
        return b;
    }

    /** Dialog whose whole body scrolls (forms). */
    private AlertDialog dialog(View body) {
        ScrollView sv = new ScrollView(act);
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        return dialogFor(sv);
    }

    /** Dialog with its own inner scroll area (result lists): no outer ScrollView, so the list scrolls. */
    private AlertDialog listDialog(View body) {
        return dialogFor(body);
    }

    private AlertDialog dialogFor(View view) {
        AlertDialog d = new AlertDialog.Builder(act).setView(view).create();
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

    private void confirm(String title, String message, String positive, boolean destructive, final Runnable onYes) {
        if (dead()) return;
        LinearLayout body = dialogBody(title, null);
        TextView m = UiKit.body(act, message);
        m.setPadding(0, dp(8), 0, dp(4));
        body.addView(m);
        final AlertDialog d = dialog(body);
        Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
        Button ok = destructive ? UiKit.danger(act, positive, null) : UiKit.primary(act, positive, null);
        ok.setOnClickListener(v -> {
            d.dismiss();
            onYes.run();
        });
        body.addView(UiKit.buttonRow(act, cancel, ok));
        show(d);
    }

    private void info(String title, String message) {
        LinearLayout body = dialogBody(title, null);
        TextView m = UiKit.body(act, message);
        m.setPadding(0, dp(8), 0, dp(4));
        m.setTextIsSelectable(true);
        body.addView(m);
        final AlertDialog d = dialog(body);
        body.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Close"), v -> d.dismiss())));
        show(d);
    }

    /** Fixed-height scroll area for result lists inside a dialog. */
    private View scrollBox(View inner) {
        ScrollView sv = new ScrollView(act);
        sv.addView(inner, new ViewGroup.LayoutParams(-1, -2));
        int h = act.getResources().getDisplayMetrics().heightPixels;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.min(dp(440), (int) (h * 0.55f)));
        lp.topMargin = dp(8);
        sv.setLayoutParams(lp);
        return sv;
    }

    // ================================================================== building blocks

    private LinearLayout listCard() {
        LinearLayout card = UiKit.card(act);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        return card;
    }

    private LinearLayout listRow() {
        LinearLayout row = UiKit.row(act);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(UiKit.rowRipple(act));
        row.setClickable(true);
        return row;
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

    private TextView rowTitle(String s) {
        return UiKit.singleLine(UiKit.text(act, s, 14f, AppTheme.text(), UiKit.MEDIUM));
    }

    private TextView rowMeta(String s) {
        TextView t = UiKit.singleLine(UiKit.caption(act, s));
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private LinearLayout.LayoutParams textLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        lp.setMarginStart(dp(12));
        lp.setMarginEnd(dp(8));
        return lp;
    }

    private LinearLayout.LayoutParams posterLp(int wDp) {
        return new LinearLayout.LayoutParams(dp(wDp), dp(wDp * 1.5f));
    }

    private LinearLayout.LayoutParams fullLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private Switch switchRow(LinearLayout parent, String title, String caption, boolean checked) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(0, dp(10), 0, dp(4));
        LinearLayout tx = UiKit.column(act);
        tx.addView(UiKit.subtitle(act, title));
        if (caption != null && !caption.isEmpty()) {
            TextView c = UiKit.caption(act, caption);
            c.setPadding(0, dp(2), 0, 0);
            tx.addView(c);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginEnd(dp(12));
        row.addView(tx, tlp);
        Switch sw = new Switch(act);
        sw.setChecked(checked);
        UiStyle.styleSwitch(act, sw);
        row.addView(sw);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return sw;
    }

    // ================================================================== posters

    private static synchronized LruCache<String, Bitmap> cache() {
        if (posterCache == null) {
            long max = Math.min(Runtime.getRuntime().maxMemory() / 16, 16L * 1024 * 1024);
            posterCache = new LruCache<String, Bitmap>((int) Math.max(2L * 1024 * 1024, max)) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };
        }
        return posterCache;
    }

    private static String posterUrl(JSONObject movie) {
        JSONArray imgs = movie.optJSONArray("images");
        for (int i = 0; imgs != null && i < imgs.length(); i++) {
            JSONObject im = imgs.optJSONObject(i);
            if (im == null || !"poster".equals(im.optString("coverType"))) continue;
            String local = RadarrApi.str(im, "url");
            String remote = RadarrApi.str(im, "remoteUrl");
            // Library items have cached local covers; lookup results only have the TMDb URL.
            if (movie.optInt("id") > 0 && local.startsWith("/")) return local;
            if (!remote.isEmpty()) return remote;
            if (!local.isEmpty()) return local;
        }
        String remotePoster = RadarrApi.str(movie, "remotePoster");
        return remotePoster.isEmpty() ? null : remotePoster;
    }

    /** 2:3 poster, rounded 8dp, loaded off the UI thread into a bounded memory cache. */
    private ImageView poster(JSONObject movie, int wDp) {
        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(UiKit.shape(act, AppTheme.inner(), 0, 8));
        iv.setClipToOutline(true);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final String url = posterUrl(movie);
        if (url == null) return iv;
        final int px = dp(wDp);
        final String key = url + "@" + px;
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
                    try {
                        b = RadarrApi.get(act).loadPoster(url, px);
                    } catch (Throwable ignored) {
                        b = null;
                    }
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

    private static String titleYear(JSONObject m) {
        if (m == null) return "?";
        String t = m.optString("title", "?");
        int y = m.optInt("year");
        return y > 0 ? t + " (" + y + ")" : t;
    }

    private static String qualityName(JSONObject withQuality) {
        if (withQuality == null) return "";
        JSONObject q = withQuality.optJSONObject("quality");
        JSONObject qq = q == null ? null : q.optJSONObject("quality");
        return qq == null ? "" : RadarrApi.str(qq, "name");
    }

    private static String statusLabel(String s) {
        if ("tba".equalsIgnoreCase(s)) return "TBA";
        if ("announced".equals(s)) return "Announced";
        if ("inCinemas".equals(s)) return "In cinemas";
        if ("released".equals(s)) return "Released";
        if ("deleted".equals(s)) return "Removed from TMDb";
        return capitalize(s);
    }

    private static String availabilityLabel(String s) {
        for (int i = 0; i < AVAIL_VALUES.length; i++) if (AVAIL_VALUES[i].equals(s)) return AVAIL_LABELS[i];
        return statusLabel(s);
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String joinDot(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String joinSpace(String a, String b) {
        return ((a == null ? "" : a) + " " + (b == null ? "" : b)).trim();
    }

    private static JSONObject copyWith(JSONObject o, String key, Object value) {
        try {
            JSONObject c = new JSONObject(o.toString());
            c.put(key, value);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        double gb = bytes / 1073741824.0;
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        double mb = bytes / 1048576.0;
        if (mb >= 1) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    private static String fmtRuntime(int minutes) {
        if (minutes <= 0) return "";
        if (minutes < 60) return trf("{0}m", minutes);
        return trf("{0}h {1}m", minutes / 60, minutes % 60);
    }

    private static String fmtAge(double minutes) {
        if (minutes <= 0) return "";
        if (minutes < 60) return trf("{0}m", (int) minutes);
        if (minutes < 2880) return trf("{0}h", (int) (minutes / 60));
        return trf("{0}d", (int) (minutes / 1440));
    }

    /** "1.02:03:04" / "00:12:34" (TimeSpan) to "1d 2h" / "12m". */
    private static String fmtTimeLeft(String ts) {
        if (ts == null || ts.isEmpty()) return "";
        try {
            int days = 0;
            String rest = ts;
            int dot = ts.indexOf('.');
            int colon = ts.indexOf(':');
            if (dot > 0 && (colon < 0 || dot < colon)) {
                days = Integer.parseInt(ts.substring(0, dot));
                rest = ts.substring(dot + 1);
            }
            String[] p = rest.split(":");
            int h = p.length > 0 ? Integer.parseInt(p[0]) : 0;
            int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (days > 0) return trf("{0}d {1}h", days, h);
            if (h > 0) return trf("{0}h {1}m", h, m);
            if (m > 0) return trf("{0}m", m);
            return tr("<1m");
        } catch (Throwable t) {
            return "";
        }
    }

    /** ISO-8601 UTC ("2024-05-01T12:34:56Z", fractions allowed) to epoch ms, 0 when unparseable. */
    private static long parseIso(String iso) {
        if (iso == null || iso.length() < 19) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso.substring(0, 19));
            return d == null ? 0L : d.getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String fmtDateTime(String iso) {
        long ms = parseIso(iso);
        if (ms <= 0) return "";
        return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** Release dates are calendar days: format the yyyy-MM-dd part without shifting time zones. */
    private static String fmtDay(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = in.parse(iso.substring(0, 10));
            if (d == null) return "";
            return new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(d);
        } catch (Throwable t) {
            return "";
        }
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
