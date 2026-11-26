package com.jellyarr.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.jellyarr.app.services.TransmissionApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "Transmission" tab: a native client for the local Transmission daemon.
 *
 * <ul>
 *   <li>Torrents - the main live list (polls every 3 s only while the tab is on screen; rows are
 *       updated in place so the scroll position stays put), filters, search, sort, and a detail page
 *       with files (wanted / priority), peers, trackers, info and actions.</li>
 *   <li>Add - magnet links / URLs / info hashes with an optional folder and start-paused.</li>
 *   <li>Speed - live rates, alternative (turtle) limits, global limits and statistics.</li>
 *   <li>Settings - folders, seeding, peers and queues, DHT/PEX/LPD, encryption, blocklist, port test.</li>
 *   <li>History &amp; cleanup - hosts the app's Download Center (history, clear and cleanup actions)
 *       injected through {@link #setCenterView(View, Runnable)}.</li>
 * </ul>
 */
public final class TransmissionTabView {

    private static final int S_TORRENTS = 0;
    private static final int S_ADD = 1;
    private static final int S_SPEED = 2;
    private static final int S_SETTINGS = 3;
    private static final int S_CENTER = 4;
    private static final String[] SECTION_NAMES = {"Torrents", "Add", "Speed", "Settings", "History & cleanup"};

    private static final int F_ALL = 0;
    private static final int F_DOWNLOADING = 1;
    private static final int F_SEEDING = 2;
    private static final int F_PAUSED = 3;
    private static final int F_COMPLETED = 4;
    private static final int F_ERROR = 5;
    private static final String[] FILTER_NAMES = {"All", "Downloading", "Seeding", "Paused", "Completed", "Error"};

    private static final String[] SORT_NAMES = {"Name", "Date added", "Progress", "Size", "Download speed",
            "Upload speed", "Ratio", "Queue"};

    private static final int D_FILES = 0;
    private static final int D_PEERS = 1;
    private static final int D_TRACKERS = 2;
    private static final int D_INFO = 3;
    private static final String[] DETAIL_TABS = {"Files", "Peers", "Trackers", "Info"};

    private static final long TICK_MS = 3000L;
    private static final int MAX_FILE_ROWS = 400;

    private interface Job<T> { T run(TransmissionApi api) throws Exception; }
    private interface Done<T> { void run(T value); }
    private interface Collect { void put(JSONObject o) throws Exception; }
    private interface IntDone { void run(int value); }

    private final Activity act;
    private final LinearLayout root;
    private final TextView headerSub;
    private final Button pauseAllBtn;
    private final Button resumeAllBtn;
    private final TextView[] navChips = new TextView[SECTION_NAMES.length];
    private final ScrollView scroll;
    private final LinearLayout content;
    /** Polling, reads and quick actions: one thread, so requests never overlap. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Long operations (add, move, blocklist, port test) so they never stall the live list. */
    private final ExecutorService slow = Executors.newSingleThreadExecutor();

    private int section = S_TORRENTS;
    private int gen = 0;
    private int detailId = 0;
    private int detailTab = D_FILES;
    private boolean pollPending = false;
    private boolean pollAgain = false;
    private boolean tickerOn = false;
    private boolean versionRequested = false;
    private String version = "";

    // torrents list state
    private JSONArray torrents = null;
    private int filter = F_ALL;
    private int sortKey = 1;
    private boolean sortDesc = true;
    private String query = "";
    private LinearLayout listShell = null;
    private LinearLayout rowsCard = null;
    private TextView[] filterChips = null;
    private int styledFilter = -1;
    private TextView countView = null;
    private LinearLayout banner = null;
    private TextView bannerText = null;
    private Button sortButton = null;
    private String emptyKey = null;
    private final Map<Integer, RowHolder> rowHolders = new HashMap<>();
    private final List<Integer> rowOrder = new ArrayList<>();
    private final Set<Integer> busyIds = new HashSet<>();
    private boolean failureShown = false;

    // detail state
    private DetailHolder detail = null;

    // speed state
    private TextView liveDown = null;
    private TextView liveUp = null;
    private TextView liveCounts = null;

    // History & cleanup (legacy Download Center)
    private View centerView = null;
    private Runnable centerRefresh = null;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!tickerOn) return;
            if (!wantTicker()) {
                stopTicker();
                return;
            }
            requestSilent();
            root.postDelayed(this, TICK_MS);
        }
    };

    public TransmissionTabView(Activity activity) {
        this.act = activity;

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

        // ---- page header: title, live subtitle, Pause All / Resume All
        LinearLayout actions = UiKit.row(act);
        pauseAllBtn = compact(UiKit.outline(act, I18n.tr("Pause All"), v -> pauseAll((Button) v)));
        resumeAllBtn = compact(UiKit.outline(act, I18n.tr("Resume All"), v -> resumeAll((Button) v)));
        actions.addView(pauseAllBtn, compactLp(false));
        actions.addView(resumeAllBtn, compactLp(true));
        LinearLayout header = UiKit.header(act, "Transmission", I18n.tr("Connecting…"), actions);
        header.setPadding(dp(16), dp(12), dp(16), dp(10));
        headerSub = UiKit.singleLine(findSubtitle(header));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ---- section chips
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

        // ---- scrolling content
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
        if (detailId > 0) {
            detailId = 0;
            detail = null;
            refresh();
            return true;
        }
        if (section != S_TORRENTS) {
            go(S_TORRENTS);
            return true;
        }
        return false;
    }

    public void refresh() {
        gen++;
        renderNav();
        liveDown = null;
        liveUp = null;
        liveCounts = null;
        if (detailId > 0) {
            boolean same = detail != null && detail.id == detailId && detail.root.getParent() == content;
            loadDetail(same);
        } else {
            detail = null;
            switch (section) {
                case S_TORRENTS: loadTorrents(false); break;
                case S_ADD: renderAdd(); break;
                case S_SPEED: loadSpeed(); break;
                case S_CENTER: renderCenter(); break;
                default: loadSettings(); break;
            }
        }
        syncTicker();
    }

    /** Hosts the app's Download Center (history / clear / cleanup) as the "History & cleanup" section. */
    public void setCenterView(View v, Runnable refreshAction) {
        centerView = v;
        centerRefresh = refreshAction;
    }

    private void go(int s) {
        section = s;
        detailId = 0;
        detail = null;
        scroll.scrollTo(0, 0);
        refresh();
    }

    private void renderNav() {
        for (int i = 0; i < navChips.length; i++) {
            UiKit.styleChip(act, navChips[i], i == section);
        }
    }

    // ================================================================== live ticker

    private boolean isLive() {
        return detailId > 0 || section == S_TORRENTS || section == S_SPEED;
    }

    private boolean wantTicker() {
        return root != null && isLive() && root.isAttachedToWindow()
                && root.getWindowVisibility() == View.VISIBLE && root.isShown();
    }

    private void syncTicker() {
        if (root == null) return;
        if (wantTicker()) {
            if (!tickerOn) {
                tickerOn = true;
                root.removeCallbacks(ticker);
                root.postDelayed(ticker, TICK_MS);
            }
        } else {
            stopTicker();
        }
    }

    private void stopTicker() {
        tickerOn = false;
        if (root != null) root.removeCallbacks(ticker);
    }

    /** Refreshes the current live screen without a loading state; never stacks requests. */
    private void requestSilent() {
        if (detailId > 0) loadDetail(true);
        else if (section == S_TORRENTS) loadTorrents(true);
        else if (section == S_SPEED) tickSpeed();
    }

    private boolean beginPoll() {
        if (pollPending) {
            pollAgain = true;
            return false;
        }
        pollPending = true;
        return true;
    }

    private void pollDone() {
        pollPending = false;
        if (pollAgain) {
            pollAgain = false;
            root.postDelayed(() -> {
                if (wantTicker()) requestSilent();
            }, 150);
        }
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
                    result = job.run(TransmissionApi.get(act));
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
            // executor shut down
        }
    }

    /** Runs an action with its button disabled, toasts the outcome and refreshes the live screen. */
    private void runAction(final View button, final Job<Object> job, final String okMsg) {
        setBusy(button, true);
        async(io, job, v -> {
            setBusy(button, false);
            if (okMsg != null) toast(okMsg);
            requestSilent();
        }, t -> {
            setBusy(button, false);
            toast(msg(t));
            requestSilent();
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

    private void pauseAll(final Button b) {
        setBusy(resumeAllBtn, true);
        setBusy(b, true);
        async(io, api -> { api.torrentStop(null); return null; }, v -> {
            setBusy(b, false);
            setBusy(resumeAllBtn, false);
            toast("All torrents paused.");
            requestSilent();
        }, t -> {
            setBusy(b, false);
            setBusy(resumeAllBtn, false);
            toast(msg(t));
        });
    }

    private void resumeAll(final Button b) {
        setBusy(pauseAllBtn, true);
        setBusy(b, true);
        async(io, api -> { api.torrentStart(null); return null; }, v -> {
            setBusy(b, false);
            setBusy(pauseAllBtn, false);
            toast("All torrents resumed.");
            requestSilent();
        }, t -> {
            setBusy(b, false);
            setBusy(pauseAllBtn, false);
            toast(msg(t));
        });
    }

    // ================================================================== screen states

    private void clearScreenRefs() {
        listShell = null;
        rowsCard = null;
        filterChips = null;
        styledFilter = -1;
        countView = null;
        banner = null;
        bannerText = null;
        sortButton = null;
        emptyKey = null;
        rowHolders.clear();
        rowOrder.clear();
        failureShown = false;
    }

    private void showState(View v) {
        content.removeAllViews();
        clearScreenRefs();
        content.addView(v);
    }

    private void showLoading(String what) {
        showState(UiKit.loading(act, I18n.tr(what)));
    }

    private void showFailure(Throwable t, String what) {
        View v;
        if (TransmissionApi.isNotRunning(t)) {
            v = UiKit.error(act, I18n.tr("Transmission is not running"),
                    I18n.tr("Start it from the Services tab, then retry."), I18n.tr("Retry"), x -> refresh());
            headerSub.setText(I18n.tr("Not running"));
        } else if (TransmissionApi.isAuthError(t)) {
            v = UiKit.error(act, I18n.tr("Transmission login failed"),
                    I18n.tr("Check the account in Settings > Security & Identity, then retry."), I18n.tr("Retry"), x -> refresh());
            headerSub.setText(I18n.tr("Login failed"));
        } else {
            v = UiKit.error(act, I18n.tr(what), I18n.tr(msg(t)), I18n.tr("Retry"), x -> refresh());
            headerSub.setText(I18n.tr("Offline"));
        }
        showState(v);
        failureShown = true;
    }

    // ================================================================== header

    private void updateHeader(JSONArray arr) {
        long down = 0, up = 0;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            down += t.optLong("rateDownload");
            up += t.optLong("rateUpload");
        }
        setHeaderSpeeds(down, up, arr == null ? -1 : arr.length());
        if (!versionRequested) {
            versionRequested = true;
            async(io, api -> {
                String sv = api.sessionGet().optString("version", "");
                return sv;
            }, v -> {
                String s = v == null ? "" : v.trim();
                int sp = s.indexOf(' ');
                version = sp > 0 ? s.substring(0, sp) : s;
            }, t -> versionRequested = false);
        }
    }

    private void setHeaderSpeeds(long down, long up, int count) {
        StringBuilder sb = new StringBuilder();
        if (!version.isEmpty()) sb.append('v').append(version).append(" · ");
        sb.append("↓ ").append(fmtSpeed(down)).append(" · ↑ ").append(fmtSpeed(up));
        if (count >= 0) sb.append(" · ").append(count == 1 ? I18n.tr("1 torrent") : tf("{0} torrents", count));
        setText(headerSub, sb.toString());
    }

    // ================================================================== TORRENTS list

    private void loadTorrents(final boolean silent) {
        final int g = gen;
        boolean shellReady = listShell != null && listShell.getParent() == content;
        if (silent) {
            if (!beginPoll()) return;
        } else if (!shellReady) {
            if (torrents != null) {
                buildListShell();
                fillRows();
            } else {
                showLoading("Loading torrents…");
            }
        }
        async(io, api -> api.torrentList(), arr -> {
            if (silent) pollDone();
            if (g != gen || detailId > 0 || section != S_TORRENTS) return;
            torrents = arr;
            if (listShell == null || listShell.getParent() != content) buildListShell();
            setBanner(null);
            fillRows();
            updateHeader(arr);
        }, t -> {
            if (silent) pollDone();
            if (g != gen || detailId > 0 || section != S_TORRENTS) return;
            boolean shell = listShell != null && listShell.getParent() == content && torrents != null;
            if (shell) {
                setBanner(t);
                headerSub.setText(I18n.tr(TransmissionApi.isNotRunning(t) ? "Not running" : "Offline"));
            } else if (!silent || !failureShown) {
                showFailure(t, "Could not load torrents");
            }
        });
    }

    private void setBanner(Throwable t) {
        if (banner == null) return;
        if (t == null) {
            banner.setVisibility(View.GONE);
            return;
        }
        String m;
        if (TransmissionApi.isNotRunning(t)) m = I18n.tr("Transmission stopped responding. Showing the last known list.");
        else if (TransmissionApi.isAuthError(t)) m = I18n.tr(TransmissionApi.AUTH_FAILED);
        else m = I18n.tr(msg(t));
        setText(bannerText, m);
        banner.setVisibility(View.VISIBLE);
    }

    private void buildListShell() {
        content.removeAllViews();
        clearScreenRefs();
        listShell = UiKit.column(act);

        banner = UiKit.column(act);
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        banner.setBackground(UiKit.shape(act, AppTheme.dangerContainer(), 0, UiKit.R_INNER));
        bannerText = UiKit.body(act, "");
        bannerText.setTextColor(AppTheme.text());
        banner.addView(bannerText);
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.bottomMargin = dp(12);
        listShell.addView(banner, blp);

        // search + sort
        LinearLayout bar = UiKit.row(act);
        final EditText search = UiKit.input(act, I18n.tr("Search torrents"));
        search.setText(query);
        search.setSelection(search.getText().length());
        bar.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1.0f));
        sortButton = UiKit.outline(act, sortLabel(), v -> showSortDialog());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, dp(44));
        slp.setMarginStart(dp(8));
        bar.addView(sortButton, slp);
        listShell.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        // filter chips (counts updated in place)
        LinearLayout chips = UiKit.row(act);
        chips.setPadding(0, dp(12), 0, dp(4));
        filterChips = new TextView[FILTER_NAMES.length];
        for (int f = 0; f < FILTER_NAMES.length; f++) {
            final int fi = f;
            TextView c = UiKit.chip(act, I18n.tr(FILTER_NAMES[f]), f == filter);
            c.setOnClickListener(v -> {
                filter = fi;
                scroll.scrollTo(0, 0);
                fillRows();
            });
            filterChips[f] = c;
            chips.addView(c, UiKit.chipLp(act));
        }
        styledFilter = filter;
        listShell.addView(UiKit.chipScroller(act, chips), new LinearLayout.LayoutParams(-1, -2));

        countView = UiKit.overline(act, "");
        countView.setPadding(0, dp(12), 0, dp(8));
        listShell.addView(countView);

        rowsCard = UiKit.card(act);
        rowsCard.setPadding(dp(4), dp(4), dp(4), dp(4));
        listShell.addView(rowsCard);

        content.addView(listShell, new LinearLayout.LayoutParams(-1, -2));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = e.toString();
                fillRows();
            }
        });
    }

    private String sortLabel() {
        return I18n.tr("Sort") + ": " + I18n.tr(SORT_NAMES[sortKey]) + (sortDesc ? " ↓" : " ↑");
    }

    private void showSortDialog() {
        String[] items = new String[SORT_NAMES.length];
        for (int i = 0; i < items.length; i++) items[i] = SORT_NAMES[i];
        choose("Sort by", items, sortKey, idx -> {
            if (idx == sortKey) sortDesc = !sortDesc;
            else {
                sortKey = idx;
                sortDesc = idx != 0 && idx != 7;
            }
            if (sortButton != null) sortButton.setText(sortLabel());
            fillRows();
        });
    }

    private void fillRows() {
        if (rowsCard == null || torrents == null) return;
        int[] counts = new int[FILTER_NAMES.length];
        List<JSONObject> list = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < torrents.length(); i++) {
            JSONObject t = torrents.optJSONObject(i);
            if (t == null) continue;
            for (int f = 0; f < counts.length; f++) if (matches(t, f)) counts[f]++;
            if (!matches(t, filter)) continue;
            if (!q.isEmpty() && !t.optString("name", "").toLowerCase(Locale.ROOT).contains(q)) continue;
            list.add(t);
        }

        if (filterChips != null) {
            for (int f = 0; f < filterChips.length; f++) {
                setText(filterChips[f], I18n.tr(FILTER_NAMES[f]) + "  " + counts[f]);
            }
            if (styledFilter != filter) {
                for (int f = 0; f < filterChips.length; f++) UiKit.styleChip(act, filterChips[f], f == filter);
                styledFilter = filter;
            }
        }

        final int key = sortKey;
        final int dir = sortDesc ? -1 : 1;
        Collections.sort(list, (a, b) -> {
            int c = dir * compare(a, b, key);
            return c != 0 ? c : Integer.compare(a.optInt("id"), b.optInt("id"));
        });

        setText(countView, tf("{0} of {1} torrents", list.size(), torrents.length()));

        if (list.isEmpty()) {
            String k = torrents.length() == 0 ? "none" : "nomatch";
            if (k.equals(emptyKey) && rowOrder.isEmpty()) return;
            rowsCard.removeAllViews();
            rowHolders.clear();
            rowOrder.clear();
            emptyKey = k;
            if (torrents.length() == 0) {
                LinearLayout box = UiKit.column(act);
                box.addView(UiKit.empty(act, I18n.tr("No torrents yet"),
                        I18n.tr("Add a magnet link, a .torrent URL or an info hash.")));
                Button add = UiKit.tonal(act, I18n.tr("Add Torrent"), v -> go(S_ADD));
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, dp(42));
                alp.gravity = Gravity.CENTER_HORIZONTAL;
                alp.bottomMargin = dp(20);
                box.addView(add, alp);
                rowsCard.addView(box, new LinearLayout.LayoutParams(-1, -2));
            } else {
                rowsCard.addView(UiKit.empty(act, I18n.tr("No matches"),
                        I18n.tr("Try another filter or search term.")), new LinearLayout.LayoutParams(-1, -2));
            }
            return;
        }
        emptyKey = null;

        List<Integer> ids = new ArrayList<>();
        for (JSONObject t : list) ids.add(t.optInt("id"));
        if (ids.equals(rowOrder)) {
            for (JSONObject t : list) {
                RowHolder h = rowHolders.get(t.optInt("id"));
                if (h != null) h.bind(t);
            }
            return;
        }

        // Order or membership changed: re-attach (reusing existing rows) and keep the scroll position.
        final int y = scroll.getScrollY();
        Map<Integer, RowHolder> old = new HashMap<>(rowHolders);
        rowsCard.removeAllViews();
        rowHolders.clear();
        rowOrder.clear();
        for (int i = 0; i < list.size(); i++) {
            JSONObject t = list.get(i);
            int id = t.optInt("id");
            RowHolder h = old.get(id);
            if (h == null) h = new RowHolder();
            if (i > 0) rowsCard.addView(listDivider());
            h.bind(t);
            rowsCard.addView(h.view, new LinearLayout.LayoutParams(-1, -2));
            rowHolders.put(id, h);
            rowOrder.add(id);
        }
        scroll.post(() -> scroll.scrollTo(0, y));
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

    private static int compare(JSONObject a, JSONObject b, int key) {
        switch (key) {
            case 0: return a.optString("name", "").compareToIgnoreCase(b.optString("name", ""));
            case 1: return Long.compare(a.optLong("addedDate"), b.optLong("addedDate"));
            case 2: return Double.compare(progressOf(a), progressOf(b));
            case 3: return Long.compare(a.optLong("sizeWhenDone"), b.optLong("sizeWhenDone"));
            case 4: return Long.compare(a.optLong("rateDownload"), b.optLong("rateDownload"));
            case 5: return Long.compare(a.optLong("rateUpload"), b.optLong("rateUpload"));
            case 6: return Double.compare(a.optDouble("uploadRatio", 0), b.optDouble("uploadRatio", 0));
            default: return Integer.compare(a.optInt("queuePosition"), b.optInt("queuePosition"));
        }
    }

    private static boolean isDone(JSONObject t) {
        return t.optDouble("percentDone", 0) >= 1.0
                || (t.optLong("sizeWhenDone") > 0 && t.optLong("leftUntilDone", 1) == 0);
    }

    private static boolean hasError(JSONObject t) {
        int e = t.optInt("error");
        return e == TransmissionApi.ERR_TRACKER_ERROR || e == TransmissionApi.ERR_LOCAL;
    }

    private static boolean matches(JSONObject t, int f) {
        int st = t.optInt("status");
        switch (f) {
            case F_DOWNLOADING: return st == TransmissionApi.ST_DOWNLOADING || st == TransmissionApi.ST_DOWNLOAD_WAIT;
            case F_SEEDING: return st == TransmissionApi.ST_SEEDING || st == TransmissionApi.ST_SEED_WAIT;
            case F_PAUSED: return st == TransmissionApi.ST_STOPPED && !isDone(t);
            case F_COMPLETED: return isDone(t);
            case F_ERROR: return hasError(t);
            default: return true;
        }
    }

    /** 0..1: verification progress while checking, metadata progress for fresh magnets, else percentDone. */
    private static double progressOf(JSONObject t) {
        int st = t.optInt("status");
        if (st == TransmissionApi.ST_CHECKING) return t.optDouble("recheckProgress", 0);
        if (t.optDouble("metadataPercentComplete", 1) < 1.0) return t.optDouble("metadataPercentComplete", 0);
        return t.optDouble("percentDone", 0);
    }

    private static String statusLabel(JSONObject t) {
        if (hasError(t)) return "Error";
        switch (t.optInt("status")) {
            case TransmissionApi.ST_STOPPED: return isDone(t) ? "Completed" : "Paused";
            case TransmissionApi.ST_CHECK_WAIT: return "Queued to verify";
            case TransmissionApi.ST_CHECKING: return "Verifying";
            case TransmissionApi.ST_DOWNLOAD_WAIT: return "Queued";
            case TransmissionApi.ST_DOWNLOADING:
                return t.optDouble("metadataPercentComplete", 1) < 1.0 ? "Fetching metadata" : "Downloading";
            case TransmissionApi.ST_SEED_WAIT: return "Queued to seed";
            case TransmissionApi.ST_SEEDING: return "Seeding";
            default: return "Unknown";
        }
    }

    /** Downloading = accent, seeding / completed = success, paused = muted, error = danger, waiting = warning. */
    private static int stateColor(JSONObject t) {
        if (hasError(t)) return AppTheme.danger();
        switch (t.optInt("status")) {
            case TransmissionApi.ST_STOPPED: return isDone(t) ? AppTheme.success() : AppTheme.textMuted();
            case TransmissionApi.ST_DOWNLOADING: return AppTheme.accent();
            case TransmissionApi.ST_SEEDING:
            case TransmissionApi.ST_SEED_WAIT: return AppTheme.success();
            default: return AppTheme.warning();
        }
    }

    private static String sizeLine(JSONObject t, double pct) {
        long want = t.optLong("sizeWhenDone");
        long have = Math.max(0, want - t.optLong("leftUntilDone"));
        String s = fmtPct(pct);
        if (want > 0 && !isDone(t)) s += " · " + fmtSize(have) + " / " + fmtSize(want);
        else if (want > 0) s += " · " + fmtSize(want);
        return s;
    }

    private static String rateLine(JSONObject t) {
        int st = t.optInt("status");
        StringBuilder sb = new StringBuilder();
        sb.append("↓ ").append(fmtSpeed(t.optLong("rateDownload")))
                .append(" · ↑ ").append(fmtSpeed(t.optLong("rateUpload")));
        if (st == TransmissionApi.ST_DOWNLOADING) sb.append(" · ").append(I18n.tr("ETA")).append(' ').append(fmtEta(t.optLong("eta", -1)));
        if (st == TransmissionApi.ST_DOWNLOADING || st == TransmissionApi.ST_SEEDING) {
            sb.append(" · ").append(tf("Peers {0}/{1}", t.optInt("peersSendingToUs") + t.optInt("peersGettingFromUs"),
                    t.optInt("peersConnected")));
        }
        sb.append(" · ").append(tf("Ratio {0}", fmtRatio(t.optDouble("uploadRatio", -1))));
        return sb.toString();
    }

    /** One torrent row, updated in place on every poll. */
    private final class RowHolder {
        int id;
        boolean stopped;
        final LinearLayout view;
        final TextView name;
        final Button quick;
        final LinearLayout metaRow;
        String badgeKey = "";
        final TextView meta;
        final View progress;
        int progressColor;
        final TextView rates;
        final TextView error;

        RowHolder() {
            view = UiKit.column(act);
            view.setPadding(dp(12), dp(12), dp(12), dp(12));
            view.setBackground(UiKit.rowRipple(act));
            view.setClickable(true);
            view.setFocusable(true);
            view.setOnClickListener(v -> openDetail(id));

            LinearLayout top = UiKit.row(act);
            name = UiKit.singleLine(UiKit.text(act, "", 14f, AppTheme.text(), UiKit.MEDIUM));
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
            quick = compact(UiKit.outline(act, "", null));
            quick.setOnClickListener(v -> toggleTorrent(id, stopped, v));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, dp(32));
            qlp.setMarginStart(dp(10));
            top.addView(quick, qlp);
            view.addView(top);

            metaRow = UiKit.row(act);
            metaRow.setPadding(0, dp(6), 0, 0);
            metaRow.addView(UiKit.badge(act, "", AppTheme.textMuted()));
            meta = UiKit.singleLine(UiKit.caption(act, ""));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            mlp.setMarginStart(dp(8));
            metaRow.addView(meta, mlp);
            view.addView(metaRow);

            progressColor = AppTheme.accent();
            progress = UiKit.progress(act, 0, progressColor);
            view.addView(progress);

            rates = UiKit.singleLine(UiKit.caption(act, ""));
            rates.setPadding(0, dp(6), 0, 0);
            view.addView(rates);

            error = UiKit.caption(act, "");
            error.setMaxLines(2);
            error.setEllipsize(TextUtils.TruncateAt.END);
            error.setPadding(0, dp(4), 0, 0);
            error.setVisibility(View.GONE);
            view.addView(error);
        }

        void bind(JSONObject t) {
            id = t.optInt("id");
            stopped = t.optInt("status") == TransmissionApi.ST_STOPPED;
            setText(name, t.optString("name", "?"));
            setText(quick, I18n.tr(stopped ? "Resume" : "Pause"));
            boolean busy = busyIds.contains(id);
            if (quick.isEnabled() == busy) setBusy(quick, busy);

            String label = statusLabel(t);
            int color = stateColor(t);
            String key = label + "|" + color;
            if (!key.equals(badgeKey)) {
                badgeKey = key;
                metaRow.removeViewAt(0);
                metaRow.addView(UiKit.badge(act, I18n.tr(label), color), 0);
            }
            double pct = progressOf(t);
            setText(meta, sizeLine(t, pct));
            progressColor = updateProgress(progress, pct * 100.0, color, progressColor);
            setText(rates, rateLine(t));
            bindError(error, t);
        }
    }

    private void bindError(TextView error, JSONObject t) {
        int e = t.optInt("error");
        String es = t.optString("errorString", "");
        if (e != TransmissionApi.ERR_NONE && !es.isEmpty()) {
            setText(error, (e == TransmissionApi.ERR_TRACKER_WARNING ? I18n.tr("Tracker warning") + ": " : "") + es);
            error.setTextColor(e == TransmissionApi.ERR_TRACKER_WARNING ? AppTheme.warning() : AppTheme.danger());
            error.setVisibility(View.VISIBLE);
        } else if (error.getVisibility() != View.GONE) {
            error.setVisibility(View.GONE);
        }
    }

    private void toggleTorrent(final int id, final boolean wasStopped, final View button) {
        final int[] ids = new int[]{id};
        busyIds.add(id);
        setBusy(button, true);
        async(io, api -> {
            if (wasStopped) api.torrentStart(ids);
            else api.torrentStop(ids);
            return null;
        }, v -> {
            busyIds.remove(id);
            setBusy(button, false);
            requestSilent();
        }, t -> {
            busyIds.remove(id);
            setBusy(button, false);
            toast(msg(t));
        });
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

    private void openDetail(int id) {
        if (id <= 0) return;
        detailId = id;
        detailTab = D_FILES;
        detail = null;
        scroll.scrollTo(0, 0);
        refresh();
    }

    // ================================================================== torrent DETAIL

    private void loadDetail(final boolean silent) {
        final int g = gen;
        final int id = detailId;
        if (silent) {
            if (!beginPoll()) return;
        } else {
            showLoading("Loading torrent…");
        }
        async(io, api -> api.torrentDetails(id), t -> {
            if (silent) pollDone();
            if (g != gen || id != detailId) return;
            if (detail != null && detail.id == id && detail.root.getParent() == content) detail.update(t);
            else renderDetail(t);
        }, e -> {
            if (silent) pollDone();
            if (g != gen || id != detailId) return;
            boolean shown = detail != null && detail.id == id && detail.root.getParent() == content;
            if (msg(e).startsWith("Torrent not found")) {
                toast("This torrent was removed.");
                detailId = 0;
                detail = null;
                refresh();
            } else if (shown) {
                headerSub.setText(I18n.tr(TransmissionApi.isNotRunning(e) ? "Not running" : "Offline"));
            } else if (!silent || !failureShown) {
                showFailure(e, "Could not load the torrent");
            }
        });
    }

    private void renderDetail(final JSONObject t) {
        content.removeAllViews();
        clearScreenRefs();
        detail = new DetailHolder(t);
        content.addView(detail.root, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The detail page; refreshed in place by the ticker. */
    private final class DetailHolder {
        final int id;
        final LinearLayout root;
        final TextView name;
        final LinearLayout metaRow;
        String badgeKey = "";
        final TextView meta;
        final View progress;
        int progressColor;
        final TextView rates;
        final TextView error;
        final Button toggle;
        boolean stopped;
        final TextView[] tabChips = new TextView[DETAIL_TABS.length];
        final LinearLayout body;
        JSONObject last;
        String bodySignature = "";
        final List<FileHolder> files = new ArrayList<>();

        DetailHolder(JSONObject t) {
            id = t.optInt("id");
            last = t;
            root = UiKit.column(act);

            Button back = compact(UiKit.outline(act, I18n.tr("Back"), v -> onBack()));
            LinearLayout.LayoutParams bk = new LinearLayout.LayoutParams(-2, dp(34));
            bk.bottomMargin = dp(12);
            root.addView(back, bk);

            LinearLayout card = UiKit.card(act);
            name = UiKit.title(act, t.optString("name", "?"));
            name.setMaxLines(3);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextIsSelectable(true);
            card.addView(name);
            metaRow = UiKit.row(act);
            metaRow.setPadding(0, dp(8), 0, 0);
            metaRow.addView(UiKit.badge(act, "", AppTheme.textMuted()));
            meta = UiKit.singleLine(UiKit.caption(act, ""));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            mlp.setMarginStart(dp(8));
            metaRow.addView(meta, mlp);
            card.addView(metaRow);
            progressColor = AppTheme.accent();
            progress = UiKit.progress(act, 0, progressColor);
            card.addView(progress);
            rates = UiKit.caption(act, "");
            rates.setPadding(0, dp(8), 0, 0);
            card.addView(rates);
            error = UiKit.caption(act, "");
            error.setPadding(0, dp(4), 0, 0);
            error.setVisibility(View.GONE);
            card.addView(error);

            final int[] ids = new int[]{id};
            toggle = UiKit.primary(act, "", v -> {
                final boolean wasStopped = stopped;
                runAction(v, api -> {
                    if (wasStopped) api.torrentStart(ids);
                    else api.torrentStop(ids);
                    return null;
                }, wasStopped ? "Resumed." : "Paused.");
            });
            Button verify = UiKit.outline(act, I18n.tr("Verify"), v ->
                    runAction(v, api -> { api.torrentVerify(ids); return null; }, "Verifying local data…"));
            Button announce = UiKit.outline(act, I18n.tr("Reannounce"), v ->
                    runAction(v, api -> { api.torrentReannounce(ids); return null; }, "Reannounce requested."));
            card.addView(UiKit.buttonRow(act, toggle, verify, announce));

            Button move = UiKit.outline(act, I18n.tr("Move"), v -> showMoveDialog(id, last.optString("downloadDir", "")));
            Button queue = UiKit.outline(act, I18n.tr("Queue"), v -> showQueueDialog(id));
            Button remove = UiKit.danger(act, I18n.tr("Remove"), v -> showRemoveDialog(id, last.optString("name", "")));
            LinearLayout r2 = UiKit.buttonRow(act, move, queue, remove);
            card.addView(r2);
            root.addView(card);

            LinearLayout chips = UiKit.row(act);
            chips.setPadding(0, dp(4), 0, dp(12));
            for (int i = 0; i < DETAIL_TABS.length; i++) {
                final int ti = i;
                TextView c = UiKit.chip(act, I18n.tr(DETAIL_TABS[i]), i == detailTab);
                c.setOnClickListener(v -> selectTab(ti));
                tabChips[i] = c;
                chips.addView(c, UiKit.chipLp(act));
            }
            root.addView(UiKit.chipScroller(act, chips), new LinearLayout.LayoutParams(-1, -2));

            body = UiKit.column(act);
            root.addView(body, new LinearLayout.LayoutParams(-1, -2));

            bindSummary(t);
            renderBody(t, true);
        }

        void selectTab(int ti) {
            if (ti == detailTab) return;
            detailTab = ti;
            for (int i = 0; i < tabChips.length; i++) UiKit.styleChip(act, tabChips[i], i == detailTab);
            renderBody(last, true);
            requestSilent();
        }

        void update(JSONObject t) {
            last = t;
            bindSummary(t);
            renderBody(t, false);
        }

        void bindSummary(JSONObject t) {
            stopped = t.optInt("status") == TransmissionApi.ST_STOPPED;
            setText(name, t.optString("name", "?"));
            String label = statusLabel(t);
            int color = stateColor(t);
            String key = label + "|" + color;
            if (!key.equals(badgeKey)) {
                badgeKey = key;
                metaRow.removeViewAt(0);
                metaRow.addView(UiKit.badge(act, I18n.tr(label), color), 0);
            }
            double pct = progressOf(t);
            setText(meta, sizeLine(t, pct));
            progressColor = updateProgress(progress, pct * 100.0, color, progressColor);
            String r = "↓ " + fmtSpeed(t.optLong("rateDownload")) + " · ↑ " + fmtSpeed(t.optLong("rateUpload"))
                    + " · " + I18n.tr("ETA") + " " + fmtEta(t.optLong("eta", -1))
                    + " · " + tf("Ratio {0}", fmtRatio(t.optDouble("uploadRatio", -1)))
                    + " · " + tf("Peers {0}/{1}", t.optInt("peersSendingToUs") + t.optInt("peersGettingFromUs"),
                    t.optInt("peersConnected"));
            setText(rates, r);
            bindError(error, t);
            setText(toggle, I18n.tr(stopped ? "Resume" : "Pause"));
        }

        void renderBody(JSONObject t, boolean force) {
            if (detailTab == D_FILES) {
                JSONArray fl = t.optJSONArray("files");
                JSONArray st = t.optJSONArray("fileStats");
                int n = fl == null ? 0 : Math.min(fl.length(), MAX_FILE_ROWS);
                if (!force && n > 0 && n == files.size() && body.getChildCount() > 0) {
                    for (int i = 0; i < n; i++) {
                        JSONObject f = fl.optJSONObject(i);
                        if (f != null) files.get(i).bind(f, st == null ? null : st.optJSONObject(i));
                    }
                    return;
                }
                body.removeAllViews();
                files.clear();
                bodySignature = "";
                renderFiles(this, t);
                return;
            }
            String sig;
            if (detailTab == D_PEERS) sig = String.valueOf(t.optJSONArray("peers"));
            else if (detailTab == D_TRACKERS) sig = String.valueOf(t.optJSONArray("trackerStats"));
            else sig = infoSignature(t);
            if (!force && sig.equals(bodySignature)) return;
            bodySignature = sig;
            files.clear();
            final int y = scroll.getScrollY();
            body.removeAllViews();
            if (detailTab == D_PEERS) renderPeers(body, t);
            else if (detailTab == D_TRACKERS) renderTrackers(body, t);
            else renderInfo(body, t);
            if (!force) scroll.post(() -> scroll.scrollTo(0, y));
        }
    }

    private static String infoSignature(JSONObject t) {
        String[] keys = {"downloadDir", "labels", "bandwidthPriority", "downloadLimited", "downloadLimit",
                "uploadLimited", "uploadLimit", "doneDate", "corruptEver", "isPrivate", "magnetLink"};
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(k).append('=').append(t.opt(k)).append(';');
        sb.append(t.optLong("downloadedEver") / (1024 * 1024)).append(';').append(t.optLong("uploadedEver") / (1024 * 1024));
        return sb.toString();
    }

    private void renderFiles(final DetailHolder dh, JSONObject t) {
        final int id = t.optInt("id");
        final JSONArray files = t.optJSONArray("files");
        final JSONArray stats = t.optJSONArray("fileStats");
        if (files == null || files.length() == 0) {
            dh.body.addView(UiKit.empty(act, I18n.tr("No file list yet"),
                    I18n.tr("Transmission is still fetching the torrent's metadata.")));
            return;
        }
        final int n = files.length();
        Button all = UiKit.outline(act, I18n.tr("Select All"), null);
        Button none = UiKit.outline(act, I18n.tr("Select None"), null);
        all.setOnClickListener(v -> bulkWanted(v, id, n, true));
        none.setOnClickListener(v -> bulkWanted(v, id, n, false));
        LinearLayout bulk = UiKit.buttonRow(act, all, none);
        ((LinearLayout.LayoutParams) bulk.getLayoutParams()).topMargin = 0;
        ((LinearLayout.LayoutParams) bulk.getLayoutParams()).bottomMargin = dp(12);
        dh.body.addView(bulk);

        LinearLayout card = UiKit.card(act);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        int shown = Math.min(n, MAX_FILE_ROWS);
        for (int i = 0; i < shown; i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            FileHolder h = new FileHolder(id, i);
            h.bind(f, stats == null ? null : stats.optJSONObject(i));
            if (card.getChildCount() > 0) card.addView(listDivider());
            card.addView(h.view, new LinearLayout.LayoutParams(-1, -2));
            dh.files.add(h);
        }
        dh.body.addView(card);
        if (n > shown) {
            TextView more = UiKit.caption(act, tf("Showing the first {0} of {1} files.", shown, n));
            more.setPadding(0, 0, 0, dp(8));
            dh.body.addView(more);
        }
    }

    /** One file row: wanted switch, name, progress and priority. */
    private final class FileHolder {
        final int index;
        final LinearLayout view;
        final Switch sw;
        final TextView name;
        final TextView info;
        final Button prio;
        int priority;
        boolean binding;

        FileHolder(final int torrentId, int index) {
            this.index = index;
            view = UiKit.row(act);
            view.setPadding(dp(8), dp(10), dp(8), dp(10));

            sw = UiStyle.newSwitch(act);
            UiStyle.styleSwitch(act, sw);
            view.addView(sw);

            LinearLayout tx = UiKit.column(act);
            name = UiKit.text(act, "", 13.5f, AppTheme.text(), null);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            tx.addView(name);
            info = UiKit.singleLine(UiKit.caption(act, ""));
            info.setPadding(0, dp(2), 0, 0);
            tx.addView(info);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            tlp.setMarginStart(dp(10));
            tlp.setMarginEnd(dp(8));
            view.addView(tx, tlp);

            prio = compact(UiKit.outline(act, "", null));
            prio.setOnClickListener(v -> choose("File priority", new String[]{"Low", "Normal", "High"}, priority + 1, sel -> {
                final int[] idx = new int[]{this.index};
                runAction(v, api -> { api.setFilePriority(torrentId, idx, sel - 1); return null; }, null);
            }));
            view.addView(prio, new LinearLayout.LayoutParams(-2, dp(32)));

            sw.setOnCheckedChangeListener((b, checked) -> {
                if (binding) return;
                final int[] idx = new int[]{this.index};
                sw.setEnabled(false);
                async(io, api -> { api.setFilesWanted(torrentId, idx, checked); return null; }, r -> {
                    sw.setEnabled(true);
                    name.setTextColor(checked ? AppTheme.text() : AppTheme.textMuted());
                    requestSilent();
                }, e -> {
                    sw.setEnabled(true);
                    binding = true;
                    sw.setChecked(!checked);
                    binding = false;
                    toast(msg(e));
                });
            });
        }

        void bind(JSONObject f, JSONObject fs) {
            long len = f.optLong("length");
            long done = fs != null ? fs.optLong("bytesCompleted") : f.optLong("bytesCompleted");
            boolean wanted = fs == null || fs.optBoolean("wanted", true);
            priority = fs == null ? 0 : Math.max(-1, Math.min(1, fs.optInt("priority", 0)));
            setText(name, f.optString("name", "?"));
            name.setTextColor(wanted ? AppTheme.text() : AppTheme.textMuted());
            double fp = len > 0 ? (double) done / (double) len : 0;
            setText(info, fmtPct(fp) + " · " + fmtSize(done) + " / " + fmtSize(len));
            if (sw.isEnabled() && sw.isChecked() != wanted) {
                binding = true;
                sw.setChecked(wanted);
                binding = false;
            }
            setText(prio, I18n.tr(priority > 0 ? "High" : priority < 0 ? "Low" : "Normal"));
            prio.setTextColor(priority > 0 ? AppTheme.warning() : AppTheme.text());
        }
    }

    private void bulkWanted(View button, final int id, final int n, final boolean wanted) {
        final int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i;
        runAction(button, api -> { api.setFilesWanted(id, all, wanted); return null; },
                wanted ? "All files selected." : "All files deselected.");
    }

    private void renderPeers(LinearLayout body, JSONObject t) {
        JSONArray peers = t.optJSONArray("peers");
        if (peers == null || peers.length() == 0) {
            body.addView(UiKit.empty(act, I18n.tr("No connected peers"), null));
            return;
        }
        LinearLayout card = UiKit.card(act);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < peers.length(); i++) {
            JSONObject p = peers.optJSONObject(i);
            if (p == null) continue;
            if (card.getChildCount() > 0) card.addView(listDivider());
            LinearLayout row = listRow();
            row.addView(UiKit.singleLine(UiKit.text(act, p.optString("address", "?") + ":" + p.optInt("port"),
                    14f, AppTheme.text(), UiKit.MEDIUM)));
            String client = p.optString("clientName", "");
            TextView l1 = UiKit.singleLine(UiKit.caption(act, (client.isEmpty() ? I18n.tr("Unknown client") : client)
                    + " · " + fmtPct(p.optDouble("progress", 0))
                    + (p.optString("flagStr", "").isEmpty() ? "" : " · " + p.optString("flagStr", ""))));
            l1.setPadding(0, dp(2), 0, 0);
            row.addView(l1);
            TextView l2 = UiKit.caption(act, "↓ " + fmtSpeed(p.optLong("rateToClient")) + " · ↑ " + fmtSpeed(p.optLong("rateToPeer")));
            l2.setPadding(0, dp(2), 0, 0);
            row.addView(l2);
            card.addView(row);
        }
        body.addView(card);
    }

    private void renderTrackers(LinearLayout body, JSONObject t) {
        JSONArray ts = t.optJSONArray("trackerStats");
        if (ts == null || ts.length() == 0) {
            body.addView(UiKit.empty(act, I18n.tr("No trackers"),
                    I18n.tr("This torrent relies on DHT and peer exchange.")));
            return;
        }
        LinearLayout card = UiKit.card(act);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < ts.length(); i++) {
            JSONObject k = ts.optJSONObject(i);
            if (k == null) continue;
            if (card.getChildCount() > 0) card.addView(listDivider());
            LinearLayout row = listRow();
            String host = k.optString("host", "");
            row.addView(UiKit.singleLine(UiKit.text(act, host.isEmpty() ? k.optString("announce", "?") : host,
                    14f, AppTheme.text(), UiKit.MEDIUM)));
            String res = k.optString("lastAnnounceResult", "");
            boolean ok = k.optBoolean("lastAnnounceSucceeded", false);
            boolean announced = k.optBoolean("hasAnnounced", !res.isEmpty());
            TextView l1 = UiKit.caption(act, res.isEmpty() || !announced ? I18n.tr("No announce yet") : res);
            if (announced && !ok && !res.isEmpty()) l1.setTextColor(AppTheme.danger());
            l1.setPadding(0, dp(2), 0, 0);
            row.addView(l1);
            long lastT = k.optLong("lastAnnounceTime");
            long nextT = k.optLong("nextAnnounceTime");
            StringBuilder sb = new StringBuilder();
            sb.append(tf("Seeds {0}", Math.max(0, k.optInt("seederCount"))))
                    .append(" · ").append(tf("Leechers {0}", Math.max(0, k.optInt("leecherCount"))));
            if (lastT > 0) sb.append(" · ").append(tf("Last {0}", fmtDate(lastT)));
            if (nextT > 0) sb.append(" · ").append(tf("Next {0}", fmtDate(nextT)));
            TextView l2 = UiKit.caption(act, sb.toString());
            l2.setPadding(0, dp(2), 0, 0);
            row.addView(l2);
            card.addView(row);
        }
        body.addView(card);
    }

    private void renderInfo(LinearLayout body, final JSONObject t) {
        final int id = t.optInt("id");
        LinearLayout c = UiKit.card(act);
        c.addView(kvMono("Location", t.optString("downloadDir", "")));
        c.addView(kvMono("Hash", t.optString("hashString", "")));
        c.addView(kv("Added", fmtDate(t.optLong("addedDate"))));
        long done = t.optLong("doneDate");
        c.addView(kv("Completed", done > 0 ? fmtDate(done) : "—"));
        c.addView(kv("Total size", fmtSize(t.optLong("totalSize"))));
        c.addView(kv("Downloaded", fmtSize(t.optLong("downloadedEver"))));
        c.addView(kv("Uploaded", fmtSize(t.optLong("uploadedEver"))));
        c.addView(kv("Corrupt", fmtSize(t.optLong("corruptEver"))));
        c.addView(kv("Pieces", t.optInt("pieceCount") + " × " + fmtSize(t.optLong("pieceSize"))));
        c.addView(kv("Private", I18n.tr(t.optBoolean("isPrivate") ? "Yes" : "No")));
        String creator = t.optString("creator", "");
        if (!creator.isEmpty()) c.addView(kv("Created by", creator));
        String comment = t.optString("comment", "");
        if (!comment.isEmpty()) c.addView(kv("Comment", comment));
        JSONArray labels = t.optJSONArray("labels");
        StringBuilder lb = new StringBuilder();
        for (int i = 0; labels != null && i < labels.length(); i++) {
            String s = labels.optString(i, "");
            if (s.isEmpty()) continue;
            if (lb.length() > 0) lb.append(", ");
            lb.append(s);
        }
        final String labelText = lb.toString();
        c.addView(kv("Labels", labelText.isEmpty() ? "—" : labelText));
        c.addView(kv("Speed limits",
                (t.optBoolean("downloadLimited") ? "↓ " + t.optInt("downloadLimit") + " kB/s" : "↓ " + I18n.tr("Unlimited")) + " · "
                        + (t.optBoolean("uploadLimited") ? "↑ " + t.optInt("uploadLimit") + " kB/s" : "↑ " + I18n.tr("Unlimited"))));

        final String magnet = t.optString("magnetLink", "");
        Button labelsBtn = UiKit.outline(act, I18n.tr("Labels"), v -> showLabelsDialog(id, labelText));
        Button limitsBtn = UiKit.outline(act, I18n.tr("Limits"), v -> showLimitsDialog(t));
        Button magnetBtn = UiKit.outline(act, I18n.tr("Copy Magnet"), v -> {
            if (magnet.isEmpty()) {
                toast("No magnet link available.");
                return;
            }
            copyToClipboard("magnet", magnet);
            toast("Magnet link copied.");
        });
        c.addView(UiKit.buttonRow(act, labelsBtn, limitsBtn, magnetBtn));
        body.addView(c);

        LinearLayout pc = UiKit.card(act);
        pc.addView(UiKit.overline(act, I18n.tr("Bandwidth priority")));
        final int bp = Math.max(-1, Math.min(1, t.optInt("bandwidthPriority")));
        LinearLayout chips = UiKit.row(act);
        final String[] names = {"Low", "Normal", "High"};
        final int[] ids = new int[]{id};
        for (int i = 0; i < 3; i++) {
            final int value = i - 1;
            TextView chip = UiKit.chip(act, I18n.tr(names[i]), value == bp);
            chip.setOnClickListener(v -> {
                if (value == bp) return;
                runAction(v, api -> { api.setBandwidthPriority(ids, value); return null; }, "Priority updated.");
            });
            chips.addView(chip, UiKit.chipLp(act));
        }
        pc.addView(chips);
        body.addView(pc);
    }

    private void showMoveDialog(final int id, String currentDir) {
        final Sheet s = new Sheet("Move torrent");
        final EditText dir = field(s.box, "New location", currentDir, true);
        dir.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final Switch move = switchRow(s.box, "Move existing data", "Off: only point Transmission at data that is already there.", true);
        Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        final Button ok = UiKit.primary(act, I18n.tr("Move"), null);
        ok.setOnClickListener(v -> {
            final String loc = dir.getText().toString().trim();
            if (loc.isEmpty()) {
                toast("Enter a folder path.");
                return;
            }
            final boolean mv = move.isChecked();
            setBusy(ok, true);
            async(slow, api -> { api.torrentSetLocation(new int[]{id}, loc, mv); return null; }, r -> {
                s.dismiss();
                toast("Location updated.");
                requestSilent();
            }, e -> {
                setBusy(ok, false);
                toast(msg(e));
            });
        });
        s.box.addView(UiKit.buttonRow(act, cancel, ok));
        s.show();
    }

    private void showQueueDialog(final int id) {
        final int[] ids = new int[]{id};
        choose("Queue", new String[]{"Start now (skip the queue)", "Move to top", "Move up", "Move down", "Move to bottom"}, -1, sel ->
                runAction(null, api -> {
                    switch (sel) {
                        case 0: api.torrentStartNow(ids); break;
                        case 1: api.queueMoveTop(ids); break;
                        case 2: api.queueMoveUp(ids); break;
                        case 3: api.queueMoveDown(ids); break;
                        default: api.queueMoveBottom(ids); break;
                    }
                    return null;
                }, sel == 0 ? "Started." : "Queue updated."));
    }

    private void showRemoveDialog(final int id, String name) {
        final Sheet s = new Sheet("Remove torrent");
        TextView n = UiKit.subtitle(act, name);
        n.setMaxLines(3);
        n.setEllipsize(TextUtils.TruncateAt.END);
        s.box.addView(n);
        TextView hint = UiKit.body(act, I18n.tr("Remove only the torrent and keep its files, or delete the downloaded files too."));
        hint.setPadding(0, dp(6), 0, 0);
        s.box.addView(hint);
        Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        Button keep = UiKit.outline(act, I18n.tr("Keep Files"), v -> {
            s.dismiss();
            doRemove(id, false);
        });
        s.box.addView(UiKit.buttonRow(act, cancel, keep));
        Button wipe = UiKit.danger(act, I18n.tr("Delete Files"), v -> {
            s.dismiss();
            confirm("Delete files?", "Permanently delete the downloaded files from disk? This cannot be undone.",
                    "Delete", true, () -> doRemove(id, true));
        });
        s.box.addView(UiKit.buttonRow(act, wipe));
        s.show();
    }

    private void doRemove(final int id, final boolean deleteData) {
        async(io, api -> { api.torrentRemove(new int[]{id}, deleteData); return null; }, v -> {
            toast(deleteData ? "Torrent and files removed." : "Torrent removed.");
            if (detailId == id) {
                detailId = 0;
                detail = null;
            }
            refresh();
        }, t -> toast(msg(t)));
    }

    private void showLabelsDialog(final int id, String current) {
        final Sheet s = new Sheet("Labels");
        final EditText in = field(s.box, "Comma-separated labels", current, false);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        Button save = UiKit.primary(act, I18n.tr("Save"), v -> {
            final List<String> out = new ArrayList<>();
            for (String p : in.getText().toString().split(",")) {
                String x = p.trim();
                if (!x.isEmpty() && !out.contains(x)) out.add(x);
            }
            s.dismiss();
            runAction(null, api -> { api.setLabels(new int[]{id}, out.toArray(new String[0])); return null; }, "Labels saved.");
        });
        s.box.addView(UiKit.buttonRow(act, cancel, save));
        s.show();
    }

    private void showLimitsDialog(JSONObject t) {
        final int id = t.optInt("id");
        final Sheet s = new Sheet("Speed limits");
        final Switch dl = switchRow(s.box, "Limit download", null, t.optBoolean("downloadLimited"));
        final EditText dlv = numberField(s.box, "Download limit (kB/s)", String.valueOf(t.optInt("downloadLimit")), false);
        final Switch ul = switchRow(s.box, "Limit upload", null, t.optBoolean("uploadLimited"));
        final EditText ulv = numberField(s.box, "Upload limit (kB/s)", String.valueOf(t.optInt("uploadLimit")), false);
        Button cancel = UiKit.outline(act, I18n.tr("Cancel"), v -> s.dismiss());
        Button save = UiKit.primary(act, I18n.tr("Save"), v -> {
            final boolean dlOn = dl.isChecked();
            final boolean ulOn = ul.isChecked();
            final int dlK = parseInt(dlv, 0);
            final int ulK = parseInt(ulv, 0);
            s.dismiss();
            runAction(null, api -> { api.setTorrentSpeedLimits(id, dlOn, dlK, ulOn, ulK); return null; }, "Limits saved.");
        });
        s.box.addView(UiKit.buttonRow(act, cancel, save));
        s.show();
    }

    // ================================================================== ADD

    private void renderAdd() {
        content.removeAllViews();
        clearScreenRefs();
        LinearLayout c = UiKit.card(act);
        c.addView(UiKit.overline(act, I18n.tr("Add torrent")));
        TextView h = UiKit.body(act, I18n.tr("Paste magnet links, .torrent URLs or info hashes, one per line."));
        h.setPadding(0, 0, 0, dp(10));
        c.addView(h);

        final EditText links = UiKit.input(act, "magnet:?xt=urn:btih:…");
        links.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        links.setSingleLine(false);
        links.setHorizontallyScrolling(false);
        links.setMinLines(3);
        links.setMaxLines(8);
        links.setGravity(Gravity.TOP | Gravity.START);
        links.setTypeface(Typeface.MONOSPACE);
        links.setTextSize(13f);
        c.addView(links, new LinearLayout.LayoutParams(-1, -2));

        final EditText dir = field(c, "Download folder (optional)", "", true);
        dir.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final Switch paused = switchRow(c, "Start paused", null, false);

        Button paste = UiKit.outline(act, I18n.tr("Paste"), v -> {
            String clip = readClipboard();
            if (clip.isEmpty()) {
                toast("Clipboard is empty.");
                return;
            }
            String cur = links.getText().toString().trim();
            links.setText(cur.isEmpty() ? clip : cur + "\n" + clip);
            links.setSelection(links.getText().length());
        });
        final Button add = UiKit.primary(act, I18n.tr("Add"), null);
        add.setOnClickListener(v -> doAdd(add, links, dir, paused));
        c.addView(UiKit.buttonRow(act, paste, add));
        content.addView(c);

        TextView note = UiKit.caption(act, I18n.tr("Torrents added here are not tracked by Sonarr or Radarr. Use their tabs to grab releases they should import."));
        note.setPadding(dp(4), 0, dp(4), 0);
        content.addView(note);

        // Show the daemon's default download folder as the hint (best effort).
        final int g = gen;
        async(io, api -> {
            String d = api.sessionGet().optString("download-dir", "");
            return d;
        }, s -> {
            if (g == gen && s != null && !s.isEmpty() && dir.getParent() != null) dir.setHint(tf("Default: {0}", s));
        }, e -> { });
    }

    private void doAdd(final Button add, final EditText links, final EditText dir, final Switch paused) {
        final List<String> items = new ArrayList<>();
        for (String line : links.getText().toString().split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.matches("(?i)[0-9a-f]{40}") || s.matches("(?i)[a-z2-7]{32}")) s = "magnet:?xt=urn:btih:" + s;
            if (!items.contains(s)) items.add(s);
        }
        if (items.isEmpty()) {
            toast("Paste a magnet link or URL first.");
            return;
        }
        final String dd = dir.getText().toString().trim();
        final boolean pz = paused.isChecked();
        setBusy(add, true);
        async(slow, api -> {
            int added = 0, dup = 0, failed = 0;
            String lastError = null;
            for (String s : items) {
                try {
                    JSONObject r = api.torrentAdd(s, null, dd, pz);
                    if (r.has("torrent-duplicate")) dup++;
                    else added++;
                } catch (Exception e) {
                    if (TransmissionApi.isNotRunning(e) || TransmissionApi.isAuthError(e)) throw e;
                    failed++;
                    lastError = msg(e);
                }
            }
            return new Object[]{added, dup, failed, lastError};
        }, r -> {
            setBusy(add, false);
            int added = (Integer) r[0], dup = (Integer) r[1], failed = (Integer) r[2];
            StringBuilder sb = new StringBuilder(tf("{0} added", added));
            if (dup > 0) sb.append(", ").append(tf("{0} already present", dup));
            if (failed > 0) sb.append(", ").append(tf("{0} failed", failed)).append(": ").append(I18n.tr(String.valueOf(r[3])));
            toast(sb.toString());
            if (failed == 0) links.setText("");
            if (added > 0 && failed == 0) go(S_TORRENTS);
        }, e -> {
            setBusy(add, false);
            toast(msg(e));
        });
    }

    // ================================================================== SPEED

    private void loadSpeed() {
        final int g = gen;
        showLoading("Loading speed settings…");
        async(io, api -> new Object[]{api.sessionGet(), api.sessionStats()}, r -> {
            if (g != gen || section != S_SPEED || detailId > 0) return;
            renderSpeed((JSONObject) r[0], (JSONObject) r[1]);
        }, e -> {
            if (g == gen) showFailure(e, "Could not load speed settings");
        });
    }

    private void tickSpeed() {
        if (liveDown == null) return;
        if (!beginPoll()) return;
        final int g = gen;
        async(io, api -> api.sessionStats(), st -> {
            pollDone();
            if (g != gen || section != S_SPEED || liveDown == null) return;
            bindLive(st);
        }, e -> pollDone());
    }

    private void bindLive(JSONObject st) {
        long d = st.optLong("downloadSpeed");
        long u = st.optLong("uploadSpeed");
        setText(liveDown, fmtSpeed(d));
        setText(liveUp, fmtSpeed(u));
        setText(liveCounts, tf("{0} active · {1} paused · {2} total", st.optInt("activeTorrentCount"),
                st.optInt("pausedTorrentCount"), st.optInt("torrentCount")));
        setHeaderSpeeds(d, u, st.optInt("torrentCount", -1));
    }

    private void renderSpeed(final JSONObject s, JSONObject stats) {
        content.removeAllViews();
        clearScreenRefs();
        final List<Collect> cols = new ArrayList<>();

        LinearLayout live = UiKit.card(act);
        live.addView(UiKit.overline(act, I18n.tr("Live")));
        LinearLayout lrow = UiKit.row(act);
        LinearLayout c1 = UiKit.column(act);
        c1.addView(UiKit.caption(act, "↓ " + I18n.tr("Download")));
        liveDown = UiKit.display(act, "");
        liveDown.setTextColor(AppTheme.accent());
        c1.addView(liveDown);
        LinearLayout c2 = UiKit.column(act);
        c2.addView(UiKit.caption(act, "↑ " + I18n.tr("Upload")));
        liveUp = UiKit.display(act, "");
        liveUp.setTextColor(AppTheme.success());
        c2.addView(liveUp);
        lrow.addView(c1, new LinearLayout.LayoutParams(0, -2, 1.0f));
        lrow.addView(c2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        live.addView(lrow);
        liveCounts = UiKit.caption(act, "");
        liveCounts.setPadding(0, dp(8), 0, 0);
        live.addView(liveCounts);
        content.addView(live);
        bindLive(stats);

        // alternative (turtle) limits: the switch applies immediately
        LinearLayout turtle = UiKit.card(act);
        turtle.addView(UiKit.overline(act, I18n.tr("Alternative speed limits")));
        final Switch alt = switchRow(turtle, "Turtle mode", "Switch to the alternative limits below right now.",
                s.optBoolean("alt-speed-enabled"));
        final boolean[] altBinding = {false};
        alt.setOnCheckedChangeListener((b, checked) -> {
            if (altBinding[0]) return;
            alt.setEnabled(false);
            async(io, api -> {
                JSONObject a = new JSONObject();
                a.put("alt-speed-enabled", checked);
                api.sessionSet(a);
                return null;
            }, r -> {
                alt.setEnabled(true);
                toast(checked ? "Turtle mode on." : "Turtle mode off.");
            }, e -> {
                alt.setEnabled(true);
                altBinding[0] = true;
                alt.setChecked(!checked);
                altBinding[0] = false;
                toast(msg(e));
            });
        });
        final EditText altDown = numberField(turtle, "Alternative download limit (kB/s)", String.valueOf(s.optInt("alt-speed-down", 50)), false);
        final EditText altUp = numberField(turtle, "Alternative upload limit (kB/s)", String.valueOf(s.optInt("alt-speed-up", 50)), false);
        cols.add(o -> {
            o.put("alt-speed-down", parseInt(altDown, 50));
            o.put("alt-speed-up", parseInt(altUp, 50));
        });
        content.addView(turtle);

        // global limits
        LinearLayout lim = UiKit.card(act);
        lim.addView(UiKit.overline(act, I18n.tr("Global speed limits")));
        final Switch dOn = switchRow(lim, "Limit download speed", null, s.optBoolean("speed-limit-down-enabled"));
        final EditText dv = numberField(lim, "Download limit (kB/s)", String.valueOf(s.optInt("speed-limit-down", 100)), false);
        final Switch uOn = switchRow(lim, "Limit upload speed", null, s.optBoolean("speed-limit-up-enabled"));
        final EditText uv = numberField(lim, "Upload limit (kB/s)", String.valueOf(s.optInt("speed-limit-up", 100)), false);
        cols.add(o -> {
            o.put("speed-limit-down-enabled", dOn.isChecked());
            o.put("speed-limit-down", parseInt(dv, 100));
            o.put("speed-limit-up-enabled", uOn.isChecked());
            o.put("speed-limit-up", parseInt(uv, 100));
        });
        content.addView(lim);

        final Button save = UiKit.primary(act, I18n.tr("Save Limits"), null);
        save.setOnClickListener(v -> saveSession(save, cols, "Speed limits saved."));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(44));
        slp.bottomMargin = dp(12);
        content.addView(save, slp);

        content.addView(statsCard("This session", stats.optJSONObject("current-stats")));
        content.addView(statsCard("All time", stats.optJSONObject("cumulative-stats")));
    }

    private View statsCard(String title, JSONObject cs) {
        LinearLayout c = UiKit.card(act);
        c.addView(UiKit.overline(act, I18n.tr(title)));
        if (cs == null) {
            c.addView(UiKit.caption(act, I18n.tr("No statistics yet.")));
            return c;
        }
        long dl = cs.optLong("downloadedBytes");
        long ul = cs.optLong("uploadedBytes");
        c.addView(kv("Downloaded", fmtSize(dl)));
        c.addView(kv("Uploaded", fmtSize(ul)));
        c.addView(kv("Ratio", dl > 0 ? fmtRatio((double) ul / (double) dl) : "—"));
        c.addView(kv("Files added", String.valueOf(cs.optLong("filesAdded"))));
        c.addView(kv("Active time", fmtDuration(cs.optLong("secondsActive"))));
        if (cs.has("sessionCount")) c.addView(kv("Sessions", String.valueOf(cs.optLong("sessionCount"))));
        return c;
    }

    private void saveSession(final Button save, List<Collect> cols, final String okMsg) {
        final JSONObject args = new JSONObject();
        try {
            for (Collect c : cols) c.put(args);
        } catch (Exception e) {
            toast(msg(e));
            return;
        }
        setBusy(save, true);
        async(io, api -> { api.sessionSet(args); return null; }, v -> {
            setBusy(save, false);
            toast(okMsg);
        }, t -> {
            setBusy(save, false);
            toast(msg(t));
        });
    }

    // ================================================================== SETTINGS

    private void loadSettings() {
        final int g = gen;
        showLoading("Loading settings…");
        async(io, api -> {
            JSONObject s = api.sessionGet();
            try {
                s.put("_free", api.freeSpace(s.optString("download-dir", "")));
            } catch (Throwable ignored) {
                // free-space is optional
            }
            return s;
        }, s -> {
            if (g != gen || section != S_SETTINGS || detailId > 0) return;
            renderSettings(s);
        }, e -> {
            if (g == gen) showFailure(e, "Could not load settings");
        });
    }

    private void renderSettings(final JSONObject s) {
        content.removeAllViews();
        clearScreenRefs();
        final List<Collect> cols = new ArrayList<>();

        LinearLayout info = UiKit.card(act);
        info.addView(UiKit.overline(act, I18n.tr("Daemon")));
        info.addView(kv("Version", s.optString("version", "—")));
        info.addView(kv("RPC version", String.valueOf(s.optInt("rpc-version"))));
        long free = s.optLong("_free", -1);
        if (free >= 0) info.addView(kv("Free space", fmtSize(free)));
        content.addView(info);

        LinearLayout dirs = UiKit.card(act);
        dirs.addView(UiKit.overline(act, I18n.tr("Folders")));
        final EditText dd = field(dirs, "Download folder", s.optString("download-dir", ""), true);
        dd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final Switch idOn = switchRow(dirs, "Use a separate folder for incomplete files", null, s.optBoolean("incomplete-dir-enabled"));
        final EditText id = field(dirs, "Incomplete folder", s.optString("incomplete-dir", ""), true);
        id.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final Switch startAdded = switchRow(dirs, "Start added torrents", null, s.optBoolean("start-added-torrents", true));
        cols.add(o -> {
            String a = dd.getText().toString().trim();
            if (!a.isEmpty()) o.put("download-dir", a);
            o.put("incomplete-dir-enabled", idOn.isChecked());
            String b = id.getText().toString().trim();
            if (!b.isEmpty()) o.put("incomplete-dir", b);
            o.put("start-added-torrents", startAdded.isChecked());
        });
        content.addView(dirs);

        LinearLayout seed = UiKit.card(act);
        seed.addView(UiKit.overline(act, I18n.tr("Seeding")));
        final Switch ratioOn = switchRow(seed, "Stop seeding at ratio", null, s.optBoolean("seedRatioLimited"));
        final EditText ratio = numberField(seed, "Ratio limit", fmtRatioPlain(s.optDouble("seedRatioLimit", 2.0)), true);
        cols.add(o -> {
            o.put("seedRatioLimited", ratioOn.isChecked());
            o.put("seedRatioLimit", Math.max(0, parseDouble(ratio, 2.0)));
        });
        content.addView(seed);

        LinearLayout peers = UiKit.card(act);
        peers.addView(UiKit.overline(act, I18n.tr("Peers and queues")));
        final EditText pg = numberField(peers, "Global peer limit", String.valueOf(s.optInt("peer-limit-global", 200)), false);
        final EditText pt = numberField(peers, "Peer limit per torrent", String.valueOf(s.optInt("peer-limit-per-torrent", 50)), false);
        final Switch dqOn = switchRow(peers, "Limit active downloads", null, s.optBoolean("download-queue-enabled", true));
        final EditText dq = numberField(peers, "Max active downloads", String.valueOf(s.optInt("download-queue-size", 5)), false);
        final Switch sqOn = switchRow(peers, "Limit active seeds", null, s.optBoolean("seed-queue-enabled"));
        final EditText sq = numberField(peers, "Max active seeds", String.valueOf(s.optInt("seed-queue-size", 10)), false);
        cols.add(o -> {
            o.put("peer-limit-global", Math.max(1, parseInt(pg, 200)));
            o.put("peer-limit-per-torrent", Math.max(1, parseInt(pt, 50)));
            o.put("download-queue-enabled", dqOn.isChecked());
            o.put("download-queue-size", Math.max(1, parseInt(dq, 5)));
            o.put("seed-queue-enabled", sqOn.isChecked());
            o.put("seed-queue-size", Math.max(1, parseInt(sq, 10)));
        });
        content.addView(peers);

        LinearLayout net = UiKit.card(act);
        net.addView(UiKit.overline(act, I18n.tr("Network")));
        final Switch dht = switchRow(net, "DHT", "Distributed hash table peer discovery.", s.optBoolean("dht-enabled", true));
        final Switch pex = switchRow(net, "PEX", "Peer exchange.", s.optBoolean("pex-enabled", true));
        final Switch lpd = switchRow(net, "LPD", "Local peer discovery.", s.optBoolean("lpd-enabled"));
        cols.add(o -> {
            o.put("dht-enabled", dht.isChecked());
            o.put("pex-enabled", pex.isChecked());
            o.put("lpd-enabled", lpd.isChecked());
        });
        TextView encLabel = UiKit.caption(act, I18n.tr("Encryption"));
        encLabel.setPadding(0, dp(14), 0, dp(8));
        net.addView(encLabel);
        final String[] enc = new String[]{s.optString("encryption", "preferred")};
        final LinearLayout encRow = UiKit.row(act);
        drawEncryption(encRow, enc);
        net.addView(UiKit.chipScroller(act, encRow), new LinearLayout.LayoutParams(-1, -2));
        cols.add(o -> o.put("encryption", enc[0]));
        content.addView(net);

        LinearLayout bl = UiKit.card(act);
        bl.addView(UiKit.overline(act, I18n.tr("Blocklist")));
        final Switch blOn = switchRow(bl, "Enable blocklist", null, s.optBoolean("blocklist-enabled"));
        final EditText blUrl = field(bl, "Blocklist URL", s.optString("blocklist-url", ""), true);
        blUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        cols.add(o -> {
            o.put("blocklist-enabled", blOn.isChecked());
            String u = blUrl.getText().toString().trim();
            if (!u.isEmpty()) o.put("blocklist-url", u);
        });
        final TextView bs = UiKit.caption(act, tf("Rules loaded: {0}", s.optInt("blocklist-size")));
        bs.setPadding(0, dp(8), 0, 0);
        bl.addView(bs);
        final Button upd = UiKit.outline(act, I18n.tr("Update Blocklist"), null);
        upd.setOnClickListener(v -> {
            setBusy(upd, true);
            async(slow, api -> api.blocklistUpdate(), n -> {
                setBusy(upd, false);
                setText(bs, tf("Rules loaded: {0}", n));
                toast(tf("Blocklist updated: {0} rules", n));
            }, e -> {
                setBusy(upd, false);
                toast(msg(e));
            });
        });
        final Button port = UiKit.outline(act, I18n.tr("Test Port"), null);
        port.setOnClickListener(v -> {
            setBusy(port, true);
            async(slow, api -> api.portTest(), open -> {
                setBusy(port, false);
                toast(open ? "The peer port is open." : "The peer port is closed.");
            }, e -> {
                setBusy(port, false);
                toast(msg(e));
            });
        });
        bl.addView(UiKit.buttonRow(act, upd, port));
        content.addView(bl);

        final Button save = UiKit.primary(act, I18n.tr("Save Settings"), null);
        save.setOnClickListener(v -> saveSession(save, cols, "Settings saved."));
        content.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView note = UiKit.caption(act, I18n.tr("Jellyarr re-applies some of these (for example the seeding ratio and folders) when Transmission restarts."));
        note.setPadding(dp(4), dp(12), dp(4), 0);
        content.addView(note);
    }

    private void drawEncryption(final LinearLayout holder, final String[] cur) {
        holder.removeAllViews();
        final String[] values = {"required", "preferred", "tolerated"};
        final String[] labels = {"Required", "Preferred", "Allowed"};
        for (int i = 0; i < values.length; i++) {
            final String val = values[i];
            TextView p = UiKit.chip(act, I18n.tr(labels[i]), val.equals(cur[0]));
            p.setOnClickListener(v -> {
                cur[0] = val;
                drawEncryption(holder, cur);
            });
            holder.addView(p, UiKit.chipLp(act));
        }
    }

    // ================================================================== History & cleanup

    private void renderCenter() {
        content.removeAllViews();
        clearScreenRefs();
        TextView intro = UiKit.caption(act, I18n.tr("Finished downloads, history and cleanup tools. Live torrents are in the Torrents section."));
        intro.setPadding(dp(4), 0, dp(4), 0);
        content.addView(intro);
        if (centerView == null) {
            content.addView(UiKit.empty(act, I18n.tr("Nothing here yet"), null));
            return;
        }
        if (centerView.getParent() instanceof ViewGroup) ((ViewGroup) centerView.getParent()).removeView(centerView);
        content.addView(centerView);
        if (centerRefresh != null) centerRefresh.run();
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

    /** Single-choice list; selected < 0 marks nothing. */
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

    private LinearLayout listRow() {
        LinearLayout row = UiKit.column(act);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        return row;
    }

    /** Label above an input; monospace for paths and URLs. */
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

    private EditText numberField(LinearLayout parent, String label, String value, boolean decimal) {
        EditText e = field(parent, label, value, false);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    /** Label (+ optional hint) with a themed switch at the end; returns the switch. */
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
        Switch sw = UiStyle.newSwitch(act);
        sw.setChecked(checked);
        UiStyle.styleSwitch(act, sw);
        row.addView(sw);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return sw;
    }

    private View kv(String key, String value) {
        return UiKit.kv(act, I18n.tr(key), value == null || value.isEmpty() ? "—" : value);
    }

    /** kv row whose value is monospace and selectable (paths, hashes). */
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

    private Button compact(Button b) {
        b.setTextSize(12.5f);
        b.setMinHeight(dp(32));
        b.setMinimumHeight(dp(32));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private LinearLayout.LayoutParams compactLp(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
        if (gap) lp.setMarginStart(dp(8));
        return lp;
    }

    /** UiKit.header builds [column(title, subtitle)] + trailing; returns its subtitle TextView. */
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

    private static int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    private static double parseDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim().replace(',', '.')); } catch (Throwable t) { return def; }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence cs = clip.getItemAt(0).coerceToText(act);
            return cs == null ? "" : cs.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void copyToClipboard(String label, String s) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, s));
        } catch (Throwable ignored) { }
    }

    private int dp(float v) {
        return UiKit.dp(act, v);
    }

    // ================================================================== formatting

    private static String fmtSize(long bytes) {
        return com.jellyarr.app.util.Fmt.bytes(bytes);
    }

    private static String fmtSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        return fmtSize(bytesPerSec) + "/s";
    }

    private static String fmtPct(double frac) {
        if (frac < 0 || Double.isNaN(frac)) frac = 0;
        if (frac > 1) frac = 1;
        return String.format(Locale.US, "%.1f%%", frac * 100.0);
    }

    private static String fmtRatio(double r) {
        if (r < 0 || Double.isNaN(r)) return "—";
        return String.format(Locale.US, "%.2f", r);
    }

    private static String fmtRatioPlain(double r) {
        return String.format(Locale.US, "%.2f", r);
    }

    /** Transmission uses -1 for "not available" and -2 for "unknown". */
    private static String fmtEta(long secs) {
        if (secs < 0) return "—";
        return fmtDuration(secs);
    }

    private static String fmtDuration(long secs) {
        if (secs < 60) return secs + "s";
        long m = secs / 60;
        if (m < 60) return m + "m " + (secs % 60) + "s";
        long h = m / 60;
        if (h < 48) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    private static String fmtDate(long epochSec) {
        if (epochSec <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(epochSec * 1000L));
    }
}
