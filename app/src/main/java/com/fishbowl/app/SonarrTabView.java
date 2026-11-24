package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.services.AutomationPrefs;
import com.fishbowl.app.services.SonarrApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "Sonarr" tab: a native client for the local Sonarr (v3 API, served by Sonarr v3 and v4).
 *
 * <ul>
 *   <li>Series - library with filters; a series page with seasons / episodes, monitoring, search,
 *       interactive release search, refresh / rescan, file deletion; add and delete series.</li>
 *   <li>Calendar - recent and upcoming episodes (UTC range, grouped by local day).</li>
 *   <li>Queue - live downloads (polls only while on screen) with remove / blocklist.</li>
 *   <li>History - grabbed / imported / failed events.</li>
 *   <li>Wanted - monitored missing episodes, search one or all.</li>
 *   <li>System - status, health, disk space, root folders, profiles, indexers, clients, tasks.</li>
 *   <li>Auto Search - the periodic missing-episode search.</li>
 * </ul>
 */
public final class SonarrTabView {

    private static final int S_SERIES = 0;
    private static final int S_CALENDAR = 1;
    private static final int S_QUEUE = 2;
    private static final int S_HISTORY = 3;
    private static final int S_WANTED = 4;
    private static final int S_SYSTEM = 5;
    private static final int S_AUTO = 6;
    private static final String[] SECTION_NAMES = {"Series", "Calendar", "Queue", "History", "Wanted", "System", "Auto Search"};

    private static final String[] SERIES_FILTERS = {"All", "Monitored", "Missing", "Continuing", "Ended"};

    private static final int PAGE_SIZE = 40;
    private static final int SERIES_BATCH = 60;
    private static final long QUEUE_TICK_MS = 6000L;

    private interface Job<T> { T run(SonarrApi api) throws Exception; }
    private interface Done<T> { void run(T value); }
    private interface IntDone { void run(int value); }

    private final Activity act;
    private final LinearLayout root;
    private final TextView headerSub;
    private final TextView[] navChips = new TextView[SECTION_NAMES.length];
    private final ScrollView scroll;
    private final LinearLayout content;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    /** Slow calls (interactive search, add, delete) so they never block the screen loads. */
    private final ExecutorService slow = Executors.newSingleThreadExecutor();
    private final ExecutorService imgPool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> imgCache;
    private final Map<String, List<ImageView>> imgPending = new HashMap<>();
    private final Set<String> imgFailed = new HashSet<>();

    private int section = S_SERIES;
    private int page = 1;
    private int gen = 0;
    private int detailSeriesId = 0;
    private boolean tickerOn = false;
    private boolean pollPending = false;
    private boolean versionRequested = false;
    private String version = "";

    // series list
    private JSONArray seriesCache = null;
    private String seriesQuery = "";
    private int seriesFilter = 0;
    private int seriesLimit = SERIES_BATCH;
    private LinearLayout seriesShell = null;
    private LinearLayout seriesRows = null;
    private TextView seriesCount = null;
    private TextView[] seriesChips = null;
    private int styledSeriesFilter = -1;
    private Button moreButton = null;

    // series detail
    private final Set<Integer> expandedSeasons = new HashSet<>();
    private LinearLayout detailRoot = null;

    // queue
    private LinearLayout queueRows = null;
    private TextView queueCount = null;
    private final Map<Integer, QueueHolder> queueHolders = new HashMap<>();
    private final List<Integer> queueOrder = new ArrayList<>();
    private boolean failureShown = false;

    private final Runnable queueTicker = new Runnable() {
        @Override public void run() {
            if (!tickerOn) return;
            if (!wantTicker()) {
                stopTicker();
                return;
            }
            loadQueue(true);
            root.postDelayed(this, QUEUE_TICK_MS);
        }
    };

    public SonarrTabView(Activity activity) {
        this.act = activity;
        long maxKb = Runtime.getRuntime().maxMemory() / 1024L;
        int cacheKb = (int) Math.max(4096L, Math.min(maxKb / 16L, 24L * 1024L));
        imgCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override protected int sizeOf(String k, Bitmap b) {
                return Math.max(1, b.getByteCount() / 1024);
            }
        };

        root = new LinearLayout(act) {
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                syncTicker();
            }

            @Override protected void onDetachedFromWindow() {
                stopTicker();
                super.onDetachedFromWindow();
            }

            @Override protected void onVisibilityChanged(View changedView, int visibility) {
                super.onVisibilityChanged(changedView, visibility);
                syncTicker();
            }

            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                syncTicker();
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppTheme.bg());

        Button addBtn = compact(UiKit.outline(act, I18n.tr("Add Series"), v -> showAddDialog()));
        LinearLayout header = UiKit.header(act, "Sonarr", I18n.tr("TV series"), addBtn);
        header.setPadding(dp(16), dp(12), dp(16), dp(10));
        ViewGroup.LayoutParams alp = addBtn.getLayoutParams();
        if (alp != null) {
            alp.height = dp(34);
            addBtn.setLayoutParams(alp);
        }
        headerSub = UiKit.singleLine(findSubtitle(header));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout chips = UiKit.row(act);
        chips.setPadding(dp(16), 0, dp(16), dp(10));
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int idx = i;
            TextView c = UiKit.chip(act, I18n.tr(SECTION_NAMES[i]), i == section);
            c.setOnClickListener(v -> go(idx));
            navChips[i] = c;
            chips.addView(c, UiKit.chipLp(act));
        }
        HorizontalScrollView navScroll = UiKit.chipScroller(act, chips);
        root.addView(navScroll, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        content = UiKit.column(act);
        content.setPadding(dp(16), dp(2), dp(16), dp(24));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
    }

    public View getView() { return root; }

    /** Returns true when the back press was consumed. */
    public boolean onBack() {
        if (detailSeriesId > 0) {
            detailSeriesId = 0;
            detailRoot = null;
            refresh();
            return true;
        }
        if ((section == S_WANTED || section == S_HISTORY) && page > 1) {
            page--;
            scroll.scrollTo(0, 0);
            refresh();
            return true;
        }
        if (section != S_SERIES) {
            go(S_SERIES);
            return true;
        }
        return false;
    }

    public void refresh() {
        gen++;
        renderNav();
        requestVersion();
        if (detailSeriesId > 0) {
            loadDetail(detailRoot != null && detailRoot.getParent() == content);
        } else {
            switch (section) {
                case S_SERIES: loadSeries(); break;
                case S_CALENDAR: loadCalendar(); break;
                case S_QUEUE: loadQueue(false); break;
                case S_HISTORY: loadHistory(); break;
                case S_WANTED: loadWanted(); break;
                case S_SYSTEM: loadSystem(); break;
                default: renderAuto(); break;
            }
        }
        syncTicker();
    }

    private void go(int s) {
        section = s;
        page = 1;
        detailSeriesId = 0;
        detailRoot = null;
        scroll.scrollTo(0, 0);
        refresh();
    }

    private void renderNav() {
        for (int i = 0; i < navChips.length; i++) UiKit.styleChip(act, navChips[i], i == section);
    }

    // ================================================================== queue ticker

    private boolean wantTicker() {
        return root != null && section == S_QUEUE && detailSeriesId == 0 && root.isAttachedToWindow()
                && root.getWindowVisibility() == View.VISIBLE && root.isShown();
    }

    private void syncTicker() {
        if (root == null) return;
        if (wantTicker()) {
            if (!tickerOn) {
                tickerOn = true;
                root.removeCallbacks(queueTicker);
                root.postDelayed(queueTicker, QUEUE_TICK_MS);
            }
        } else {
            stopTicker();
        }
    }

    private void stopTicker() {
        tickerOn = false;
        if (root != null) root.removeCallbacks(queueTicker);
    }

    // ================================================================== async helpers

    private boolean dead() {
        return act.isFinishing() || act.isDestroyed();
    }

    private <T> void async(ExecutorService ex, final Job<T> job, final Done<T> ok, final Done<Throwable> err) {
        try {
            ex.execute(() -> {
                T result = null;
                Throwable failure = null;
                try {
                    result = job.run(SonarrApi.get(act));
                } catch (Throwable t) {
                    failure = t;
                }
                final T r = result;
                final Throwable f = failure;
                act.runOnUiThread(() -> {
                    if (dead()) return;
                    if (f == null) {
                        if (ok != null) ok.run(r);
                    } else if (err != null) {
                        err.run(f);
                    } else {
                        toast(msg(f));
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    /** Runs a command with its button disabled and toasts the outcome. */
    private void runAction(final View button, final Job<Object> job, final String okMsg, final Runnable after) {
        setBusy(button, true);
        async(io, job, v -> {
            setBusy(button, false);
            if (okMsg != null) toast(okMsg);
            if (after != null) after.run();
        }, t -> {
            setBusy(button, false);
            toast(msg(t));
        });
    }

    private static void setBusy(View b, boolean busy) {
        if (b == null) return;
        b.setEnabled(!busy);
        b.setAlpha(busy ? 0.5f : 1f);
    }

    private static String msg(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.trim().isEmpty() ? "Unknown error." : m;
    }

    private void toast(String s) {
        if (s == null || dead()) return;
        Toast.makeText(act, I18n.tr(s), s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private void requestVersion() {
        if (versionRequested) return;
        versionRequested = true;
        async(io, api -> {
            String v = api.status().optString("version", "");
            return v;
        }, v -> {
            version = v == null ? "" : v.trim();
            updateHeader();
        }, t -> versionRequested = false);
    }

    private void updateHeader() {
        StringBuilder sb = new StringBuilder();
        if (!version.isEmpty()) sb.append('v').append(version);
        if (seriesCache != null) {
            if (sb.length() > 0) sb.append(" · ");
            int n = seriesCache.length();
            sb.append(n == 1 ? I18n.tr("1 series") : tf("{0} series", n));
        }
        setText(headerSub, sb.length() == 0 ? I18n.tr("TV series") : sb.toString());
    }

    // ================================================================== screen states

    private void clearRefs() {
        seriesShell = null;
        seriesRows = null;
        seriesCount = null;
        seriesChips = null;
        styledSeriesFilter = -1;
        moreButton = null;
        queueRows = null;
        queueCount = null;
        queueHolders.clear();
        queueOrder.clear();
        failureShown = false;
    }

    private void showState(View v) {
        content.removeAllViews();
        clearRefs();
        content.addView(v);
    }

    private void showLoading(String what) {
        showState(UiKit.loading(act, I18n.tr(what)));
    }

    private View failureView(Throwable t, String what) {
        if (SonarrApi.isNotRunning(t)) {
            return UiKit.error(act, I18n.tr("Sonarr is not running"),
                    I18n.tr("Start it from the Services tab, then retry."), I18n.tr("Retry"), x -> refresh());
        }
        if (SonarrApi.isNoKey(t)) {
            return UiKit.error(act, I18n.tr("Sonarr is not set up yet"), I18n.tr(SonarrApi.NO_KEY), I18n.tr("Retry"), x -> refresh());
        }
        if (SonarrApi.isAuthError(t)) {
            return UiKit.error(act, I18n.tr("Sonarr rejected the API key"), I18n.tr(msg(t)), I18n.tr("Retry"), x -> refresh());
        }
        return UiKit.error(act, I18n.tr(what), I18n.tr(msg(t)), I18n.tr("Retry"), x -> refresh());
    }

    private void showFailure(Throwable t, String what) {
        showState(failureView(t, what));
        failureShown = true;
        if (SonarrApi.isNotRunning(t)) setText(headerSub, I18n.tr("Not running"));
    }

    // ================================================================== SERIES list

    private void loadSeries() {
        final int g = gen;
        if (seriesCache != null) {
            if (seriesShell == null || seriesShell.getParent() != content) renderSeriesList();
        } else {
            showLoading("Loading series…");
        }
        async(io, api -> api.series(), arr -> {
            if (g != gen || detailSeriesId > 0 || section != S_SERIES) return;
            seriesCache = arr;
            updateHeader();
            if (seriesShell == null || seriesShell.getParent() != content) renderSeriesList();
            else fillSeriesRows();
        }, t -> {
            if (g != gen || detailSeriesId > 0 || section != S_SERIES) return;
            if (seriesCache == null) showFailure(t, "Could not load series");
            else toast(msg(t));
        });
    }

    private void renderSeriesList() {
        content.removeAllViews();
        clearRefs();
        seriesShell = UiKit.column(act);

        final EditText filter = UiKit.input(act, I18n.tr("Filter series"));
        filter.setText(seriesQuery);
        filter.setSelection(filter.getText().length());
        seriesShell.addView(filter, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout chips = UiKit.row(act);
        chips.setPadding(0, dp(12), 0, dp(4));
        seriesChips = new TextView[SERIES_FILTERS.length];
        for (int i = 0; i < SERIES_FILTERS.length; i++) {
            final int fi = i;
            TextView c = UiKit.chip(act, I18n.tr(SERIES_FILTERS[i]), i == seriesFilter);
            c.setOnClickListener(v -> {
                seriesFilter = fi;
                seriesLimit = SERIES_BATCH;
                scroll.scrollTo(0, 0);
                fillSeriesRows();
            });
            seriesChips[i] = c;
            chips.addView(c, UiKit.chipLp(act));
        }
        styledSeriesFilter = seriesFilter;
        seriesShell.addView(UiKit.chipScroller(act, chips), new LinearLayout.LayoutParams(-1, -2));

        seriesCount = UiKit.overline(act, "");
        seriesCount.setPadding(0, dp(12), 0, dp(8));
        seriesShell.addView(seriesCount);

        seriesRows = UiKit.card(act);
        seriesRows.setPadding(dp(4), dp(4), dp(4), dp(4));
        seriesShell.addView(seriesRows);

        moreButton = UiKit.outline(act, I18n.tr("Show More"), v -> {
            seriesLimit += SERIES_BATCH;
            fillSeriesRows();
        });
        seriesShell.addView(moreButton, new LinearLayout.LayoutParams(-1, dp(42)));

        content.addView(seriesShell, new LinearLayout.LayoutParams(-1, -2));
        fillSeriesRows();

        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                seriesQuery = e.toString();
                seriesLimit = SERIES_BATCH;
                fillSeriesRows();
            }
        });
    }

    private static int missingCount(JSONObject s) {
        JSONObject st = s.optJSONObject("statistics");
        if (st == null) return 0;
        return Math.max(0, st.optInt("episodeCount") - st.optInt("episodeFileCount"));
    }

    private static boolean matchesSeries(JSONObject s, int f) {
        String status = s.optString("status", "");
        switch (f) {
            case 1: return s.optBoolean("monitored");
            case 2: return s.optBoolean("monitored") && missingCount(s) > 0;
            case 3: return "continuing".equals(status) || "upcoming".equals(status);
            case 4: return "ended".equals(status);
            default: return true;
        }
    }

    private void fillSeriesRows() {
        if (seriesRows == null || seriesCache == null) return;
        final int y = scroll.getScrollY();
        int[] counts = new int[SERIES_FILTERS.length];
        List<JSONObject> list = new ArrayList<>();
        String q = seriesQuery.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < seriesCache.length(); i++) {
            JSONObject s = seriesCache.optJSONObject(i);
            if (s == null) continue;
            for (int f = 0; f < counts.length; f++) if (matchesSeries(s, f)) counts[f]++;
            if (!matchesSeries(s, seriesFilter)) continue;
            if (!q.isEmpty() && !s.optString("title", "").toLowerCase(Locale.ROOT).contains(q)) continue;
            list.add(s);
        }
        if (seriesChips != null) {
            for (int f = 0; f < seriesChips.length; f++) setText(seriesChips[f], I18n.tr(SERIES_FILTERS[f]) + "  " + counts[f]);
            if (styledSeriesFilter != seriesFilter) {
                for (int f = 0; f < seriesChips.length; f++) UiKit.styleChip(act, seriesChips[f], f == seriesFilter);
                styledSeriesFilter = seriesFilter;
            }
        }
        Collections.sort(list, (a, b) -> a.optString("sortTitle", a.optString("title", ""))
                .compareToIgnoreCase(b.optString("sortTitle", b.optString("title", ""))));
        setText(seriesCount, tf("{0} of {1} series", list.size(), seriesCache.length()));

        seriesRows.removeAllViews();
        if (list.isEmpty()) {
            if (seriesCache.length() == 0) {
                LinearLayout box = UiKit.column(act);
                box.addView(UiKit.empty(act, I18n.tr("No series yet"), I18n.tr("Add a show and Sonarr will find its episodes.")));
                Button add = UiKit.tonal(act, I18n.tr("Add Series"), v -> showAddDialog());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(42));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(20);
                box.addView(add, lp);
                seriesRows.addView(box, new LinearLayout.LayoutParams(-1, -2));
            } else {
                seriesRows.addView(UiKit.empty(act, I18n.tr("No matches"), I18n.tr("Try another filter or search term.")),
                        new LinearLayout.LayoutParams(-1, -2));
            }
            if (moreButton != null) moreButton.setVisibility(View.GONE);
            return;
        }
        int shown = Math.min(list.size(), seriesLimit);
        for (int i = 0; i < shown; i++) {
            if (i > 0) seriesRows.addView(listDivider());
            seriesRows.addView(seriesRow(list.get(i)), new LinearLayout.LayoutParams(-1, -2));
        }
        if (moreButton != null) {
            moreButton.setVisibility(shown < list.size() ? View.VISIBLE : View.GONE);
            setText(moreButton, tf("Show More ({0} left)", list.size() - shown));
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private View seriesRow(final JSONObject s) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(dp(8), dp(10), dp(12), dp(10));
        row.setBackground(UiKit.rowRipple(act));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(poster(s, 40, 60), new LinearLayout.LayoutParams(dp(40), dp(60)));

        LinearLayout tx = UiKit.column(act);
        LinearLayout top = UiKit.row(act);
        TextView t = UiKit.singleLine(UiKit.text(act, s.optString("title", "?"), 14f, AppTheme.text(), UiKit.MEDIUM));
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0f));
        boolean monitored = s.optBoolean("monitored");
        int missing = missingCount(s);
        View badge = null;
        if (!monitored) badge = UiKit.badge(act, I18n.tr("Unmonitored"), AppTheme.textMuted());
        else if (missing > 0) badge = UiKit.badge(act, tf("{0} missing", missing), AppTheme.warning());
        if (badge != null) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.setMarginStart(dp(8));
            top.addView(badge, blp);
        }
        tx.addView(top);

        JSONObject st = s.optJSONObject("statistics");
        int files = st == null ? 0 : st.optInt("episodeFileCount");
        int eps = st == null ? 0 : st.optInt("episodeCount");
        int seasons = st != null ? st.optInt("seasonCount") : (s.optJSONArray("seasons") == null ? 0 : s.optJSONArray("seasons").length());
        long size = st == null ? 0 : st.optLong("sizeOnDisk");
        StringBuilder l1 = new StringBuilder();
        if (s.optInt("year") > 0) l1.append(s.optInt("year"));
        appendDot(l1, I18n.tr(capitalize(s.optString("status", ""))));
        appendDot(l1, s.optString("network", ""));
        TextView c1 = UiKit.singleLine(UiKit.caption(act, l1.toString()));
        c1.setPadding(0, dp(2), 0, 0);
        tx.addView(c1);
        String l2 = (seasons == 1 ? I18n.tr("1 season") : tf("{0} seasons", seasons))
                + " · " + tf("{0}/{1} episodes", files, eps) + (size > 0 ? " · " + fmtSize(size) : "");
        TextView c2 = UiKit.singleLine(UiKit.caption(act, l2));
        tx.addView(c2);
        if (eps > 0) {
            int pct = (int) Math.round(files * 100.0 / eps);
            int color = !monitored ? AppTheme.textMuted() : files >= eps ? AppTheme.success() : AppTheme.accent();
            tx.addView(UiKit.progress(act, pct, color));
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginStart(dp(12));
        row.addView(tx, tlp);

        row.setOnClickListener(v -> openSeries(s.optInt("id")));
        return row;
    }

    private void openSeries(int id) {
        if (id <= 0) return;
        detailSeriesId = id;
        detailRoot = null;
        expandedSeasons.clear();
        scroll.scrollTo(0, 0);
        refresh();
    }

    // ================================================================== SERIES detail

    private void loadDetail(final boolean keepScroll) {
        final int g = gen;
        final int id = detailSeriesId;
        if (!keepScroll) showLoading("Loading series…");
        async(io, api -> {
            JSONObject pack = new JSONObject();
            pack.put("series", api.series(id));
            pack.put("episodes", api.episodes(id));
            return pack;
        }, pack -> {
            if (g != gen || detailSeriesId != id) return;
            JSONObject series = pack.optJSONObject("series");
            JSONArray eps = pack.optJSONArray("episodes");
            if (series == null) {
                showFailure(new Exception("Series not found."), "Could not load the series");
                return;
            }
            final int y = scroll.getScrollY();
            renderDetail(series, eps == null ? new JSONArray() : eps);
            if (keepScroll) scroll.post(() -> scroll.scrollTo(0, y));
        }, t -> {
            if (g != gen || detailSeriesId != id) return;
            if (keepScroll && detailRoot != null && detailRoot.getParent() == content) toast(msg(t));
            else showFailure(t, "Could not load the series");
        });
    }

    /** Reloads the open series in place (after monitor changes, deletions...). */
    private void reloadDetail() {
        if (detailSeriesId <= 0) return;
        gen++;
        loadDetail(detailRoot != null && detailRoot.getParent() == content);
    }

    private void renderDetail(final JSONObject series, final JSONArray episodes) {
        content.removeAllViews();
        clearRefs();
        final int id = series.optInt("id");
        detailRoot = UiKit.column(act);

        Button back = compact(UiKit.outline(act, I18n.tr("Back"), v -> onBack()));
        LinearLayout.LayoutParams bk = new LinearLayout.LayoutParams(-2, dp(34));
        bk.bottomMargin = dp(12);
        detailRoot.addView(back, bk);

        LinearLayout card = UiKit.card(act);
        LinearLayout head = UiKit.row(act);
        head.setGravity(Gravity.TOP);
        head.addView(poster(series, 92, 138), new LinearLayout.LayoutParams(dp(92), dp(138)));
        LinearLayout info = UiKit.column(act);
        TextView title = UiKit.title(act, series.optString("title", "?"));
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        StringBuilder meta = new StringBuilder();
        if (series.optInt("year") > 0) meta.append(series.optInt("year"));
        appendDot(meta, I18n.tr(capitalize(series.optString("status", ""))));
        appendDot(meta, series.optString("network", ""));
        if (series.optInt("runtime") > 0) appendDot(meta, tf("{0} min", series.optInt("runtime")));
        TextView m = UiKit.caption(act, meta.toString());
        m.setPadding(0, dp(4), 0, 0);
        info.addView(m);
        JSONObject st = series.optJSONObject("statistics");
        if (st != null) {
            TextView s2 = UiKit.caption(act, tf("{0}/{1} episodes", st.optInt("episodeFileCount"), st.optInt("episodeCount"))
                    + (st.optLong("sizeOnDisk") > 0 ? " · " + fmtSize(st.optLong("sizeOnDisk")) : ""));
            s2.setPadding(0, dp(2), 0, 0);
            info.addView(s2);
        }
        int missing = missingCount(series);
        if (!series.optBoolean("monitored")) {
            info.addView(spaced(UiKit.badge(act, I18n.tr("Unmonitored"), AppTheme.textMuted())));
        } else if (missing > 0) {
            info.addView(spaced(UiKit.badge(act, tf("{0} missing", missing), AppTheme.warning())));
        } else if (st != null && st.optInt("episodeCount") > 0) {
            info.addView(spaced(UiKit.badge(act, I18n.tr("Complete"), AppTheme.success())));
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        ilp.setMarginStart(dp(14));
        head.addView(info, ilp);
        card.addView(head);

        String ov = series.optString("overview", "");
        if (!ov.isEmpty()) {
            final TextView o = UiKit.body(act, ov);
            o.setMaxLines(4);
            o.setEllipsize(TextUtils.TruncateAt.END);
            o.setPadding(0, dp(12), 0, 0);
            o.setOnClickListener(v -> {
                boolean collapsed = o.getMaxLines() == 4;
                o.setMaxLines(collapsed ? Integer.MAX_VALUE : 4);
            });
            card.addView(o);
        }
        String path = series.optString("path", "");
        if (!path.isEmpty()) {
            TextView p = UiKit.mono(act, path);
            p.setTextSize(11.5f);
            p.setTextColor(AppTheme.textMuted());
            p.setPadding(0, dp(10), 0, 0);
            card.addView(p);
        }

        final Switch monSw = switchRow(card, "Monitored", "Sonarr searches for and grabs monitored episodes.", series.optBoolean("monitored"));
        final boolean[] binding = {false};
        monSw.setOnCheckedChangeListener((b, checked) -> {
            if (binding[0]) return;
            final JSONObject copy;
            try {
                copy = new JSONObject(series.toString());
                copy.put("monitored", checked);
            } catch (Exception e) {
                toast(msg(e));
                return;
            }
            monSw.setEnabled(false);
            async(io, api -> { api.putSeries(copy); return null; }, r -> {
                monSw.setEnabled(true);
                seriesCache = null;
                toast(checked ? "Monitoring on." : "Monitoring off.");
                reloadDetail();
            }, e -> {
                monSw.setEnabled(true);
                binding[0] = true;
                monSw.setChecked(!checked);
                binding[0] = false;
                toast(msg(e));
            });
        });

        Button search = UiKit.primary(act, I18n.tr("Search"), v ->
                runAction(v, api -> { api.searchSeries(id); return null; }, "Search started for monitored episodes.", null));
        Button refreshBtn = UiKit.outline(act, I18n.tr("Refresh"), v ->
                runAction(v, api -> { api.refreshSeries(id); return null; }, "Refresh queued.", null));
        Button rescan = UiKit.outline(act, I18n.tr("Rescan"), v ->
                runAction(v, api -> { api.rescanSeries(id); return null; }, "Disk rescan queued.", null));
        card.addView(UiKit.buttonRow(act, search, refreshBtn, rescan));
        Button del = UiKit.danger(act, I18n.tr("Delete Series"), v -> confirmDeleteSeries(series));
        card.addView(UiKit.buttonRow(act, del));
        detailRoot.addView(card);

        // seasons, newest first
        JSONArray seasons = series.optJSONArray("seasons");
        TreeMap<Integer, JSONObject> seasonMap = new TreeMap<>(Collections.<Integer>reverseOrder());
        for (int i = 0; seasons != null && i < seasons.length(); i++) {
            JSONObject s = seasons.optJSONObject(i);
            if (s != null) seasonMap.put(s.optInt("seasonNumber"), s);
        }
        Map<Integer, List<JSONObject>> bySeason = new HashMap<>();
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject e = episodes.optJSONObject(i);
            if (e == null) continue;
            int sn = e.optInt("seasonNumber");
            List<JSONObject> l = bySeason.get(sn);
            if (l == null) {
                l = new ArrayList<>();
                bySeason.put(sn, l);
            }
            l.add(e);
            if (!seasonMap.containsKey(sn)) seasonMap.put(sn, new JSONObject());
        }
        if (!seasonMap.isEmpty()) {
            TextView ol = UiKit.overline(act, I18n.tr("Seasons"));
            ol.setPadding(dp(4), dp(4), 0, dp(8));
            detailRoot.addView(ol);
        }
        for (Map.Entry<Integer, JSONObject> en : seasonMap.entrySet()) {
            List<JSONObject> eps = bySeason.get(en.getKey());
            detailRoot.addView(seasonCard(series, en.getKey(), en.getValue(), eps == null ? new ArrayList<JSONObject>() : eps));
        }
        content.addView(detailRoot, new LinearLayout.LayoutParams(-1, -2));
    }

    private View spaced(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private View seasonCard(final JSONObject series, final int seasonNo, final JSONObject seasonObj, final List<JSONObject> eps) {
        final int id = series.optInt("id");
        final LinearLayout card = UiKit.card(act);
        int have = 0;
        long bytes = 0;
        int aired = 0;
        long now = System.currentTimeMillis();
        for (JSONObject e : eps) {
            if (e.optBoolean("hasFile")) have++;
            JSONObject f = e.optJSONObject("episodeFile");
            if (f != null) bytes += f.optLong("size");
            long air = parseUtc(e.optString("airDateUtc", ""));
            if (e.optBoolean("hasFile") || (air > 0 && air <= now)) aired++;
        }
        final String label = seasonNo == 0 ? I18n.tr("Specials") : tf("Season {0}", seasonNo);

        LinearLayout top = UiKit.row(act);
        LinearLayout tx = UiKit.column(act);
        tx.addView(UiKit.subtitle(act, label));
        TextView cap = UiKit.caption(act, tf("{0} of {1} episodes", have, eps.size()) + (bytes > 0 ? " · " + fmtSize(bytes) : ""));
        cap.setPadding(0, dp(2), 0, 0);
        tx.addView(cap);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginEnd(dp(12));
        top.addView(tx, tlp);
        final Switch sw = new Switch(act);
        sw.setChecked(seasonObj.optBoolean("monitored"));
        UiStyle.styleSwitch(act, sw);
        top.addView(sw);
        card.addView(top);
        if (aired > 0) {
            int pct = (int) Math.round(have * 100.0 / aired);
            card.addView(UiKit.progress(act, pct, !seasonObj.optBoolean("monitored") ? AppTheme.textMuted()
                    : have >= aired ? AppTheme.success() : AppTheme.accent()));
        }

        final boolean[] binding = {false};
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (binding[0]) return;
            final JSONObject copy;
            try {
                copy = new JSONObject(series.toString());
                JSONArray ss = copy.optJSONArray("seasons");
                for (int i = 0; ss != null && i < ss.length(); i++) {
                    JSONObject so = ss.optJSONObject(i);
                    if (so != null && so.optInt("seasonNumber") == seasonNo) so.put("monitored", checked);
                }
            } catch (Exception e) {
                toast(msg(e));
                return;
            }
            sw.setEnabled(false);
            async(io, api -> { api.putSeries(copy); return null; }, r -> {
                sw.setEnabled(true);
                seriesCache = null;
                toast(checked ? "Season monitored." : "Season unmonitored.");
                reloadDetail();
            }, e -> {
                sw.setEnabled(true);
                binding[0] = true;
                sw.setChecked(!checked);
                binding[0] = false;
                toast(msg(e));
            });
        });

        Button search = UiKit.outline(act, I18n.tr("Search"), v ->
                runAction(v, api -> { api.searchSeason(id, seasonNo); return null; }, "Season search started.", null));
        Button releases = UiKit.outline(act, I18n.tr("Releases"), v ->
                showReleases(seasonNo, 0, id, series.optString("title", "") + " · " + label));
        final Button toggle = UiKit.tonal(act, I18n.tr(expandedSeasons.contains(seasonNo) ? "Hide" : "Episodes"), null);
        card.addView(UiKit.buttonRow(act, search, releases, toggle));

        final LinearLayout list = UiKit.column(act);
        card.addView(list, new LinearLayout.LayoutParams(-1, -2));
        final Runnable fill = () -> {
            list.removeAllViews();
            if (!expandedSeasons.contains(seasonNo)) return;
            List<JSONObject> sorted = new ArrayList<>(eps);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.optInt("episodeNumber"), a.optInt("episodeNumber")));
            list.addView(UiKit.space(act, 8));
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) list.addView(listDivider());
                list.addView(episodeRow(series, sorted.get(i)), new LinearLayout.LayoutParams(-1, -2));
            }
        };
        toggle.setOnClickListener(v -> {
            if (expandedSeasons.contains(seasonNo)) expandedSeasons.remove(seasonNo);
            else expandedSeasons.add(seasonNo);
            toggle.setText(I18n.tr(expandedSeasons.contains(seasonNo) ? "Hide" : "Episodes"));
            fill.run();
        });
        fill.run();
        return card;
    }

    private static String episodeCode(JSONObject e) {
        return String.format(Locale.US, "S%02dE%02d", e.optInt("seasonNumber"), e.optInt("episodeNumber"));
    }

    private View episodeRow(final JSONObject series, final JSONObject e) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(dp(8), dp(10), dp(4), dp(10));
        row.setBackground(UiKit.rowRipple(act));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout tx = UiKit.column(act);
        String et = e.optString("title", "");
        tx.addView(UiKit.singleLine(UiKit.text(act, e.optInt("episodeNumber") + " · " + (et.isEmpty() ? I18n.tr("TBA") : et),
                14f, AppTheme.text(), UiKit.MEDIUM)));
        TextView status = UiKit.singleLine(UiKit.caption(act, ""));
        status.setPadding(0, dp(2), 0, 0);
        bindEpisodeStatus(status, e);
        tx.addView(status);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginEnd(dp(8));
        row.addView(tx, tlp);

        final Switch sw = new Switch(act);
        sw.setChecked(e.optBoolean("monitored"));
        UiStyle.styleSwitch(act, sw);
        row.addView(sw);
        final int epId = e.optInt("id");
        final boolean[] binding = {false};
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (binding[0]) return;
            sw.setEnabled(false);
            async(io, api -> { api.monitorEpisodes(new int[]{epId}, checked); return null; }, r -> {
                sw.setEnabled(true);
                try { e.put("monitored", checked); } catch (Exception ignored) { }
            }, ex -> {
                sw.setEnabled(true);
                binding[0] = true;
                sw.setChecked(!checked);
                binding[0] = false;
                toast(msg(ex));
            });
        });
        row.setOnClickListener(v -> showEpisodeActions(series, e));
        return row;
    }

    private void bindEpisodeStatus(TextView status, JSONObject e) {
        boolean hasFile = e.optBoolean("hasFile");
        long air = parseUtc(e.optString("airDateUtc", ""));
        JSONObject file = e.optJSONObject("episodeFile");
        String s;
        int color;
        if (hasFile) {
            String q = qualityName(file == null ? null : file.optJSONObject("quality"));
            s = I18n.tr("Downloaded") + (q.isEmpty() ? "" : " · " + q) + (file != null && file.optLong("size") > 0 ? " · " + fmtSize(file.optLong("size")) : "");
            color = AppTheme.success();
        } else if (air > System.currentTimeMillis()) {
            s = tf("Airs {0}", fmtLocal(air));
            color = AppTheme.textMuted();
        } else if (air <= 0 && e.optString("airDate", "").isEmpty()) {
            s = I18n.tr("Air date unknown");
            color = AppTheme.textMuted();
        } else if (!e.optBoolean("monitored")) {
            s = I18n.tr("Unmonitored") + (air > 0 ? " · " + tf("Aired {0}", fmtLocalDate(air)) : "");
            color = AppTheme.textMuted();
        } else {
            s = I18n.tr("Missing") + (air > 0 ? " · " + tf("Aired {0}", fmtLocalDate(air)) : "");
            color = AppTheme.warning();
        }
        status.setText(s);
        status.setTextColor(color);
    }

    private void showEpisodeActions(final JSONObject series, final JSONObject e) {
        final int seriesId = series.optInt("id");
        final int epId = e.optInt("id");
        final String code = episodeCode(e);
        final Sheet s = new Sheet(code + " · " + e.optString("title", ""));
        TextView cap = UiKit.caption(act, "");
        bindEpisodeStatus(cap, e);
        s.box.addView(cap);
        String ov = e.optString("overview", "");
        if (!ov.isEmpty()) {
            TextView o = UiKit.body(act, ov);
            o.setPadding(0, dp(8), 0, 0);
            s.box.addView(o);
        }
        Button search = UiKit.primary(act, I18n.tr("Search"), v ->
                runAction(v, api -> { api.searchEpisode(epId); return null; }, "Episode search started.", s::dismiss));
        Button releases = UiKit.outline(act, I18n.tr("Releases"), v -> {
            s.dismiss();
            showReleases(-1, epId, seriesId, series.optString("title", "") + " · " + code);
        });
        s.box.addView(UiKit.buttonRow(act, search, releases));
        final JSONObject file = e.optJSONObject("episodeFile");
        if (e.optBoolean("hasFile") && file != null && file.optInt("id") > 0) {
            final int fileId = file.optInt("id");
            String rel = file.optString("relativePath", "");
            if (!rel.isEmpty()) {
                TextView p = UiKit.mono(act, rel);
                p.setTextSize(11.5f);
                p.setTextColor(AppTheme.textMuted());
                p.setPadding(0, dp(12), 0, 0);
                s.box.addView(p);
            }
            Button del = UiKit.danger(act, I18n.tr("Delete File"), v -> {
                s.dismiss();
                confirm("Delete file?", "Delete the downloaded file of this episode from disk? This cannot be undone.", "Delete", true, () ->
                        runAction(null, api -> { api.deleteEpisodeFile(fileId); return null; }, "File deleted.", this::reloadDetail));
            });
            s.box.addView(UiKit.buttonRow(act, del));
        }
        s.show();
    }

    private void confirmDeleteSeries(final JSONObject series) {
        final Sheet s = new Sheet("Delete series");
        s.box.addView(UiKit.subtitle(act, series.optString("title", "")));
        TextView hint = UiKit.body(act, I18n.tr("The series is removed from Sonarr. Its files stay on disk unless you choose to delete them."));
        hint.setPadding(0, dp(6), 0, 0);
        s.box.addView(hint);
        final Switch files = switchRow(s.box, "Also delete the files on disk", null, false);
        Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        Button del = UiKit.danger(act, I18n.tr("Delete"), v -> {
            final int id = series.optInt("id");
            final boolean df = files.isChecked();
            s.dismiss();
            Runnable go = () -> async(slow, api -> { api.deleteSeries(id, df); return null; }, r -> {
                toast("Series deleted.");
                seriesCache = null;
                detailSeriesId = 0;
                detailRoot = null;
                refresh();
            }, t -> toast(msg(t)));
            if (df) confirm("Delete files?", "Permanently delete every file of this series from disk? This cannot be undone.", "Delete", true, go);
            else go.run();
        });
        s.box.addView(UiKit.buttonRow(act, cancel, del));
        s.show();
    }

    // ================================================================== interactive search

    /** seasonNo >= 0 with episodeId == 0 searches a season pack; otherwise one episode. */
    private void showReleases(final int seasonNo, final int episodeId, final int seriesId, String title) {
        final Sheet s = new Sheet("Interactive search");
        TextView sub = UiKit.caption(act, title);
        sub.setPadding(0, 0, 0, dp(8));
        s.box.addView(sub);
        final LinearLayout list = UiKit.column(act);
        list.addView(UiKit.loading(act, I18n.tr("Searching indexers… this can take a minute.")));
        s.box.addView(list, new LinearLayout.LayoutParams(-1, -2));
        s.show();
        async(slow, api -> episodeId > 0 ? api.releasesForEpisode(episodeId) : api.releasesForSeason(seriesId, seasonNo), arr -> {
            if (!s.dialog.isShowing()) return;
            list.removeAllViews();
            List<JSONObject> rel = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) rel.add(r);
            }
            if (rel.isEmpty()) {
                list.addView(UiKit.empty(act, I18n.tr("No releases found"), I18n.tr("Check that Prowlarr has working indexers.")));
                return;
            }
            Collections.sort(rel, (a, b) -> {
                boolean ra = isRejected(a);
                boolean rb = isRejected(b);
                if (ra != rb) return ra ? 1 : -1;
                int wa = a.optInt("releaseWeight", Integer.MAX_VALUE);
                int wb = b.optInt("releaseWeight", Integer.MAX_VALUE);
                if (wa != wb) return Integer.compare(wa, wb);
                return Integer.compare(b.optInt("seeders"), a.optInt("seeders"));
            });
            TextView count = UiKit.overline(act, rel.size() == 1 ? I18n.tr("1 release") : tf("{0} releases", rel.size()));
            list.addView(count);
            int n = 0;
            for (JSONObject r : rel) {
                if (++n > 80) break;
                if (n > 1) list.addView(listDivider());
                list.addView(releaseRow(r, s), new LinearLayout.LayoutParams(-1, -2));
            }
        }, t -> {
            if (!s.dialog.isShowing()) return;
            list.removeAllViews();
            list.addView(failureViewInline(t));
        });
    }

    private View failureViewInline(Throwable t) {
        LinearLayout box = UiKit.column(act);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(UiKit.shape(act, AppTheme.dangerContainer(), 0, UiKit.R_INNER));
        TextView m = UiKit.body(act, I18n.tr(msg(t)));
        m.setTextColor(AppTheme.text());
        box.addView(m);
        return box;
    }

    private static boolean isRejected(JSONObject r) {
        if (r.has("approved")) return !r.optBoolean("approved", true);
        JSONArray rej = r.optJSONArray("rejections");
        return rej != null && rej.length() > 0;
    }

    private static String qualityName(JSONObject q) {
        JSONObject qq = q == null ? null : q.optJSONObject("quality");
        return qq == null ? "" : qq.optString("name", "");
    }

    private View releaseRow(final JSONObject r, final Sheet sheet) {
        LinearLayout box = UiKit.column(act);
        box.setPadding(dp(4), dp(10), dp(4), dp(10));
        TextView t = UiKit.text(act, r.optString("title", "?"), 13.5f, AppTheme.text(), UiKit.MEDIUM);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        String proto = r.optString("protocol", "");
        StringBuilder line = new StringBuilder();
        String q = qualityName(r.optJSONObject("quality"));
        if (!q.isEmpty()) line.append(q);
        appendDot(line, fmtSize(r.optLong("size")));
        appendDot(line, r.optString("indexer", ""));
        if ("torrent".equalsIgnoreCase(proto)) appendDot(line, tf("S {0} / L {1}", r.optInt("seeders"), r.optInt("leechers")));
        appendDot(line, fmtAge(r.optDouble("ageMinutes", r.optInt("age") * 1440.0)));
        TextView l = UiKit.caption(act, line.toString());
        l.setPadding(0, dp(4), 0, 0);
        box.addView(l);
        JSONArray rej = r.optJSONArray("rejections");
        final boolean rejected = isRejected(r);
        for (int i = 0; rej != null && i < Math.min(2, rej.length()); i++) {
            Object o = rej.opt(i);
            String reason = o instanceof JSONObject ? ((JSONObject) o).optString("reason", "") : String.valueOf(o);
            if (reason.isEmpty()) continue;
            TextView rt = UiKit.caption(act, reason);
            rt.setTextColor(AppTheme.warning());
            rt.setPadding(0, dp(2), 0, 0);
            box.addView(rt);
        }
        final Button grab = rejected ? UiKit.outline(act, I18n.tr("Grab Anyway"), null) : UiKit.primary(act, I18n.tr("Grab"), null);
        grab.setOnClickListener(v -> {
            Runnable go = () -> {
                setBusy(grab, true);
                async(slow, api -> { api.grabRelease(r); return null; }, x -> {
                    toast("Sent to the download client.");
                    sheet.dismiss();
                }, e -> {
                    setBusy(grab, false);
                    toast(msg(e));
                });
            };
            if (rejected) confirm("Grab rejected release?", "Sonarr rejected this release for the reasons shown. Download it anyway?", "Grab", false, go);
            else go.run();
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, dp(36));
        glp.topMargin = dp(8);
        compact(grab);
        box.addView(grab, glp);
        return box;
    }

    // ================================================================== ADD series

    private void showAddDialog() {
        final Sheet s = new Sheet("Add series");
        final EditText term = UiKit.input(act, I18n.tr("Search by title or tvdb:12345"));
        term.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        term.setInputType(InputType.TYPE_CLASS_TEXT);
        s.box.addView(term, new LinearLayout.LayoutParams(-1, dp(44)));
        final Button go = UiKit.primary(act, I18n.tr("Search"), null);
        LinearLayout row = UiKit.buttonRow(act, go);
        s.box.addView(row);
        final LinearLayout results = UiKit.column(act);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        s.box.addView(results, rlp);
        final Runnable search = () -> {
            final String q = term.getText().toString().trim();
            if (q.isEmpty()) return;
            setBusy(go, true);
            results.removeAllViews();
            results.addView(UiKit.loading(act, I18n.tr("Searching…")));
            async(slow, api -> api.lookup(q), arr -> {
                setBusy(go, false);
                if (!s.dialog.isShowing()) return;
                results.removeAllViews();
                if (arr.length() == 0) {
                    results.addView(UiKit.empty(act, I18n.tr("No results"), I18n.tr("Try the original title or a TVDB id.")));
                    return;
                }
                int n = 0;
                for (int i = 0; i < arr.length() && n < 25; i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r == null) continue;
                    if (n++ > 0) results.addView(listDivider());
                    results.addView(lookupRow(r, s), new LinearLayout.LayoutParams(-1, -2));
                }
            }, t -> {
                setBusy(go, false);
                if (!s.dialog.isShowing()) return;
                results.removeAllViews();
                results.addView(failureViewInline(t));
            });
        };
        go.setOnClickListener(v -> search.run());
        term.setOnEditorActionListener((v, actionId, ev) -> {
            search.run();
            return true;
        });
        s.show();
    }

    private View lookupRow(final JSONObject r, final Sheet parent) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setBackground(UiKit.rowRipple(act));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(poster(r, 40, 60), new LinearLayout.LayoutParams(dp(40), dp(60)));
        LinearLayout tx = UiKit.column(act);
        TextView t = UiKit.text(act, r.optString("title", "?"), 14f, AppTheme.text(), UiKit.MEDIUM);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(t);
        StringBuilder sb = new StringBuilder();
        if (r.optInt("year") > 0) sb.append(r.optInt("year"));
        appendDot(sb, r.optString("network", ""));
        int seasons = r.optJSONArray("seasons") == null ? 0 : r.optJSONArray("seasons").length();
        if (seasons > 0) appendDot(sb, seasons == 1 ? I18n.tr("1 season") : tf("{0} seasons", seasons));
        TextView c = UiKit.singleLine(UiKit.caption(act, sb.toString()));
        c.setPadding(0, dp(2), 0, 0);
        tx.addView(c);
        final boolean exists = r.optInt("id") > 0;
        if (exists) tx.addView(spaced(UiKit.badge(act, I18n.tr("In library"), AppTheme.success())));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginStart(dp(12));
        row.addView(tx, tlp);
        row.setOnClickListener(v -> {
            if (exists) {
                parent.dismiss();
                openSeries(r.optInt("id"));
            } else {
                showAddOptions(r, parent);
            }
        });
        return row;
    }

    private void showAddOptions(final JSONObject lookup, final Sheet parent) {
        final Sheet s = new Sheet("Add series");
        s.box.addView(UiKit.subtitle(act, lookup.optString("title", "") + (lookup.optInt("year") > 0 ? " (" + lookup.optInt("year") + ")" : "")));
        final LinearLayout body = UiKit.column(act);
        body.addView(UiKit.loading(act, I18n.tr("Loading options…")));
        s.box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        s.show();
        async(io, api -> {
            JSONObject o = new JSONObject();
            o.put("roots", api.rootFolders());
            o.put("profiles", api.qualityProfiles());
            return o;
        }, o -> {
            if (!s.dialog.isShowing()) return;
            body.removeAllViews();
            final JSONArray roots = o.optJSONArray("roots");
            final JSONArray profiles = o.optJSONArray("profiles");
            if (roots == null || roots.length() == 0) {
                body.addView(failureViewInline(new Exception("No root folder is configured. Add one in the System section first.")));
                return;
            }
            if (profiles == null || profiles.length() == 0) {
                body.addView(failureViewInline(new Exception("No quality profile found in Sonarr.")));
                return;
            }
            final List<String> rootNames = new ArrayList<>();
            for (int i = 0; i < roots.length(); i++) {
                JSONObject rf = roots.optJSONObject(i);
                if (rf != null && !rf.optString("path", "").isEmpty()) rootNames.add(rf.optString("path", ""));
            }
            final List<String> profNames = new ArrayList<>();
            final List<Integer> profIds = new ArrayList<>();
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.optJSONObject(i);
                if (p == null) continue;
                profNames.add(p.optString("name", "?"));
                profIds.add(p.optInt("id"));
            }
            if (rootNames.isEmpty() || profIds.isEmpty()) {
                body.addView(failureViewInline(new Exception("Sonarr returned no usable root folder or quality profile.")));
                return;
            }
            final String[] monitorLabels = {"All episodes", "Future episodes", "Missing episodes", "Existing episodes",
                    "Pilot episode", "First season", "Last season", "None"};
            final String[] monitorVals = {"all", "future", "missing", "existing", "pilot", "firstSeason", "lastSeason", "none"};
            final String[] typeLabels = {"Standard", "Anime", "Daily"};
            final String[] typeVals = {"standard", "anime", "daily"};
            final int[] selRoot = {0}, selProf = {0}, selMon = {0}, selType = {0};
            String lt = lookup.optString("seriesType", "standard");
            for (int i = 0; i < typeVals.length; i++) if (typeVals[i].equals(lt)) selType[0] = i;
            body.addView(choiceField("Root folder", rootNames.toArray(new String[0]), selRoot, true));
            body.addView(choiceField("Quality profile", profNames.toArray(new String[0]), selProf, false));
            body.addView(choiceField("Monitor", monitorLabels, selMon, false));
            body.addView(choiceField("Series type", typeLabels, selType, false));
            final Switch folders = switchRow(body, "Season folders", null, true);
            final Switch search = switchRow(body, "Start searching for missing episodes", null, true);
            Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
            final Button add = UiKit.primary(act, I18n.tr("Add Series"), null);
            add.setOnClickListener(v -> {
                final String root = rootNames.get(selRoot[0]);
                final int prof = profIds.get(selProf[0]);
                final String mon = monitorVals[selMon[0]];
                final boolean sf = folders.isChecked();
                final String type = typeVals[selType[0]];
                final boolean sm = search.isChecked();
                setBusy(add, true);
                async(slow, api -> {
                    api.addSeries(lookup, root, prof, mon, sf, type, sm);
                    return null;
                }, r -> {
                    toast("Series added.");
                    s.dismiss();
                    parent.dismiss();
                    seriesCache = null;
                    go(S_SERIES);
                }, e -> {
                    setBusy(add, false);
                    toast(msg(e));
                });
            });
            body.addView(UiKit.buttonRow(act, cancel, add));
        }, t -> {
            if (!s.dialog.isShowing()) return;
            body.removeAllViews();
            body.addView(failureViewInline(t));
        });
    }

    /** Label + tappable value that opens a single-choice sheet. */
    private View choiceField(final String label, final String[] options, final int[] selected, boolean mono) {
        LinearLayout box = UiKit.column(act);
        TextView l = UiKit.caption(act, I18n.tr(label));
        l.setPadding(0, dp(12), 0, dp(6));
        box.addView(l);
        LinearLayout valueRow = UiKit.row(act);
        valueRow.setPadding(dp(14), dp(11), dp(14), dp(11));
        valueRow.setBackground(UiKit.pressable(act, AppTheme.inner(), AppTheme.outline(), UiKit.R_INNER));
        valueRow.setClickable(true);
        valueRow.setFocusable(true);
        final TextView value = UiKit.singleLine(UiKit.text(act, I18n.tr(options[selected[0]]), 14f, AppTheme.text(), null));
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        if (mono) value.setTypeface(Typeface.MONOSPACE);
        valueRow.addView(value, new LinearLayout.LayoutParams(0, -2, 1.0f));
        valueRow.addView(UiKit.caption(act, "▾"));
        valueRow.setOnClickListener(v -> choose(label, options, selected[0], idx -> {
            selected[0] = idx;
            value.setText(I18n.tr(options[idx]));
        }));
        box.addView(valueRow, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ================================================================== CALENDAR

    private void loadCalendar() {
        final int g = gen;
        showLoading("Loading calendar…");
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -3);
        final String start = SonarrApi.isoUtc(c.getTimeInMillis());
        c.add(Calendar.DAY_OF_YEAR, 24);
        final String end = SonarrApi.isoUtc(c.getTimeInMillis());
        async(io, api -> api.calendar(start, end), arr -> {
            if (g != gen || section != S_CALENDAR || detailSeriesId > 0) return;
            content.removeAllViews();
            clearRefs();
            if (arr.length() == 0) {
                content.addView(UiKit.empty(act, I18n.tr("Nothing scheduled"), I18n.tr("No monitored episodes air in the next three weeks.")));
                return;
            }
            TreeMap<String, List<JSONObject>> byDay = new TreeMap<>();
            Map<JSONObject, Long> airs = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                long air = parseUtc(e.optString("airDateUtc", ""));
                airs.put(e, air);
                String day = air > 0 ? fmtLocalDate(air) : e.optString("airDate", "?");
                List<JSONObject> l = byDay.get(day);
                if (l == null) {
                    l = new ArrayList<>();
                    byDay.put(day, l);
                }
                l.add(e);
            }
            String today = fmtLocalDate(System.currentTimeMillis());
            for (Map.Entry<String, List<JSONObject>> en : byDay.entrySet()) {
                boolean isToday = en.getKey().equals(today);
                TextView h = UiKit.overline(act, dayLabel(en.getKey()));
                if (isToday) h.setTextColor(AppTheme.accent());
                h.setPadding(dp(4), dp(4), 0, dp(8));
                content.addView(h);
                LinearLayout card = UiKit.card(act);
                card.setPadding(dp(4), dp(4), dp(4), dp(4));
                List<JSONObject> eps = en.getValue();
                final Map<JSONObject, Long> fa = airs;
                Collections.sort(eps, (a, b) -> Long.compare(fa.get(a) == null ? 0 : fa.get(a), fa.get(b) == null ? 0 : fa.get(b)));
                for (int i = 0; i < eps.size(); i++) {
                    if (i > 0) card.addView(listDivider());
                    Long air = airs.get(eps.get(i));
                    card.addView(calendarRow(eps.get(i), air == null ? 0 : air), new LinearLayout.LayoutParams(-1, -2));
                }
                content.addView(card);
            }
        }, t -> {
            if (g == gen) showFailure(t, "Could not load the calendar");
        });
    }

    private String dayLabel(String isoDay) {
        Calendar c = Calendar.getInstance();
        String today = fmtLocalDate(c.getTimeInMillis());
        c.add(Calendar.DAY_OF_YEAR, 1);
        String tomorrow = fmtLocalDate(c.getTimeInMillis());
        c.add(Calendar.DAY_OF_YEAR, -2);
        String yesterday = fmtLocalDate(c.getTimeInMillis());
        if (isoDay.equals(today)) return I18n.tr("Today");
        if (isoDay.equals(tomorrow)) return I18n.tr("Tomorrow");
        if (isoDay.equals(yesterday)) return I18n.tr("Yesterday");
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDay);
            if (d != null) return new SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(d);
        } catch (Throwable ignored) {
            // fall back to the ISO day
        }
        return isoDay;
    }

    private View calendarRow(final JSONObject e, long air) {
        JSONObject s = e.optJSONObject("series");
        LinearLayout row = UiKit.column(act);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout top = UiKit.row(act);
        top.addView(UiKit.singleLine(UiKit.text(act, s == null ? "?" : s.optString("title", "?"), 14f, AppTheme.text(), UiKit.MEDIUM)),
                new LinearLayout.LayoutParams(0, -2, 1.0f));
        boolean has = e.optBoolean("hasFile");
        boolean aired = air > 0 && air <= System.currentTimeMillis();
        View badge;
        if (has) badge = UiKit.badge(act, I18n.tr("Downloaded"), AppTheme.success());
        else if (aired) badge = UiKit.badge(act, I18n.tr("Missing"), AppTheme.warning());
        else badge = UiKit.badge(act, air > 0 ? fmtLocalTime(air) : I18n.tr("Upcoming"), AppTheme.accent());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        top.addView(badge, blp);
        row.addView(top);
        String et = e.optString("title", "");
        TextView t = UiKit.singleLine(UiKit.caption(act, episodeCode(e) + " · " + (et.isEmpty() ? I18n.tr("TBA") : et)
                + (air > 0 && (has || aired) ? " · " + fmtLocalTime(air) : "")));
        t.setPadding(0, dp(4), 0, 0);
        row.addView(t);
        if (s != null && s.optInt("id") > 0) {
            final int sid = s.optInt("id");
            row.setBackground(UiKit.rowRipple(act));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> openSeries(sid));
        }
        return row;
    }

    // ================================================================== QUEUE (live)

    private void loadQueue(final boolean silent) {
        final int g = gen;
        boolean shell = queueRows != null && queueRows.getParent() != null && content.getChildCount() > 0;
        if (silent) {
            if (pollPending) return;
            pollPending = true;
        } else if (!shell) {
            showLoading("Loading queue…");
        }
        async(io, api -> api.queue(1, 100), o -> {
            if (silent) pollPending = false;
            if (g != gen || section != S_QUEUE || detailSeriesId > 0) return;
            fillQueue(o);
        }, t -> {
            if (silent) pollPending = false;
            if (g != gen || section != S_QUEUE || detailSeriesId > 0) return;
            if (queueRows != null && queueRows.getParent() != null) {
                if (!silent) toast(msg(t));
            } else if (!silent || !failureShown) {
                showFailure(t, "Could not load the queue");
            }
        });
    }

    private void fillQueue(JSONObject o) {
        JSONArray recs = o.optJSONArray("records");
        int total = o.optInt("totalRecords", recs == null ? 0 : recs.length());
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; recs != null && i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r != null) list.add(r);
        }
        if (list.isEmpty()) {
            if (queueRows == null && content.getChildCount() == 1 && "queue-empty".equals(content.getChildAt(0).getTag())) return;
            content.removeAllViews();
            clearRefs();
            View empty = UiKit.empty(act, I18n.tr("The queue is empty"), I18n.tr("Grabbed releases appear here while they download."));
            empty.setTag("queue-empty");
            content.addView(empty);
            return;
        }
        if (queueRows == null || queueRows.getParent() == null) {
            content.removeAllViews();
            clearRefs();
            queueCount = UiKit.overline(act, "");
            queueCount.setPadding(dp(4), dp(4), 0, dp(8));
            content.addView(queueCount);
            queueRows = UiKit.card(act);
            queueRows.setPadding(dp(4), dp(4), dp(4), dp(4));
            content.addView(queueRows);
        }
        setText(queueCount, total > list.size() ? tf("Showing {0} of {1} items", list.size(), total)
                : (list.size() == 1 ? I18n.tr("1 item") : tf("{0} items", list.size())));

        List<Integer> ids = new ArrayList<>();
        for (JSONObject r : list) ids.add(r.optInt("id"));
        if (ids.equals(queueOrder)) {
            for (JSONObject r : list) {
                QueueHolder h = queueHolders.get(r.optInt("id"));
                if (h != null) h.bind(r);
            }
            return;
        }
        final int y = scroll.getScrollY();
        Map<Integer, QueueHolder> old = new HashMap<>(queueHolders);
        queueRows.removeAllViews();
        queueHolders.clear();
        queueOrder.clear();
        for (int i = 0; i < list.size(); i++) {
            JSONObject r = list.get(i);
            int id = r.optInt("id");
            QueueHolder h = old.get(id);
            if (h == null) h = new QueueHolder();
            if (i > 0) queueRows.addView(listDivider());
            h.bind(r);
            queueRows.addView(h.view, new LinearLayout.LayoutParams(-1, -2));
            queueHolders.put(id, h);
            queueOrder.add(id);
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** Badge label + colour for a queue record (status / trackedDownloadStatus / trackedDownloadState). */
    private static Object[] queueState(JSONObject r) {
        String status = r.optString("status", "").toLowerCase(Locale.ROOT);
        String tds = r.optString("trackedDownloadStatus", "").toLowerCase(Locale.ROOT);
        String tstate = r.optString("trackedDownloadState", "").toLowerCase(Locale.ROOT);
        if ("error".equals(tds) || "failed".equals(status) || "failed".equals(tstate) || "failedpending".equals(tstate)) {
            return new Object[]{"Failed", AppTheme.danger()};
        }
        if ("downloadclientunavailable".equals(status)) return new Object[]{"Client unavailable", AppTheme.danger()};
        if ("importpending".equals(tstate)) return new Object[]{"Import pending", AppTheme.warning()};
        if ("importing".equals(tstate)) return new Object[]{"Importing", AppTheme.success()};
        if ("imported".equals(tstate)) return new Object[]{"Imported", AppTheme.success()};
        if ("warning".equals(tds) || "warning".equals(status)) return new Object[]{"Warning", AppTheme.warning()};
        if ("downloading".equals(status)) return new Object[]{"Downloading", AppTheme.accent()};
        if ("paused".equals(status)) return new Object[]{"Paused", AppTheme.textMuted()};
        if ("queued".equals(status)) return new Object[]{"Queued", AppTheme.textMuted()};
        if ("delay".equals(status)) return new Object[]{"Delayed", AppTheme.textMuted()};
        if ("completed".equals(status)) return new Object[]{"Completed", AppTheme.success()};
        return new Object[]{status.isEmpty() ? "Unknown" : capitalize(status), AppTheme.textMuted()};
    }

    /** One queue row, updated in place by the ticker. */
    private final class QueueHolder {
        int id;
        String title = "";
        final LinearLayout view;
        final TextView name;
        final LinearLayout metaRow;
        String badgeKey = "";
        final TextView episode;
        final TextView release;
        final View progress;
        int progressColor;
        final TextView meta;
        final TextView warn;

        QueueHolder() {
            view = UiKit.column(act);
            view.setPadding(dp(12), dp(12), dp(12), dp(12));

            LinearLayout top = UiKit.row(act);
            name = UiKit.singleLine(UiKit.text(act, "", 14f, AppTheme.text(), UiKit.MEDIUM));
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
            Button remove = compact(UiKit.danger(act, I18n.tr("Remove"), v -> showRemoveQueue(id, title)));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, dp(32));
            rlp.setMarginStart(dp(10));
            top.addView(remove, rlp);
            view.addView(top);

            metaRow = UiKit.row(act);
            metaRow.setPadding(0, dp(6), 0, 0);
            metaRow.addView(UiKit.badge(act, "", AppTheme.textMuted()));
            episode = UiKit.singleLine(UiKit.caption(act, ""));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            elp.setMarginStart(dp(8));
            metaRow.addView(episode, elp);
            view.addView(metaRow);

            release = UiKit.singleLine(UiKit.caption(act, ""));
            release.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            release.setPadding(0, dp(4), 0, 0);
            view.addView(release);

            progressColor = AppTheme.accent();
            progress = UiKit.progress(act, 0, progressColor);
            view.addView(progress);

            meta = UiKit.singleLine(UiKit.caption(act, ""));
            meta.setPadding(0, dp(6), 0, 0);
            view.addView(meta);

            warn = UiKit.caption(act, "");
            warn.setTextColor(AppTheme.warning());
            warn.setMaxLines(3);
            warn.setEllipsize(TextUtils.TruncateAt.END);
            warn.setPadding(0, dp(4), 0, 0);
            warn.setVisibility(View.GONE);
            view.addView(warn);
        }

        void bind(JSONObject r) {
            id = r.optInt("id");
            JSONObject s = r.optJSONObject("series");
            JSONObject e = r.optJSONObject("episode");
            title = s != null ? s.optString("title", "") : r.optString("title", "");
            setText(name, title.isEmpty() ? r.optString("title", "?") : title);
            Object[] st = queueState(r);
            String label = (String) st[0];
            int color = (Integer) st[1];
            String key = label + "|" + color;
            if (!key.equals(badgeKey)) {
                badgeKey = key;
                metaRow.removeViewAt(0);
                metaRow.addView(UiKit.badge(act, I18n.tr(label), color), 0);
            }
            setText(episode, e == null ? "" : episodeCode(e) + " · " + e.optString("title", ""));
            setText(release, r.optString("title", ""));
            double size = r.optDouble("size", 0);
            double left = r.optDouble("sizeleft", 0);
            double pct = size > 0 ? Math.max(0, Math.min(100, (size - left) * 100.0 / size)) : 0;
            progressColor = updateProgress(progress, pct, color, progressColor);
            StringBuilder m = new StringBuilder(String.format(Locale.US, "%.0f%%", pct));
            if (size > 0) appendDot(m, fmtSize((long) size));
            String tl = r.optString("timeleft", "");
            if (!tl.isEmpty() && "downloading".equalsIgnoreCase(r.optString("status", ""))) appendDot(m, tf("{0} left", tl));
            appendDot(m, r.optString("downloadClient", ""));
            appendDot(m, r.optString("protocol", "").isEmpty() ? "" : capitalize(r.optString("protocol", "")));
            setText(meta, m.toString());
            String w = firstStatusMessage(r);
            if (!w.isEmpty()) {
                setText(warn, w);
                warn.setVisibility(View.VISIBLE);
            } else if (warn.getVisibility() != View.GONE) {
                warn.setVisibility(View.GONE);
            }
        }
    }

    private static String firstStatusMessage(JSONObject r) {
        String em = r.optString("errorMessage", "");
        if (!em.isEmpty()) return em;
        JSONArray msgs = r.optJSONArray("statusMessages");
        for (int i = 0; msgs != null && i < msgs.length(); i++) {
            JSONObject m0 = msgs.optJSONObject(i);
            if (m0 == null) continue;
            JSONArray mm = m0.optJSONArray("messages");
            if (mm != null && mm.length() > 0 && !mm.optString(0, "").isEmpty()) return mm.optString(0, "");
            if (!m0.optString("title", "").isEmpty()) return m0.optString("title", "");
        }
        return "";
    }

    private void showRemoveQueue(final int id, String title) {
        final Sheet s = new Sheet("Remove from queue");
        if (title != null && !title.isEmpty()) s.box.addView(UiKit.subtitle(act, title));
        final Switch client = switchRow(s.box, "Remove from the download client", "Also deletes the partial download.", true);
        final Switch block = switchRow(s.box, "Add the release to the blocklist", "Sonarr will not grab this release again.", false);
        final Switch replace = switchRow(s.box, "Search for a replacement", "Only applies when blocklisting.", true);
        Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        final Button remove = UiKit.danger(act, I18n.tr("Remove"), null);
        remove.setOnClickListener(v -> {
            final boolean rc = client.isChecked();
            final boolean bl = block.isChecked();
            final boolean skip = !replace.isChecked();
            setBusy(remove, true);
            async(io, api -> { api.removeQueueItem(id, rc, bl, skip); return null; }, r -> {
                s.dismiss();
                toast("Removed from the queue.");
                loadQueue(true);
            }, e -> {
                setBusy(remove, false);
                toast(msg(e));
            });
        });
        s.box.addView(UiKit.buttonRow(act, cancel, remove));
        s.show();
    }

    // ================================================================== HISTORY

    private void loadHistory() {
        final int g = gen;
        final int pg = page;
        showLoading("Loading history…");
        async(io, api -> api.history(pg, PAGE_SIZE), o -> {
            if (g != gen || section != S_HISTORY || detailSeriesId > 0) return;
            content.removeAllViews();
            clearRefs();
            JSONArray recs = o.optJSONArray("records");
            int total = o.optInt("totalRecords");
            if (recs == null || recs.length() == 0) {
                content.addView(UiKit.empty(act, I18n.tr("No history yet"), I18n.tr("Grabs, imports and failures show up here.")));
                return;
            }
            TextView count = UiKit.overline(act, tf("{0} events", total));
            count.setPadding(dp(4), dp(4), 0, dp(8));
            content.addView(count);
            LinearLayout card = UiKit.card(act);
            card.setPadding(dp(4), dp(4), dp(4), dp(4));
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r == null) continue;
                if (card.getChildCount() > 0) card.addView(listDivider());
                card.addView(historyRow(r), new LinearLayout.LayoutParams(-1, -2));
            }
            content.addView(card);
            addPager(pg, total);
        }, t -> {
            if (g == gen) showFailure(t, "Could not load history");
        });
    }

    private View historyRow(final JSONObject r) {
        LinearLayout row = UiKit.column(act);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        JSONObject s = r.optJSONObject("series");
        JSONObject e = r.optJSONObject("episode");
        LinearLayout top = UiKit.row(act);
        top.addView(UiKit.singleLine(UiKit.text(act, s == null ? "?" : s.optString("title", "?"), 14f, AppTheme.text(), UiKit.MEDIUM)),
                new LinearLayout.LayoutParams(0, -2, 1.0f));
        String ev = r.optString("eventType", "");
        int color = AppTheme.textMuted();
        String label = capitalize(ev);
        if (ev.equals("grabbed")) { label = "Grabbed"; color = AppTheme.accent(); }
        else if (ev.equals("downloadFolderImported")) { label = "Imported"; color = AppTheme.success(); }
        else if (ev.equals("downloadFailed")) { label = "Download failed"; color = AppTheme.danger(); }
        else if (ev.equals("episodeFileDeleted")) { label = "File deleted"; color = AppTheme.warning(); }
        else if (ev.equals("episodeFileRenamed")) label = "Renamed";
        else if (ev.equals("downloadIgnored")) label = "Ignored";
        else if (ev.equals("seriesFolderImported")) { label = "Imported"; color = AppTheme.success(); }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        top.addView(UiKit.badge(act, I18n.tr(label), color), blp);
        row.addView(top);
        StringBuilder sb = new StringBuilder();
        if (e != null) sb.append(episodeCode(e));
        long when = parseUtc(r.optString("date", ""));
        if (when > 0) appendDot(sb, fmtLocal(when));
        String q = qualityName(r.optJSONObject("quality"));
        if (!q.isEmpty()) appendDot(sb, q);
        TextView c = UiKit.singleLine(UiKit.caption(act, sb.toString()));
        c.setPadding(0, dp(4), 0, 0);
        row.addView(c);
        String src = r.optString("sourceTitle", "");
        if (!src.isEmpty()) {
            TextView st = UiKit.singleLine(UiKit.caption(act, src));
            st.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            st.setPadding(0, dp(2), 0, 0);
            row.addView(st);
        }
        if (s != null && s.optInt("id") > 0) {
            final int sid = s.optInt("id");
            row.setBackground(UiKit.rowRipple(act));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> openSeries(sid));
        }
        return row;
    }

    // ================================================================== WANTED

    private void loadWanted() {
        final int g = gen;
        final int pg = page;
        showLoading("Loading missing episodes…");
        async(io, api -> api.wantedMissing(pg, PAGE_SIZE), o -> {
            if (g != gen || section != S_WANTED || detailSeriesId > 0) return;
            content.removeAllViews();
            clearRefs();
            int total = o.optInt("totalRecords");
            JSONArray recs = o.optJSONArray("records");
            if (recs == null || recs.length() == 0) {
                content.addView(UiKit.empty(act, I18n.tr("Nothing missing"), I18n.tr("Every monitored episode that has aired is downloaded.")));
                return;
            }
            LinearLayout bar = UiKit.row(act);
            bar.setPadding(dp(4), 0, 0, dp(8));
            TextView cnt = UiKit.overline(act, total == 1 ? I18n.tr("1 missing episode") : tf("{0} missing episodes", total));
            cnt.setPadding(0, 0, 0, 0);
            bar.addView(cnt, new LinearLayout.LayoutParams(0, -2, 1.0f));
            Button all = compact(UiKit.outline(act, I18n.tr("Search All"), v ->
                    confirm("Search all missing?", "Ask Sonarr to search for every monitored missing episode? This can hit your indexers hard.",
                            "Search", false, () -> runAction(v, api -> { api.searchAllMissing(); return null; }, "Search started.", null))));
            bar.addView(all, new LinearLayout.LayoutParams(-2, dp(34)));
            content.addView(bar);
            LinearLayout card = UiKit.card(act);
            card.setPadding(dp(4), dp(4), dp(4), dp(4));
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r == null) continue;
                if (card.getChildCount() > 0) card.addView(listDivider());
                card.addView(wantedRow(r), new LinearLayout.LayoutParams(-1, -2));
            }
            content.addView(card);
            addPager(pg, total);
        }, t -> {
            if (g == gen) showFailure(t, "Could not load missing episodes");
        });
    }

    private View wantedRow(final JSONObject e) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        JSONObject s = e.optJSONObject("series");
        LinearLayout tx = UiKit.column(act);
        tx.addView(UiKit.singleLine(UiKit.text(act, s == null ? "?" : s.optString("title", "?"), 14f, AppTheme.text(), UiKit.MEDIUM)));
        long air = parseUtc(e.optString("airDateUtc", ""));
        String et = e.optString("title", "");
        TextView c = UiKit.singleLine(UiKit.caption(act, episodeCode(e) + " · " + (et.isEmpty() ? I18n.tr("TBA") : et)
                + (air > 0 ? " · " + tf("Aired {0}", fmtLocalDate(air)) : "")));
        c.setPadding(0, dp(2), 0, 0);
        tx.addView(c);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginEnd(dp(10));
        row.addView(tx, tlp);
        final int epId = e.optInt("id");
        row.addView(compact(UiKit.outline(act, I18n.tr("Search"), v ->
                runAction(v, api -> { api.searchEpisode(epId); return null; }, "Episode search started.", null))),
                new LinearLayout.LayoutParams(-2, dp(32)));
        if (s != null && s.optInt("id") > 0) {
            final int sid = s.optInt("id");
            tx.setBackground(UiKit.rowRipple(act));
            tx.setClickable(true);
            tx.setOnClickListener(v -> openSeries(sid));
        }
        return row;
    }

    private void addPager(int pg, int total) {
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (pages <= 1) return;
        TextView info = UiKit.caption(act, tf("Page {0} of {1}", pg, pages));
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(4), 0, 0);
        content.addView(info, new LinearLayout.LayoutParams(-1, -2));
        Button prev = UiKit.outline(act, I18n.tr("Previous"), v -> {
            page = Math.max(1, page - 1);
            scroll.scrollTo(0, 0);
            refresh();
        });
        Button next = UiKit.outline(act, I18n.tr("Next"), v -> {
            page++;
            scroll.scrollTo(0, 0);
            refresh();
        });
        if (pg <= 1) setBusy(prev, true);
        if (pg >= pages) setBusy(next, true);
        content.addView(UiKit.buttonRow(act, prev, next));
    }

    // ================================================================== SYSTEM

    private void loadSystem() {
        final int g = gen;
        showLoading("Loading system status…");
        async(io, api -> {
            JSONObject o = new JSONObject();
            o.put("status", api.status());
            try { o.put("health", api.health()); } catch (Throwable ignored) { }
            try { o.put("disk", api.diskSpace()); } catch (Throwable ignored) { }
            try { o.put("roots", api.rootFolders()); } catch (Throwable ignored) { }
            try { o.put("profiles", api.qualityProfiles()); } catch (Throwable ignored) { }
            try { o.put("indexers", api.indexers()); } catch (Throwable ignored) { }
            try { o.put("clients", api.downloadClients()); } catch (Throwable ignored) { }
            return o;
        }, o -> {
            if (g != gen || section != S_SYSTEM || detailSeriesId > 0) return;
            renderSystem(o);
        }, t -> {
            if (g == gen) showFailure(t, "Could not load system status");
        });
    }

    private LinearLayout sysCard(String title) {
        LinearLayout card = UiKit.card(act);
        card.addView(UiKit.overline(act, I18n.tr(title)));
        content.addView(card);
        return card;
    }

    private void renderSystem(JSONObject o) {
        content.removeAllViews();
        clearRefs();
        JSONObject st = o.optJSONObject("status");
        LinearLayout c = sysCard("Status");
        if (st != null) {
            c.addView(kv("Version", st.optString("version", "—")));
            String os = (st.optString("osName", "") + " " + st.optString("osVersion", "")).trim();
            if (!os.isEmpty()) c.addView(kv("System", os));
            String rt = (st.optString("runtimeName", "") + " " + st.optString("runtimeVersion", "")).trim();
            if (!rt.isEmpty()) c.addView(kv("Runtime", rt));
            long started = parseUtc(st.optString("startTime", ""));
            if (started > 0) c.addView(kv("Started", fmtLocal(started)));
            if (!st.optString("appData", "").isEmpty()) c.addView(kvMono("App data", st.optString("appData", "")));
        }

        JSONArray health = o.optJSONArray("health");
        c = sysCard("Health");
        if (health == null || health.length() == 0) {
            c.addView(UiKit.badge(act, I18n.tr("No problems detected"), AppTheme.success()));
        } else {
            for (int i = 0; i < health.length(); i++) {
                JSONObject h = health.optJSONObject(i);
                if (h == null) continue;
                boolean err = "error".equalsIgnoreCase(h.optString("type", ""));
                LinearLayout row = UiKit.column(act);
                row.setPadding(0, i == 0 ? 0 : dp(10), 0, 0);
                row.addView(UiKit.badge(act, I18n.tr(err ? "Error" : "Warning"), err ? AppTheme.danger() : AppTheme.warning()),
                        new LinearLayout.LayoutParams(-2, -2));
                TextView t = UiKit.body(act, h.optString("message", ""));
                t.setPadding(0, dp(4), 0, 0);
                row.addView(t);
                c.addView(row);
            }
        }

        JSONArray disk = o.optJSONArray("disk");
        if (disk != null && disk.length() > 0) {
            c = sysCard("Disk space");
            for (int i = 0; i < disk.length(); i++) {
                JSONObject dsk = disk.optJSONObject(i);
                if (dsk == null) continue;
                long total = dsk.optLong("totalSpace"), free = dsk.optLong("freeSpace");
                LinearLayout row = UiKit.column(act);
                row.setPadding(0, i == 0 ? 0 : dp(12), 0, 0);
                TextView p = UiKit.mono(act, dsk.optString("path", ""));
                p.setTextSize(12f);
                row.addView(p);
                int used = total > 0 ? (int) ((total - free) * 100 / total) : 0;
                row.addView(UiKit.progress(act, used, used >= 95 ? AppTheme.danger() : used >= 85 ? AppTheme.warning() : AppTheme.accent()));
                TextView fr = UiKit.caption(act, tf("{0} free of {1}", fmtSize(free), fmtSize(total)));
                fr.setPadding(0, dp(4), 0, 0);
                row.addView(fr);
                c.addView(row);
            }
        }

        c = sysCard("Root folders");
        JSONArray roots = o.optJSONArray("roots");
        if (roots == null || roots.length() == 0) {
            c.addView(UiKit.caption(act, I18n.tr("No root folder yet. Series need one to be added.")));
        }
        for (int i = 0; roots != null && i < roots.length(); i++) {
            final JSONObject rf = roots.optJSONObject(i);
            if (rf == null) continue;
            LinearLayout row = UiKit.row(act);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout tx = UiKit.column(act);
            TextView p = UiKit.mono(act, rf.optString("path", ""));
            p.setTextSize(12f);
            tx.addView(p);
            boolean ok = rf.optBoolean("accessible", true);
            TextView fs = UiKit.caption(act, ok ? tf("{0} free", fmtSize(rf.optLong("freeSpace"))) : I18n.tr("Not accessible"));
            if (!ok) fs.setTextColor(AppTheme.danger());
            tx.addView(fs);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            tlp.setMarginEnd(dp(10));
            row.addView(tx, tlp);
            final int rid = rf.optInt("id");
            row.addView(compact(UiKit.danger(act, I18n.tr("Remove"), v ->
                    confirm("Remove root folder?", "Remove this root folder from Sonarr? Files on disk are not deleted.", "Remove", true, () ->
                            runAction(v, api -> { api.deleteRootFolder(rid); return null; }, "Root folder removed.", this::refresh)))),
                    new LinearLayout.LayoutParams(-2, dp(32)));
            c.addView(row);
        }
        Button addRoot = UiKit.outline(act, I18n.tr("Add Root Folder"), v -> showAddRoot());
        c.addView(UiKit.buttonRow(act, addRoot));

        c = sysCard("Quality profiles");
        JSONArray profs = o.optJSONArray("profiles");
        if (profs == null || profs.length() == 0) c.addView(UiKit.caption(act, I18n.tr("None configured.")));
        for (int i = 0; profs != null && i < profs.length(); i++) {
            JSONObject p = profs.optJSONObject(i);
            if (p != null) c.addView(kv(p.optString("name", "?"), p.optBoolean("upgradeAllowed") ? I18n.tr("Upgrades allowed") : I18n.tr("No upgrades")));
        }

        c = sysCard("Indexers");
        addEnabledList(c, o.optJSONArray("indexers"), "enableRss");
        c = sysCard("Download clients");
        addEnabledList(c, o.optJSONArray("clients"), "enable");

        c = sysCard("Tasks");
        Button rss = UiKit.outline(act, I18n.tr("RSS Sync"), v ->
                runAction(v, api -> { api.simpleCommand("RssSync"); return null; }, "RSS sync queued.", null));
        Button refreshAll = UiKit.outline(act, I18n.tr("Refresh All"), v ->
                runAction(v, api -> { api.simpleCommand("RefreshSeries"); return null; }, "Refresh queued for every series.", null));
        c.addView(UiKit.buttonRow(act, rss, refreshAll));
        Button rescanAll = UiKit.outline(act, I18n.tr("Rescan All"), v ->
                runAction(v, api -> { api.simpleCommand("RescanSeries"); return null; }, "Rescan queued for every series.", null));
        Button backup = UiKit.outline(act, I18n.tr("Backup"), v ->
                runAction(v, api -> { api.simpleCommand("Backup"); return null; }, "Backup queued.", null));
        c.addView(UiKit.buttonRow(act, rescanAll, backup));

        TextView note = UiKit.caption(act, I18n.tr("Indexers and download clients are managed by Prowlarr and the Services tab. Advanced settings are in Sonarr's own web interface."));
        note.setPadding(dp(4), 0, dp(4), 0);
        content.addView(note);
    }

    private void addEnabledList(LinearLayout card, JSONArray arr, String flag) {
        if (arr == null || arr.length() == 0) {
            card.addView(UiKit.caption(act, I18n.tr("None configured.")));
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            boolean on = !o.has(flag) || o.optBoolean(flag);
            LinearLayout row = UiKit.row(act);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout tx = UiKit.column(act);
            tx.addView(UiKit.singleLine(UiKit.text(act, o.optString("name", "?"), 14f, AppTheme.text(), UiKit.MEDIUM)));
            String impl = o.optString("implementationName", o.optString("implementation", ""));
            if (!impl.isEmpty()) tx.addView(UiKit.caption(act, impl));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
            row.addView(UiKit.badge(act, I18n.tr(on ? "Enabled" : "Disabled"), on ? AppTheme.success() : AppTheme.textMuted()));
            card.addView(row);
        }
    }

    private void showAddRoot() {
        final Sheet s = new Sheet("Add root folder");
        TextView hint = UiKit.body(act, I18n.tr("Sonarr stores each series in a folder inside this path."));
        s.box.addView(hint);
        final EditText path = field(s.box, "Folder path", "", true);
        path.setHint("/storage/emulated/0/JellyarrMedia/TV");
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        final Button add = UiKit.primary(act, I18n.tr("Add"), null);
        add.setOnClickListener(v -> {
            final String p = path.getText().toString().trim();
            if (p.isEmpty()) {
                toast("Enter a folder path.");
                return;
            }
            setBusy(add, true);
            async(io, api -> { api.addRootFolder(p); return null; }, r -> {
                s.dismiss();
                toast("Root folder added.");
                refresh();
            }, e -> {
                setBusy(add, false);
                toast(msg(e));
            });
        });
        s.box.addView(UiKit.buttonRow(act, cancel, add));
        s.show();
    }

    // ================================================================== AUTO SEARCH

    private void renderAuto() {
        content.removeAllViews();
        clearRefs();

        LinearLayout card = UiKit.card(act);
        card.addView(UiKit.overline(act, I18n.tr("Missing episode search")));
        final Switch sw = switchRow(card, "Search missing episodes automatically",
                "Ask Sonarr to search every monitored missing episode on a fixed interval.",
                AutomationPrefs.isSonarrMissingEnabled(act));
        sw.setOnCheckedChangeListener((v, checked) -> {
            AutomationPrefs.setSonarrMissingEnabled(act, checked);
            toast(checked ? "Auto search on." : "Auto search off.");
        });

        TextView l = UiKit.caption(act, I18n.tr("Interval (minutes)"));
        l.setPadding(0, dp(14), 0, dp(6));
        card.addView(l);
        LinearLayout period = UiKit.row(act);
        final EditText input = UiKit.input(act, String.valueOf(AutomationPrefs.DEFAULT_SONARR_MINUTES));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(AutomationPrefs.getSonarrMissingMinutes(act)));
        period.addView(input, new LinearLayout.LayoutParams(0, dp(44), 1.0f));
        Button save = UiKit.outline(act, I18n.tr("Save"), null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, dp(44));
        slp.setMarginStart(dp(8));
        period.addView(save, slp);
        final Runnable dirty = UiStyle.bindDirty(save, input, () -> String.valueOf(AutomationPrefs.getSonarrMissingMinutes(act)));
        save.setOnClickListener(v -> {
            int val = AutomationPrefs.DEFAULT_SONARR_MINUTES;
            try { val = Integer.parseInt(input.getText().toString().trim()); } catch (Throwable ignored) { }
            AutomationPrefs.setSonarrMissingMinutes(act, val);
            input.setText(String.valueOf(AutomationPrefs.getSonarrMissingMinutes(act)));
            dirty.run();
            toast(tf("Saved: every {0} min", AutomationPrefs.getSonarrMissingMinutes(act)));
        });
        card.addView(period);
        TextView range = UiKit.caption(act, tf("Between {0} and {1} minutes.", AutomationPrefs.MIN_SONARR_MINUTES, AutomationPrefs.MAX_SONARR_MINUTES));
        range.setPadding(0, dp(6), 0, 0);
        card.addView(range);
        content.addView(card);

        LinearLayout statusCard = UiKit.card(act);
        statusCard.addView(UiKit.overline(act, I18n.tr("Last run")));
        final TextView status = UiKit.body(act, "");
        long last = AutomationPrefs.getSonarrMissingLastRun(act);
        status.setText(last > 0
                ? fmtLocal(last) + "\n" + I18n.tr(AutomationPrefs.getSonarrMissingLastSummary(act))
                : I18n.tr("Not run yet. Checked every 5 minutes while Sonarr is running."));
        statusCard.addView(status);
        final Button preview = UiKit.outline(act, I18n.tr("Preview"), null);
        final Button now = UiKit.primary(act, I18n.tr("Search Now"), null);
        preview.setOnClickListener(v -> runAuto(true, status, preview, now));
        now.setOnClickListener(v -> runAuto(false, status, preview, now));
        statusCard.addView(UiKit.buttonRow(act, preview, now));
        content.addView(statusCard);

        TextView note = UiKit.caption(act, I18n.tr("Only monitored episodes are searched. Keep Prowlarr and Transmission running. Very short intervals can get you rate-limited by indexers."));
        note.setPadding(dp(4), 0, dp(4), 0);
        content.addView(note);
    }

    private void runAuto(final boolean dryRun, final TextView status, final Button a, final Button b) {
        status.setText(I18n.tr(dryRun ? "Checking…" : "Searching…"));
        setBusy(a, true);
        setBusy(b, true);
        try {
            slow.execute(() -> {
                String out;
                try {
                    out = com.fishbowl.app.services.SonarrMissingSearch.run(act, dryRun);
                } catch (Throwable t) {
                    out = "Failed: " + msg(t);
                }
                final String res = out;
                act.runOnUiThread(() -> {
                    if (dead()) return;
                    setBusy(a, false);
                    setBusy(b, false);
                    status.setText(I18n.tr(res));
                });
            });
        } catch (RejectedExecutionException ignored) {
            setBusy(a, false);
            setBusy(b, false);
        }
    }

    // ================================================================== dialogs & small UI helpers

    /** Themed dialog with a title and a vertical body; add views to {@link #box}. */
    private final class Sheet {
        final LinearLayout box;
        final AlertDialog dialog;

        Sheet(String title) {
            box = UiKit.column(act);
            box.setPadding(dp(20), dp(18), dp(20), dp(16));
            if (title != null) {
                TextView t = UiKit.title(act, I18n.tr(title));
                t.setMaxLines(3);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(0, 0, 0, dp(10));
                box.addView(t);
            }
            ScrollView sv = new ScrollView(act);
            sv.addView(box, new ViewGroup.LayoutParams(-1, -2));
            dialog = new AlertDialog.Builder(act).setView(I18n.attach(sv)).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(UiKit.shape(act, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));
            }
        }

        void show() {
            if (dead()) return;
            try { dialog.show(); } catch (Throwable ignored) { }
        }

        void dismiss() {
            try { dialog.dismiss(); } catch (Throwable ignored) { }
        }
    }

    private void confirm(String title, String message, String positive, boolean destructive, final Runnable onYes) {
        final Sheet s = new Sheet(title);
        s.box.addView(UiKit.body(act, I18n.tr(message)));
        Button no = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        View.OnClickListener yesL = v -> {
            s.dismiss();
            onYes.run();
        };
        Button yes = destructive ? UiKit.danger(act, I18n.tr(positive), yesL) : UiKit.primary(act, I18n.tr(positive), yesL);
        s.box.addView(UiKit.buttonRow(act, no, yes));
        s.show();
    }

    private void choose(String title, String[] items, int selected, final IntDone cb) {
        final Sheet s = new Sheet(title);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean sel = i == selected;
            TextView row = UiKit.text(act, (sel ? "✓  " : "") + I18n.tr(items[i]), 14f,
                    sel ? AppTheme.accent() : AppTheme.text(), sel ? UiKit.BOLD : UiKit.MEDIUM);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(UiKit.rowRipple(act));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                s.dismiss();
                cb.run(idx);
            });
            s.box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        s.show();
    }

    private EditText field(LinearLayout parent, String label, String value, boolean mono) {
        TextView l = UiKit.caption(act, I18n.tr(label));
        l.setPadding(0, dp(12), 0, dp(6));
        parent.addView(l);
        EditText e = UiKit.input(act, "");
        e.setText(value == null ? "" : value);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private Switch switchRow(LinearLayout parent, String label, String hint, boolean checked) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(0, dp(12), 0, 0);
        LinearLayout tx = UiKit.column(act);
        tx.addView(UiKit.text(act, I18n.tr(label), 14f, AppTheme.text(), null));
        if (hint != null) {
            TextView h = UiKit.caption(act, I18n.tr(hint));
            h.setPadding(0, dp(2), 0, 0);
            tx.addView(h);
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

    private View kv(String key, String value) {
        return UiKit.kv(act, I18n.tr(key), value == null || value.isEmpty() ? "—" : value);
    }

    private View kvMono(String key, String value) {
        View r = kv(key, value);
        if (r instanceof ViewGroup && ((ViewGroup) r).getChildCount() > 1 && ((ViewGroup) r).getChildAt(1) instanceof TextView) {
            TextView v = (TextView) ((ViewGroup) r).getChildAt(1);
            v.setTypeface(Typeface.MONOSPACE);
            v.setTextSize(12f);
            v.setTextIsSelectable(true);
        }
        return r;
    }

    private View listDivider() {
        View d = UiKit.divider(act);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
        lp.topMargin = 0;
        lp.bottomMargin = 0;
        lp.setMarginStart(dp(12));
        lp.setMarginEnd(dp(12));
        d.setLayoutParams(lp);
        return d;
    }

    private Button compact(Button b) {
        b.setTextSize(12.5f);
        b.setMinHeight(dp(32));
        b.setMinimumHeight(dp(32));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    /** Updates a {@link UiKit#progress} bar in place; returns the colour now applied. */
    private int updateProgress(View track, double percent, int color, int currentColor) {
        if (!(track instanceof LinearLayout)) return currentColor;
        LinearLayout l = (LinearLayout) track;
        if (l.getChildCount() < 2) return currentColor;
        View fill = l.getChildAt(0);
        View rest = l.getChildAt(1);
        if (!(fill.getLayoutParams() instanceof LinearLayout.LayoutParams)
                || !(rest.getLayoutParams() instanceof LinearLayout.LayoutParams)) return currentColor;
        float p = (float) Math.max(0, Math.min(100, percent));
        LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) fill.getLayoutParams();
        LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) rest.getLayoutParams();
        float wa = Math.max(p, 0.001f);
        float wb = Math.max(100f - p, 0.001f);
        if (Math.abs(a.weight - wa) > 0.01f || Math.abs(b.weight - wb) > 0.01f) {
            a.weight = wa;
            b.weight = wb;
            fill.setLayoutParams(a);
            rest.setLayoutParams(b);
        }
        if (color != currentColor) fill.setBackground(UiKit.shape(act, color, 0, UiKit.R_PILL));
        return color;
    }

    private TextView findSubtitle(LinearLayout header) {
        View col = header.getChildCount() > 0 ? header.getChildAt(0) : null;
        if (col instanceof LinearLayout) {
            LinearLayout c = (LinearLayout) col;
            if (c.getChildCount() > 1 && c.getChildAt(1) instanceof TextView) return (TextView) c.getChildAt(1);
            TextView t = UiKit.caption(act, "");
            c.addView(t);
            return t;
        }
        return UiKit.caption(act, "");
    }

    /** Poster with a rounded placeholder; decoded off the UI thread into a bounded LruCache. */
    private ImageView poster(JSONObject series, int wDp, int hDp) {
        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(UiKit.shape(act, AppTheme.inner(), 0, 8));
        iv.setClipToOutline(true);
        String url = null;
        JSONArray imgs = series.optJSONArray("images");
        for (int i = 0; imgs != null && i < imgs.length(); i++) {
            JSONObject im = imgs.optJSONObject(i);
            if (im == null || !"poster".equals(im.optString("coverType", ""))) continue;
            String local = im.optString("url", "");
            String remote = im.optString("remoteUrl", "");
            url = series.optInt("id") > 0 && !local.isEmpty() ? local : (!remote.isEmpty() ? remote : local);
            break;
        }
        if (url == null || url.isEmpty()) return iv;
        final int maxPx = dp(wDp) * 3 / 2;
        final String key = (maxPx <= 400 ? "s:" : "l:") + url;
        final String u = url;
        iv.setTag(key);
        Bitmap cached = imgCache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return iv;
        }
        if (imgFailed.contains(key)) return iv;
        List<ImageView> waiting = imgPending.get(key);
        if (waiting != null) {
            waiting.add(iv);
            return iv;
        }
        waiting = new ArrayList<>();
        waiting.add(iv);
        imgPending.put(key, waiting);
        try {
            imgPool.execute(() -> {
                Bitmap bmp = null;
                try {
                    bmp = SonarrApi.get(act).loadImage(u, maxPx);
                } catch (Throwable ignored) {
                    // placeholder stays
                }
                final Bitmap b = bmp;
                act.runOnUiThread(() -> {
                    List<ImageView> ws = imgPending.remove(key);
                    if (b == null) {
                        imgFailed.add(key);
                        return;
                    }
                    imgCache.put(key, b);
                    if (ws == null || dead()) return;
                    for (ImageView v : ws) if (key.equals(v.getTag())) v.setImageBitmap(b);
                });
            });
        } catch (RejectedExecutionException e) {
            imgPending.remove(key);
        }
        return iv;
    }

    private static void appendDot(StringBuilder sb, String part) {
        if (part == null || part.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(part);
    }

    private static void setText(TextView v, CharSequence s) {
        if (v == null) return;
        String n = s == null ? "" : s.toString();
        if (!n.contentEquals(v.getText())) v.setText(n);
    }

    private static String tf(String template, Object... args) {
        String s = I18n.tr(template);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    private int dp(float v) {
        return UiKit.dp(act, v);
    }

    // ================================================================== formatting

    /** Parses Sonarr's UTC timestamps ("2026-11-24T21:00:00Z", optional fraction); 0 when absent. */
    private static long parseUtc(String iso) {
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

    private static String fmtLocal(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    private static String fmtLocalDate(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    private static String fmtLocalTime(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    private static String fmtAge(double minutes) {
        if (minutes < 0 || Double.isNaN(minutes)) return "";
        if (minutes < 60) return (int) minutes + "m";
        if (minutes < 2880) return (int) (minutes / 60) + "h";
        return (int) (minutes / 1440) + "d";
    }
}
