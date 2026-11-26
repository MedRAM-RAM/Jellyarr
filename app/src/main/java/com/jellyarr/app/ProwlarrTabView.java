package com.jellyarr.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.jellyarr.app.services.MediaServiceManager;
import com.jellyarr.app.services.ProwlarrApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "Prowlarr" tab: a native client for Prowlarr's web UI, structured like {@link RadarrTabView}.
 *
 * <ul>
 *   <li>Indexers - list with health badges; tap one to test, edit, enable / disable or delete; add from the
 *       definition catalogue; test all.</li>
 *   <li>Search - search every enabled indexer (category chips) and send a release to the download client.</li>
 *   <li>Apps - synced applications (Sonarr / Radarr ...), test, delete, sync now.</li>
 *   <li>Stats - per-indexer queries, grabs, failures and response time.</li>
 *   <li>History - paged indexer queries and grabs.</li>
 *   <li>System - status, health, download clients, tags, scheduled tasks, maintenance commands.</li>
 * </ul>
 */
public final class ProwlarrTabView {

    private static final int S_INDEXERS = 0;
    private static final int S_SEARCH = 1;
    private static final int S_APPS = 2;
    private static final int S_STATS = 3;
    private static final int S_HISTORY = 4;
    private static final int S_SYSTEM = 5;

    private static final String[] SECTION_NAMES = {"Indexers", "Search", "Apps", "Stats", "History", "System"};
    private static final String[] CAT_NAMES = {"All", "Movies", "TV", "Audio", "Books", "Other"};
    private static final int[] CAT_IDS = {0, 2000, 5000, 3000, 7000, 8000};
    private static final int PAGE_SIZE = 40;

    private interface Job<T> { T run(ProwlarrApi api) throws Exception; }
    private interface Done<T> { void run(T value); }
    private interface TextSink { void set(String s); }
    private interface BoolSink { void set(boolean b); }
    private interface IntSink { void set(int i); }

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final LinearLayout headerSlot;
    private final LinearLayout chipRow;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final ExecutorService bg = Executors.newCachedThreadPool();

    private int section = S_INDEXERS;
    private int page = 1;
    private int requestCounter = 0;
    private String indexerFilter = "";
    private JSONArray indexerCache = null;
    private JSONArray statusCache = null;
    private JSONArray schemaCache = null;
    private boolean schemaLoading = false;
    private final Map<Integer, String> indexerNames = new HashMap<>();
    private String version = null;
    private boolean versionLoading = false;
    private boolean attached = true;
    private boolean starting = false;
    private long lastOpenMs = 0;

    private int searchCat = 0;
    private String searchQuery = "";
    private JSONArray searchResults = null;
    private LinearLayout searchBox = null;
    private Button searchButton = null;

    private LinearLayout indexerRowsBox;
    private TextView indexerCountView;

    public ProwlarrTabView(Activity activity) {
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

    /** Returns true when the back press was consumed. */
    public boolean onBack() {
        if (section == S_HISTORY && page > 1) {
            page--;
            refresh();
            return true;
        }
        if (section != S_INDEXERS) {
            section = S_INDEXERS;
            page = 1;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        indexerRowsBox = null;
        indexerCountView = null;
        searchBox = null;
        searchButton = null;
        renderNav();
        updateHeader();
        loadVersionOnce();
        switch (section) {
            case S_SEARCH: renderSearch(); break;
            case S_APPS: loadApps(); break;
            case S_STATS: loadStats(); break;
            case S_HISTORY: loadHistory(); break;
            case S_SYSTEM: loadSystem(); break;
            default: loadIndexers(); break;
        }
    }

    // ================================================================== chrome

    private void updateHeader() {
        headerSlot.removeAllViews();
        String sub;
        if (version != null && indexerCache != null) sub = trf("Prowlarr v{0} · {1} indexers", version, indexerCache.length());
        else if (version != null) sub = trf("Prowlarr v{0}", version);
        else sub = tr("Indexers, search and app sync");
        Button refreshBtn = UiKit.outline(act, tr("Refresh"), v -> {
            if (section == S_INDEXERS) indexerCache = null;
            refresh();
        });
        headerSlot.addView(UiKit.header(act, tr("Prowlarr"), sub, refreshBtn));
    }

    private void renderNav() {
        chipRow.removeAllViews();
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int idx = i;
            TextView chip = UiKit.chip(act, tr(SECTION_NAMES[i]), idx == section);
            chip.setOnClickListener(v -> {
                if (idx == section && page == 1) {
                    scroll.smoothScrollTo(0, 0);
                    return;
                }
                section = idx;
                page = 1;
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
                    result = job.run(ProwlarrApi.get(act));
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

    private boolean debounce() {
        long now = SystemClock.uptimeMillis();
        if (now - lastOpenMs < 700) return true;
        lastOpenMs = now;
        return false;
    }

    private static String errText(Throwable t) {
        if (ProwlarrApi.isOffline(t)) return tr("Prowlarr is not running");
        if (t instanceof JSONException) return tr("Prowlarr sent an unexpected answer.");
        String m = t == null ? null : t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t == null ? "Unknown error" : t.getClass().getSimpleName();
        return tr(m);
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
        if (ProwlarrApi.isOffline(t)) {
            View v = UiKit.error(act, tr("Prowlarr is not running"),
                    tr("Start Prowlarr here or from the Services tab. This screen loads as soon as it is up."), null, null);
            if (v instanceof LinearLayout) {
                final Button start = UiKit.primary(act, tr(starting ? "Starting…" : "Start Prowlarr"), null);
                start.setOnClickListener(x -> startProwlarr(start));
                if (starting) setBusy(start, true);
                if (withRetry) {
                    ((LinearLayout) v).addView(UiKit.buttonRow(act, start, UiKit.outline(act, tr("Retry"), x -> refresh())));
                } else {
                    ((LinearLayout) v).addView(UiKit.buttonRow(act, start));
                }
            }
            return v;
        }
        if (!withRetry) return UiKit.error(act, tr("Prowlarr reported an error"), errText(t), null, null);
        return UiKit.error(act, tr("Could not load Prowlarr"), errText(t), tr("Retry"), x -> refresh());
    }

    private void startProwlarr(final Button b) {
        if (starting) return;
        starting = true;
        setBusy(b, true);
        b.setText(tr("Starting…"));
        try {
            MediaServiceManager.getInstance(act.getApplicationContext()).startService("prowlarr");
        } catch (Throwable t) {
            starting = false;
            setBusy(b, false);
            b.setText(tr("Start Prowlarr"));
            toast(errText(t));
            return;
        }
        try {
            bg.execute(() -> {
                long end = System.currentTimeMillis() + 120000;
                boolean up = false;
                while (System.currentTimeMillis() < end && attached && !dead()) {
                    if (ProwlarrApi.isPortOpen()) {
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
                    if (!ok) toast(tr("Prowlarr has not started yet. Check the Services tab."));
                    refresh();
                });
            });
        } catch (RejectedExecutionException e) {
            starting = false;
        }
    }

    // ================================================================== JSON helpers

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (Exception ignored) {
            // JSONException only for NaN / infinite numbers
        }
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception e) {
            return o;
        }
    }

    /** Null-safe string of a JSON value ("" for missing / JSON null). */
    private static String str(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        return String.valueOf(v);
    }

    private static String s(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        return o.optString(k, "");
    }

    /** History "data" keys are camelCase on current builds; accept PascalCase too. */
    private static String dataStr(JSONObject data, String key) {
        if (data == null) return "";
        String v = s(data, key);
        if (v.isEmpty()) v = s(data, Character.toUpperCase(key.charAt(0)) + key.substring(1));
        return v;
    }

    private void rememberNames(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optInt("id") > 0) indexerNames.put(o.optInt("id"), o.optString("name"));
        }
    }

    @SuppressWarnings("deprecation")
    private static String stripHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.indexOf('<') < 0 && s.indexOf('&') < 0) return s.trim();
        try {
            return android.text.Html.fromHtml(s).toString().trim();
        } catch (Throwable t) {
            return s.replaceAll("<[^>]+>", "").trim();
        }
    }

    // ================================================================== INDEXERS

    private void loadIndexers() {
        final int req = ++requestCounter;
        if (indexerCache != null) renderIndexers();
        else showLoading(tr("Loading indexers…"));
        async(api -> {
            JSONObject o = new JSONObject();
            o.put("indexers", api.indexers());
            try { o.put("status", api.indexerStatus()); } catch (Throwable ignored) {}
            return o;
        }, o -> {
            if (req != requestCounter || section != S_INDEXERS) return;
            boolean fresh = indexerCache == null || indexerRowsBox == null;
            indexerCache = o.optJSONArray("indexers");
            statusCache = o.optJSONArray("status");
            rememberNames(indexerCache);
            updateHeader();
            if (fresh) renderIndexers();
            else fillIndexerRows();
        }, t -> {
            if (req != requestCounter || section != S_INDEXERS) return;
            if (indexerCache == null || ProwlarrApi.isOffline(t)) {
                indexerCache = null;
                showError(t);
            } else {
                toast(errText(t));
            }
        });
    }

    private void renderIndexers() {
        content.removeAllViews();
        LinearLayout bar = UiKit.row(act);
        final EditText filter = UiKit.input(act, tr("Search your indexers"));
        filter.setText(indexerFilter);
        filter.setSelection(filter.getText().length());
        filter.setImeOptions(EditorInfo.IME_ACTION_DONE);
        bar.addView(filter, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button add = UiKit.primary(act, tr("Add indexer"), v -> {
            if (!debounce()) showAddIndexer();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.setMarginStart(dp(8));
        bar.addView(add, alp);
        content.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout info = UiKit.row(act);
        info.setPadding(0, dp(10), 0, dp(8));
        indexerCountView = UiKit.caption(act, "");
        info.addView(indexerCountView, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Button testAll = UiKit.outline(act, tr("Test all"), null);
        testAll.setOnClickListener(v -> testAllIndexers(testAll));
        info.addView(testAll, new LinearLayout.LayoutParams(-2, dp(38)));
        content.addView(info, new LinearLayout.LayoutParams(-1, -2));

        indexerRowsBox = UiKit.column(act);
        content.addView(indexerRowsBox, new LinearLayout.LayoutParams(-1, -2));
        fillIndexerRows();
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                indexerFilter = e.toString();
                fillIndexerRows();
            }
        });
    }

    /** indexerId -> epoch ms until which Prowlarr keeps the indexer disabled (only entries in the future). */
    private Map<Integer, Long> failingUntil() {
        Map<Integer, Long> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (int i = 0; statusCache != null && i < statusCache.length(); i++) {
            JSONObject st = statusCache.optJSONObject(i);
            if (st == null) continue;
            long till = parseIso(s(st, "disabledTill"));
            if (till > now) out.put(st.optInt("indexerId"), till);
        }
        return out;
    }

    private void fillIndexerRows() {
        if (indexerRowsBox == null || indexerCountView == null || indexerCache == null) return;
        indexerRowsBox.removeAllViews();
        Map<Integer, Long> failing = failingUntil();
        List<JSONObject> list = new ArrayList<>();
        String f = indexerFilter.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < indexerCache.length(); i++) {
            JSONObject o = indexerCache.optJSONObject(i);
            if (o == null) continue;
            if (!f.isEmpty() && !o.optString("name", "").toLowerCase(Locale.ROOT).contains(f)) continue;
            list.add(o);
        }
        Collections.sort(list, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
        int total = indexerCache.length();
        indexerCountView.setText(list.size() == total ? trf("{0} indexers", total) : trf("{0} of {1} indexers", list.size(), total));
        if (list.isEmpty()) {
            indexerRowsBox.addView(total == 0
                    ? UiKit.empty(act, tr("No indexers yet"), tr("Tap Add indexer to pick one from Prowlarr's catalogue."))
                    : UiKit.empty(act, tr("No matches"), tr("Try another search.")));
            return;
        }
        LinearLayout card = listCard();
        for (JSONObject o : list) {
            if (card.getChildCount() > 0) card.addView(separator(12));
            Long till = failing.get(o.optInt("id"));
            card.addView(indexerRow(o, till == null ? 0L : till));
        }
        indexerRowsBox.addView(card);
    }

    private View indexerRow(final JSONObject ix, final long failingTill) {
        LinearLayout row = listRow();
        final boolean enabled = ix.optBoolean("enable");
        LinearLayout tx = UiKit.column(act);
        TextView name = rowTitle(ix.optString("name"));
        if (!enabled) name.setTextColor(AppTheme.textMuted());
        tx.addView(name);
        tx.addView(rowMeta(joinDot(protocolLabel(s(ix, "protocol")), privacyLabel(s(ix, "privacy")),
                trf("Priority {0}", ix.optInt("priority", 25)))));
        if (failingTill > 0) {
            TextView w = UiKit.caption(act, trf("Paused after errors until {0}", fmtTime(failingTill)));
            w.setTextColor(AppTheme.danger());
            w.setPadding(0, dp(2), 0, 0);
            tx.addView(w);
        }
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        View badge;
        if (failingTill > 0) badge = UiKit.badge(act, tr("Failing"), AppTheme.danger());
        else if (enabled) badge = UiKit.badge(act, tr("Enabled"), AppTheme.success());
        else badge = UiKit.badge(act, tr("Disabled"), AppTheme.textMuted());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        row.addView(badge, blp);
        row.setOnClickListener(v -> {
            if (!debounce()) showIndexerSheet(ix, failingTill);
        });
        return row;
    }

    private void showIndexerSheet(final JSONObject ix, long failingTill) {
        final boolean enabled = ix.optBoolean("enable");
        final LinearLayout body = dialogBody(ix.optString("name"),
                joinDot(s(ix, "implementationName"), s(ix, "definitionName").equals(s(ix, "name")) ? "" : s(ix, "definitionName")));
        LinearLayout kvs = UiKit.column(act);
        kvs.setPadding(0, dp(10), 0, 0);
        kvs.addView(UiKit.kv(act, tr("Status"), failingTill > 0 ? trf("Failing until {0}", fmtTime(failingTill))
                : tr(enabled ? "Enabled" : "Disabled")));
        kvs.addView(UiKit.kv(act, tr("Protocol"), orDash(protocolLabel(s(ix, "protocol")))));
        kvs.addView(UiKit.kv(act, tr("Privacy"), orDash(privacyLabel(s(ix, "privacy")))));
        kvs.addView(UiKit.kv(act, tr("Priority"), String.valueOf(ix.optInt("priority", 25))));
        String lang = s(ix, "language");
        if (!lang.isEmpty()) kvs.addView(UiKit.kv(act, tr("Language"), lang));
        String added = fmtDateTime(s(ix, "added"));
        if (!added.isEmpty()) kvs.addView(UiKit.kv(act, tr("Added"), added));
        body.addView(kvs);
        String url = indexerUrl(ix);
        if (!url.isEmpty()) {
            TextView ul = UiKit.caption(act, tr("URL"));
            ul.setPadding(0, dp(8), 0, dp(2));
            body.addView(ul);
            body.addView(UiKit.mono(act, url));
        }
        String desc = stripHtml(s(ix, "description"));
        if (!desc.isEmpty()) {
            TextView dt = UiKit.body(act, desc);
            dt.setPadding(0, dp(10), 0, 0);
            body.addView(dt);
        }
        final AlertDialog d = dialog(body);
        final Button test = UiKit.primary(act, tr("Test"), null);
        test.setOnClickListener(v -> action(test, api -> {
            api.testIndexer(ix);
            return null;
        }, tr("Test passed"), null));
        Button edit = UiKit.outline(act, tr("Edit"), v -> {
            d.dismiss();
            openIndexerForm(ix, false);
        });
        body.addView(UiKit.buttonRow(act, test, edit));
        final Button toggle = UiKit.outline(act, tr(enabled ? "Disable" : "Enable"), null);
        toggle.setOnClickListener(v -> {
            final JSONObject upd = copy(ix);
            put(upd, "enable", !enabled);
            // forceSave: switching an indexer on or off must not depend on it passing a test.
            action(toggle, api -> {
                api.updateIndexer(upd, true);
                return null;
            }, tr(enabled ? "Indexer disabled" : "Indexer enabled"), () -> {
                d.dismiss();
                indexerCache = null;
                refresh();
            });
        });
        final Button del = UiKit.danger(act, tr("Delete"), null);
        del.setOnClickListener(v -> confirm(tr("Delete indexer?"),
                trf("{0} is removed from Prowlarr and from every synced app.", ix.optString("name")), tr("Delete"), true,
                () -> action(del, api -> {
                    api.deleteIndexer(ix.optInt("id"));
                    return null;
                }, tr("Indexer deleted"), () -> {
                    d.dismiss();
                    indexerCache = null;
                    refresh();
                })));
        body.addView(UiKit.buttonRow(act, toggle, del));
        body.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Close"), v -> d.dismiss())));
        show(d);
    }

    private static String indexerUrl(JSONObject ix) {
        JSONArray fields = ix.optJSONArray("fields");
        for (int i = 0; fields != null && i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f != null && "baseUrl".equals(f.optString("name"))) {
                String v = str(f.opt("value"));
                if (!v.isEmpty()) return v;
            }
        }
        JSONArray urls = ix.optJSONArray("indexerUrls");
        return urls != null && urls.length() > 0 ? urls.optString(0, "") : "";
    }

    private void testAllIndexers(final Button b) {
        if (!b.isEnabled()) return;
        setBusy(b, true);
        b.setText(tr("Testing…"));
        async(api -> api.testAllIndexers(), res -> {
            setBusy(b, false);
            b.setText(tr("Test all"));
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < res.length(); i++) {
                JSONObject r = res.optJSONObject(i);
                if (r == null || r.optBoolean("isValid", true)) continue;
                int id = r.optInt("id");
                String name = indexerNames.containsKey(id) ? indexerNames.get(id) : "#" + id;
                JSONArray vf = r.optJSONArray("validationFailures");
                JSONObject f0 = vf == null ? null : vf.optJSONObject(0);
                failed.add(name + (f0 == null ? "" : ": " + f0.optString("errorMessage", "")));
            }
            if (failed.isEmpty()) {
                toast(trf("All {0} passed the test", res.length()));
            } else {
                info(trf("{0} of {1} failed", failed.size(), res.length()), TextUtils.join("\n\n", failed));
            }
            indexerCache = null;
            if (section == S_INDEXERS) loadIndexers();
        }, t -> {
            setBusy(b, false);
            b.setText(tr("Test all"));
            toast(errText(t));
        });
    }

    // ------------------------------------------------------------------ add indexer (definition picker)

    private void showAddIndexer() {
        final LinearLayout body = dialogBody(tr("Add indexer"), tr("Pick a definition from Prowlarr's catalogue"));
        final EditText term = UiKit.input(act, tr("Search definitions"));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(12);
        body.addView(term, tlp);
        final LinearLayout results = UiKit.column(act);
        body.addView(scrollBox(results));
        final AlertDialog d = listDialog(body);
        body.addView(UiKit.buttonRow(act, UiKit.outline(act, tr("Close"), v -> d.dismiss())));
        final boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);

        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            results.removeAllViews();
            if (schemaCache == null) return;
            String f = term.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < schemaCache.length(); i++) {
                JSONObject sc = schemaCache.optJSONObject(i);
                if (sc == null) continue;
                String n = (sc.optString("name", "") + " " + s(sc, "implementationName") + " " + s(sc, "language"))
                        .toLowerCase(Locale.ROOT);
                if (!f.isEmpty() && !n.contains(f)) continue;
                list.add(sc);
            }
            Collections.sort(list, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
            if (list.isEmpty()) {
                results.addView(UiKit.empty(act, tr("No matches"), tr("Try another name.")));
                return;
            }
            TextView c = UiKit.caption(act, list.size() > 80 ? trf("{0} definitions · showing 80", list.size())
                    : trf("{0} definitions", list.size()));
            c.setPadding(dp(2), dp(4), 0, dp(4));
            results.addView(c);
            int n = 0;
            for (final JSONObject sc : list) {
                if (++n > 80) break;
                LinearLayout row = listRow();
                LinearLayout tx = UiKit.column(act);
                tx.addView(rowTitle(sc.optString("name", s(sc, "implementationName"))));
                tx.addView(rowMeta(joinDot(protocolLabel(s(sc, "protocol")), privacyLabel(s(sc, "privacy")), s(sc, "language"))));
                String desc = stripHtml(s(sc, "description"));
                if (!desc.isEmpty()) {
                    TextView dt = UiKit.caption(act, desc);
                    dt.setMaxLines(2);
                    dt.setEllipsize(TextUtils.TruncateAt.END);
                    dt.setPadding(0, dp(2), 0, 0);
                    tx.addView(dt);
                }
                row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
                row.setOnClickListener(v -> {
                    if (debounce()) return;
                    d.dismiss();
                    openIndexerForm(sc, true);
                });
                results.addView(row);
            }
        };
        term.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { fill[0].run(); }
        });
        show(d);
        if (schemaCache != null) {
            fill[0].run();
        } else {
            results.addView(UiKit.loading(act, tr("Loading the indexer catalogue…")));
            if (schemaLoading) return;
            schemaLoading = true;
            async(api -> api.indexerSchema(), arr -> {
                schemaLoading = false;
                schemaCache = arr;
                if (open[0]) fill[0].run();
            }, t -> {
                schemaLoading = false;
                if (!open[0]) return;
                results.removeAllViews();
                results.addView(errorView(t, false));
            });
        }
    }

    /** Loads the app (sync) profiles, then opens the add / edit form. */
    private void openIndexerForm(final JSONObject src, final boolean isNew) {
        async(api -> api.appProfiles(), arr -> showIndexerForm(src, isNew, arr),
                t -> showIndexerForm(src, isNew, null));
    }

    // ------------------------------------------------------------------ add / edit form

    private void showIndexerForm(JSONObject src, final boolean isNew, JSONArray profiles) {
        final JSONObject ix = copy(src);
        if (isNew) {
            ix.remove("id");
            put(ix, "enable", true);
            if (ix.optString("name", "").isEmpty()) put(ix, "name", ix.optString("implementationName"));
            if (ix.optInt("priority", 0) <= 0) put(ix, "priority", 25);
            if (ix.optJSONArray("tags") == null) put(ix, "tags", new JSONArray());
        }
        JSONArray fl = ix.optJSONArray("fields");
        final JSONArray fields = fl == null ? new JSONArray() : fl;

        final LinearLayout body = dialogBody(tr(isNew ? "Add indexer" : "Edit indexer"),
                ix.optString("implementationName", ix.optString("name")));
        final TextView err = UiKit.caption(act, "");
        err.setTextColor(AppTheme.danger());
        err.setVisibility(View.GONE);
        err.setPadding(0, dp(10), 0, 0);
        body.addView(err);

        body.addView(textField(tr("Name"), "", ix.optString("name"), InputType.TYPE_CLASS_TEXT, false,
                v -> put(ix, "name", v.trim())));
        body.addView(switchField(tr("Enable"), "", ix.optBoolean("enable", true), b -> put(ix, "enable", b)));
        body.addView(textField(tr("Priority"), tr("1 is used first, 50 last. Default 25."), String.valueOf(ix.optInt("priority", 25)),
                InputType.TYPE_CLASS_NUMBER, false, v -> {
                    try {
                        put(ix, "priority", Integer.parseInt(v.trim()));
                    } catch (NumberFormatException ignored) {
                        // keep the previous value while typing
                    }
                }));
        if (profiles != null && profiles.length() > 0) {
            final JSONArray profs = profiles;
            String[] names = new String[profs.length()];
            int cur = 0;
            boolean found = false;
            for (int i = 0; i < profs.length(); i++) {
                JSONObject p = profs.optJSONObject(i);
                names[i] = p == null ? "?" : p.optString("name");
                if (p != null && p.optInt("id") == ix.optInt("appProfileId", -1)) {
                    cur = i;
                    found = true;
                }
            }
            if (!found) {
                JSONObject p0 = profs.optJSONObject(0);
                if (p0 != null) put(ix, "appProfileId", p0.optInt("id"));
            }
            body.addView(pickField(tr("Sync profile"), names, cur, which -> {
                JSONObject p = profs.optJSONObject(which);
                if (p != null) put(ix, "appProfileId", p.optInt("id"));
            }));
        }

        final LinearLayout fieldsBox = UiKit.column(act);
        body.addView(fieldsBox, new LinearLayout.LayoutParams(-1, -2));
        final boolean[] adv = {false};
        final boolean[] skipped = {false};
        final Runnable build = () -> {
            fieldsBox.removeAllViews();
            skipped[0] = false;
            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.optJSONObject(i);
                if (f == null) continue;
                if (f.optBoolean("advanced") && !adv[0]) continue;
                View v = fieldView(f, skipped);
                if (v != null) fieldsBox.addView(v);
            }
            if (skipped[0]) {
                TextView n = UiKit.caption(act, tr("Some settings of this indexer can only be changed in Prowlarr's web interface."));
                n.setPadding(0, dp(10), 0, 0);
                fieldsBox.addView(n);
            }
        };
        build.run();
        boolean hasAdv = false;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f != null && f.optBoolean("advanced")) hasAdv = true;
        }
        if (hasAdv) {
            final Button advBtn = UiKit.outline(act, tr("Show advanced settings"), null);
            advBtn.setOnClickListener(v -> {
                adv[0] = !adv[0];
                advBtn.setText(tr(adv[0] ? "Hide advanced settings" : "Show advanced settings"));
                build.run();
            });
            body.addView(UiKit.buttonRow(act, advBtn));
        }

        final AlertDialog d = dialog(body);
        final Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
        final Button test = UiKit.outline(act, tr("Test"), null);
        final Button save = UiKit.primary(act, tr("Save"), null);
        final boolean[] force = {false};
        test.setOnClickListener(v -> {
            err.setVisibility(View.GONE);
            setBusy(test, true);
            async(api -> {
                api.testIndexer(ix);
                return Boolean.TRUE;
            }, ok -> {
                setBusy(test, false);
                toast(tr("Test passed"));
            }, t -> {
                setBusy(test, false);
                err.setText(errText(t));
                err.setVisibility(View.VISIBLE);
            });
        });
        save.setOnClickListener(v -> {
            int prio = ix.optInt("priority", 25);
            if (prio < 1 || prio > 50) {
                err.setText(tr("Priority must be between 1 and 50."));
                err.setVisibility(View.VISIBLE);
                return;
            }
            if (ix.optString("name", "").trim().isEmpty()) {
                err.setText(tr("Enter a name."));
                err.setVisibility(View.VISIBLE);
                return;
            }
            final boolean forceSave = force[0];
            err.setVisibility(View.GONE);
            setBusy(save, true);
            setBusy(test, true);
            save.setText(tr("Saving…"));
            async(api -> {
                if (isNew) api.addIndexer(ix, forceSave);
                else api.updateIndexer(ix, forceSave);
                return Boolean.TRUE;
            }, ok -> {
                d.dismiss();
                toast(tr(isNew ? "Indexer added" : "Indexer saved"));
                indexerCache = null;
                section = S_INDEXERS;
                refresh();
            }, t -> {
                setBusy(save, false);
                setBusy(test, false);
                if (ProwlarrApi.isValidationError(t) && !forceSave) {
                    force[0] = true;
                    save.setText(tr("Save anyway"));
                    err.setText(errText(t) + "\n" + tr("Fix the settings, or tap Save anyway to keep them without a passing test."));
                } else {
                    save.setText(tr(force[0] ? "Save anyway" : "Save"));
                    err.setText(errText(t));
                }
                err.setVisibility(View.VISIBLE);
                scrollToTop(body);
            });
        });
        body.addView(UiKit.buttonRow(act, cancel, test, save));
        show(d);
    }

    private static void scrollToTop(View inner) {
        if (inner.getParent() instanceof ScrollView) {
            final ScrollView sv = (ScrollView) inner.getParent();
            sv.post(() -> sv.smoothScrollTo(0, 0));
        }
    }

    /** Builds the editor for one schema field; returns null for field types that cannot be edited here. */
    private View fieldView(final JSONObject f, boolean[] skipped) {
        String hidden = f.optString("hidden", "visible");
        Object val = f.opt("value");
        if ("hidden".equalsIgnoreCase(hidden)) return null;
        if ("hiddenIfNotSet".equalsIgnoreCase(hidden) && str(val).isEmpty()) return null;
        String type = f.optString("type", "textbox");
        String label = str(f.opt("label")).isEmpty() ? f.optString("name") : str(f.opt("label"));
        String help = stripHtml(str(f.opt("helpText")));

        if ("info".equals(type)) {
            String text = stripHtml(str(val));
            if (text.isEmpty()) text = help;
            if (text.isEmpty()) return null;
            TextView t = UiKit.caption(act, text);
            t.setPadding(dp(2), dp(12), 0, 0);
            return t;
        }
        if ("captcha".equals(type) || "oAuth".equals(type) || "cardigannCaptcha".equals(type)
                || "tag".equals(type) || "tagSelect".equals(type) || "keyValueList".equals(type)) {
            skipped[0] = true;
            return null;
        }

        if ("checkbox".equals(type)) {
            boolean on = val instanceof Boolean ? (Boolean) val : "true".equalsIgnoreCase(str(val));
            return switchField(label, help, on, b -> put(f, "value", b));
        }

        JSONArray opts = f.optJSONArray("selectOptions");
        if (("select".equals(type) || "multiSelect".equals(type)) && opts != null && opts.length() > 0) {
            final JSONArray options = opts;
            final String[] names = new String[options.length()];
            for (int i = 0; i < options.length(); i++) {
                JSONObject o = options.optJSONObject(i);
                names[i] = o == null ? "?" : o.optString("name", str(o.opt("value")));
            }
            if (val instanceof JSONArray || "multiSelect".equals(type)) {
                // Multi-value field (for example categories): keep it an array.
                final boolean[] checked = new boolean[options.length()];
                JSONArray cur = val instanceof JSONArray ? (JSONArray) val : new JSONArray();
                for (int i = 0; i < options.length(); i++) {
                    JSONObject o = options.optJSONObject(i);
                    String ov = o == null ? "" : str(o.opt("value"));
                    for (int j = 0; j < cur.length(); j++) if (str(cur.opt(j)).equals(ov)) checked[i] = true;
                }
                return multiPickField(label, help, names, checked, () -> {
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < options.length(); i++) {
                        JSONObject o = options.optJSONObject(i);
                        if (checked[i] && o != null) out.put(o.opt("value"));
                    }
                    put(f, "value", out);
                });
            }
            int idx = -1;
            for (int i = 0; i < options.length(); i++) {
                JSONObject o = options.optJSONObject(i);
                if (o != null && str(o.opt("value")).equals(str(val))) idx = i;
            }
            if (idx < 0) {
                idx = 0;
                JSONObject o0 = options.optJSONObject(0);
                if (o0 != null) put(f, "value", o0.opt("value"));
            }
            View v = pickField(label, names, idx, which -> {
                JSONObject o = options.optJSONObject(which);
                if (o != null) put(f, "value", o.opt("value"));
            });
            return withHelp(v, help);
        }
        if (val instanceof JSONArray || val instanceof JSONObject) {
            skipped[0] = true;
            return null;
        }

        final boolean numeric = "number".equals(type) || val instanceof Number;
        int inputType = InputType.TYPE_CLASS_TEXT;
        if ("password".equals(type)) inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        else if (numeric) inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        else if ("url".equals(type)) inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
        boolean mono = "url".equals(type) || "path".equals(type) || "filePath".equals(type);
        return textField(label, help, str(val), inputType, mono, v -> {
            if (numeric) {
                String t = v.trim();
                if (t.isEmpty()) {
                    put(f, "value", null);
                    return;
                }
                try {
                    put(f, "value", Long.valueOf(Long.parseLong(t)));
                } catch (NumberFormatException e) {
                    try {
                        put(f, "value", Double.valueOf(Double.parseDouble(t)));
                    } catch (NumberFormatException e2) {
                        // ignore partial input such as "-"
                    }
                }
            } else {
                put(f, "value", v);
            }
        });
    }

    private View withHelp(View v, String help) {
        if (help == null || help.isEmpty()) return v;
        LinearLayout box = UiKit.column(act);
        box.addView(v);
        TextView h = UiKit.caption(act, help);
        h.setPadding(dp(2), dp(4), 0, 0);
        box.addView(h);
        return box;
    }

    private View fieldLabel(String label) {
        TextView l = UiKit.caption(act, label);
        l.setPadding(dp(2), dp(12), 0, dp(6));
        return l;
    }

    private View textField(String label, String help, String value, int inputType, boolean mono, final TextSink sink) {
        LinearLayout box = UiKit.column(act);
        box.addView(fieldLabel(label));
        EditText e = UiKit.input(act, "");
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setText(value);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        else if ((inputType & InputType.TYPE_TEXT_VARIATION_PASSWORD) == 0) e.setTypeface(Typeface.DEFAULT);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence cs, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable ed) { sink.set(ed.toString()); }
        });
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        if (help != null && !help.isEmpty()) {
            TextView h = UiKit.caption(act, help);
            h.setPadding(dp(2), dp(4), 0, 0);
            box.addView(h);
        }
        return box;
    }

    private View switchField(String label, String help, boolean value, final BoolSink sink) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(0, dp(12), 0, dp(2));
        LinearLayout tx = UiKit.column(act);
        tx.addView(UiKit.subtitle(act, label));
        if (help != null && !help.isEmpty()) {
            TextView h = UiKit.caption(act, help);
            h.setPadding(0, dp(2), 0, 0);
            tx.addView(h);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.setMarginEnd(dp(12));
        row.addView(tx, tlp);
        Switch sw = UiStyle.newSwitch(act);
        sw.setChecked(value);
        UiStyle.styleSwitch(act, sw);
        sw.setOnCheckedChangeListener((b, checked) -> sink.set(checked));
        row.addView(sw);
        return row;
    }

    private View valueBox(TextView value) {
        LinearLayout field = UiKit.row(act);
        field.setPadding(dp(14), dp(11), dp(14), dp(11));
        field.setBackground(UiKit.pressable(act, AppTheme.inner(), AppTheme.outline(), UiKit.R_INNER));
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        field.addView(value, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView caret = UiKit.caption(act, "▾");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMarginStart(dp(8));
        field.addView(caret, clp);
        field.setClickable(true);
        return field;
    }

    private View pickField(final String label, final String[] options, int selected, final IntSink sink) {
        LinearLayout box = UiKit.column(act);
        box.addView(fieldLabel(label));
        final int[] sel = {selected};
        final TextView value = UiKit.text(act, selected >= 0 && selected < options.length ? options[selected] : "-",
                14f, AppTheme.text(), null);
        View field = valueBox(value);
        field.setOnClickListener(v -> {
            if (dead()) return;
            new AlertDialog.Builder(act).setTitle(label).setSingleChoiceItems(options, sel[0], (dd, which) -> {
                sel[0] = which;
                value.setText(options[which]);
                sink.set(which);
                dd.dismiss();
            }).show();
        });
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private View multiPickField(final String label, String help, final String[] options, final boolean[] checked,
                                final Runnable onChange) {
        LinearLayout box = UiKit.column(act);
        box.addView(fieldLabel(label));
        final TextView value = UiKit.text(act, multiSummary(options, checked), 14f, AppTheme.text(), null);
        View field = valueBox(value);
        field.setOnClickListener(v -> {
            if (dead()) return;
            final boolean[] work = checked.clone();
            new AlertDialog.Builder(act).setTitle(label)
                    .setMultiChoiceItems(options, work, (dd, which, isChecked) -> work[which] = isChecked)
                    .setNegativeButton(tr("Cancel"), null)
                    .setPositiveButton(tr("Done"), (dd, w) -> {
                        System.arraycopy(work, 0, checked, 0, checked.length);
                        value.setText(multiSummary(options, checked));
                        onChange.run();
                    }).show();
        });
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        if (help != null && !help.isEmpty()) {
            TextView h = UiKit.caption(act, help);
            h.setPadding(dp(2), dp(4), 0, 0);
            box.addView(h);
        }
        return box;
    }

    private static String multiSummary(String[] options, boolean[] checked) {
        List<String> on = new ArrayList<>();
        for (int i = 0; i < options.length; i++) if (checked[i]) on.add(options[i]);
        if (on.isEmpty()) return tr("None");
        if (on.size() > 3) return trf("{0} selected", on.size());
        return TextUtils.join(", ", on);
    }

    // ================================================================== SEARCH

    private void renderSearch() {
        ++requestCounter;
        content.removeAllViews();
        LinearLayout bar = UiKit.row(act);
        final EditText q = UiKit.input(act, tr("Search every enabled indexer"));
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setText(searchQuery);
        q.setSelection(q.getText().length());
        bar.addView(q, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Button go = UiKit.primary(act, tr("Search"), null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        bar.addView(go, blp);
        content.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        searchButton = go;
        final Runnable run = () -> {
            searchQuery = q.getText().toString().trim();
            runSearch();
        };
        go.setOnClickListener(v -> run.run());
        q.setOnEditorActionListener((v, actionId, ev) -> {
            run.run();
            return true;
        });

        final LinearLayout chips = UiKit.row(act);
        chips.setPadding(0, dp(12), 0, dp(4));
        content.addView(UiKit.chipScroller(act, chips), new LinearLayout.LayoutParams(-1, -2));
        final Runnable[] drawChips = new Runnable[1];
        drawChips[0] = () -> {
            chips.removeAllViews();
            for (int i = 0; i < CAT_NAMES.length; i++) {
                final int idx = i;
                TextView c = UiKit.chip(act, tr(CAT_NAMES[i]), idx == searchCat);
                c.setOnClickListener(v -> {
                    if (searchCat == idx) return;
                    searchCat = idx;
                    drawChips[0].run();
                    searchQuery = q.getText().toString().trim();
                    if (!searchQuery.isEmpty()) runSearch();
                });
                chips.addView(c, UiKit.chipLp(act));
            }
        };
        drawChips[0].run();

        searchBox = UiKit.column(act);
        content.addView(searchBox, new LinearLayout.LayoutParams(-1, -2));
        if (searchResults != null) {
            fillSearchResults();
        } else {
            searchBox.addView(UiKit.empty(act, tr("Search your indexers"),
                    tr("Type a title and search every enabled indexer at once. Tap a result to send it to the download client.")));
        }
    }

    private void runSearch() {
        final String q = searchQuery;
        if (q.isEmpty() || searchBox == null) return;
        final int req = ++requestCounter;
        final LinearLayout box = searchBox;
        final Button go = searchButton;
        box.removeAllViews();
        box.addView(UiKit.loading(act, tr("Searching your indexers… this can take a minute.")));
        setBusy(go, true);
        final int cat = CAT_IDS[searchCat];
        async(api -> api.search(q, "search", null, cat > 0 ? new int[]{cat} : null), arr -> {
            setBusy(go, false);
            if (req != requestCounter || section != S_SEARCH) return;
            searchResults = arr;
            fillSearchResults();
        }, t -> {
            setBusy(go, false);
            if (req != requestCounter || section != S_SEARCH) return;
            box.removeAllViews();
            box.addView(errorView(t, false));
        });
    }

    private void fillSearchResults() {
        if (searchBox == null) return;
        searchBox.removeAllViews();
        if (searchResults == null) return;
        if (searchResults.length() == 0) {
            searchBox.addView(UiKit.empty(act, tr("No releases found"), tr("Try a shorter title or another category.")));
            return;
        }
        List<JSONObject> rel = new ArrayList<>();
        for (int i = 0; i < searchResults.length(); i++) {
            JSONObject r = searchResults.optJSONObject(i);
            if (r != null) rel.add(r);
        }
        Collections.sort(rel, (a, b) -> {
            int c = b.optInt("seeders") - a.optInt("seeders");
            if (c != 0) return c;
            return Long.compare(b.optLong("size"), a.optLong("size"));
        });
        TextView count = UiKit.caption(act, rel.size() > 100 ? trf("{0} results · showing 100", rel.size()) : trf("{0} results", rel.size()));
        count.setPadding(dp(2), dp(6), 0, dp(8));
        searchBox.addView(count);
        LinearLayout card = listCard();
        int n = 0;
        for (JSONObject r : rel) {
            if (++n > 100) break;
            if (card.getChildCount() > 0) card.addView(separator(12));
            card.addView(releaseRow(r));
        }
        searchBox.addView(card);
    }

    private View releaseRow(final JSONObject r) {
        LinearLayout row = listRow();
        LinearLayout tx = UiKit.column(act);
        TextView t = UiKit.text(act, r.optString("title"), 13.5f, AppTheme.text(), UiKit.MEDIUM);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(t);
        tx.addView(rowMeta(joinDot(s(r, "indexer"), fmtSize(r.optLong("size")), fmtAge(ageMinutes(r)), categoryName(r))));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        boolean torrent = "torrent".equalsIgnoreCase(s(r, "protocol"));
        View badge;
        if (torrent) {
            int se = r.optInt("seeders");
            badge = UiKit.badge(act, trf("{0} seeders", se), se == 0 ? AppTheme.danger() : se < 5 ? AppTheme.warning() : AppTheme.success());
        } else {
            badge = UiKit.badge(act, tr(protocolLabel(s(r, "protocol"))), AppTheme.textMuted());
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        row.addView(badge, blp);
        row.setOnClickListener(v -> {
            if (!debounce()) showGrabSheet(r);
        });
        return row;
    }

    private static double ageMinutes(JSONObject r) {
        if (r.has("ageMinutes") && !r.isNull("ageMinutes")) return r.optDouble("ageMinutes", 0);
        return r.optDouble("age", 0) * 1440.0;
    }

    private static String categoryName(JSONObject r) {
        JSONArray cats = r.optJSONArray("categories");
        JSONObject c0 = cats == null ? null : cats.optJSONObject(0);
        return c0 == null ? "" : s(c0, "name");
    }

    private void showGrabSheet(final JSONObject r) {
        final LinearLayout body = dialogBody(tr("Send to download client?"), null);
        TextView title = UiKit.text(act, r.optString("title"), 13.5f, AppTheme.text(), UiKit.MEDIUM);
        title.setPadding(0, dp(8), 0, dp(6));
        title.setTextIsSelectable(true);
        body.addView(title);
        body.addView(UiKit.kv(act, tr("Indexer"), orDash(s(r, "indexer"))));
        body.addView(UiKit.kv(act, tr("Size"), fmtSize(r.optLong("size"))));
        String age = fmtAge(ageMinutes(r));
        if (!age.isEmpty()) body.addView(UiKit.kv(act, tr("Age"), age));
        if ("torrent".equalsIgnoreCase(s(r, "protocol"))) {
            body.addView(UiKit.kv(act, tr("Seeders"), String.valueOf(r.optInt("seeders"))));
            body.addView(UiKit.kv(act, tr("Peers"), String.valueOf(r.optInt("leechers"))));
        } else {
            body.addView(UiKit.kv(act, tr("Protocol"), protocolLabel(s(r, "protocol"))));
        }
        if (!r.isNull("grabs") && r.has("grabs")) body.addView(UiKit.kv(act, tr("Grabs"), String.valueOf(r.optInt("grabs"))));
        String cat = categoryName(r);
        if (!cat.isEmpty()) body.addView(UiKit.kv(act, tr("Category"), cat));
        String pub = fmtDateTime(s(r, "publishDate"));
        if (!pub.isEmpty()) body.addView(UiKit.kv(act, tr("Published"), pub));
        final AlertDialog d = dialog(body);
        final Button cancel = UiKit.outline(act, tr("Cancel"), v -> d.dismiss());
        final Button grab = UiKit.primary(act, tr("Send"), null);
        grab.setOnClickListener(v -> action(grab, api -> {
            api.grab(r);
            return null;
        }, tr("Sent to the download client"), d::dismiss));
        body.addView(UiKit.buttonRow(act, cancel, grab));
        show(d);
    }

    // ================================================================== APPS

    private void loadApps() {
        final int req = ++requestCounter;
        showLoading(tr("Loading apps…"));
        async(api -> api.applications(), arr -> {
            if (req != requestCounter || section != S_APPS) return;
            content.removeAllViews();
            LinearLayout bar = UiKit.row(act);
            bar.setPadding(0, 0, 0, dp(10));
            bar.addView(UiKit.caption(act, trf("{0} apps", arr.length())), new LinearLayout.LayoutParams(0, -2, 1.0f));
            final Button sync = UiKit.primary(act, tr("Sync now"), null);
            sync.setOnClickListener(v -> action(sync, api2 -> {
                api2.simpleCommand("ApplicationIndexerSync");
                return null;
            }, tr("Indexer sync started"), null));
            bar.addView(sync, new LinearLayout.LayoutParams(-2, -2));
            content.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            if (arr.length() == 0) {
                content.addView(UiKit.empty(act, tr("No apps connected"),
                        tr("Connect Radarr or Sonarr in Prowlarr's web interface (Settings, Apps) so they receive your indexers.")));
                return;
            }
            LinearLayout card = listCard();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                if (card.getChildCount() > 0) card.addView(separator(12));
                card.addView(appRow(a));
            }
            content.addView(card);
        }, t -> {
            if (req == requestCounter && section == S_APPS) showError(t);
        });
    }

    private View appRow(final JSONObject app) {
        LinearLayout box = UiKit.column(act);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout top = UiKit.row(act);
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(app.optString("name")));
        tx.addView(rowMeta(joinDot(app.optString("implementationName", app.optString("implementation")),
                syncLabel(s(app, "syncLevel")))));
        top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        boolean on = !"disabled".equals(s(app, "syncLevel"));
        top.addView(UiKit.badge(act, tr(on ? "Syncing" : "Paused"), on ? AppTheme.success() : AppTheme.textMuted()));
        box.addView(top);
        JSONArray fl = app.optJSONArray("fields");
        for (int i = 0; fl != null && i < fl.length(); i++) {
            JSONObject f = fl.optJSONObject(i);
            if (f != null && "baseUrl".equals(f.optString("name")) && !str(f.opt("value")).isEmpty()) {
                TextView u = UiKit.mono(act, str(f.opt("value")));
                u.setTextColor(AppTheme.textSecondary());
                u.setTextIsSelectable(false);
                u.setPadding(0, dp(4), 0, 0);
                box.addView(u);
            }
        }
        final Button test = UiKit.tonal(act, tr("Test"), null);
        test.setOnClickListener(v -> action(test, api -> {
            api.testApplication(app);
            return null;
        }, tr("Test passed"), null));
        final Button del = UiKit.danger(act, tr("Delete"), null);
        del.setOnClickListener(v -> confirm(tr("Remove app?"),
                trf("{0} stops receiving indexers from Prowlarr. Indexers already in it are not deleted by this.", app.optString("name")),
                tr("Remove"), true, () -> action(del, api -> {
                    api.deleteApplication(app.optInt("id"));
                    return null;
                }, tr("App removed"), this::refresh)));
        LinearLayout actions = UiKit.row(act);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(test, new LinearLayout.LayoutParams(-2, dp(36)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, dp(36));
        dlp.setMarginStart(dp(8));
        actions.addView(del, dlp);
        box.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private static String syncLabel(String level) {
        if ("fullSync".equals(level)) return tr("Full sync");
        if ("addOnly".equals(level)) return tr("Add and remove only");
        if ("disabled".equals(level)) return tr("Sync off");
        return level;
    }

    // ================================================================== STATS

    private void loadStats() {
        final int req = ++requestCounter;
        showLoading(tr("Loading statistics…"));
        async(api -> {
            JSONObject o = api.indexerStats();
            if (indexerNames.isEmpty()) {
                try { rememberNames(api.indexers()); } catch (Throwable ignored) {}
            }
            return o;
        }, o -> {
            if (req != requestCounter || section != S_STATS) return;
            renderStats(o);
        }, t -> {
            if (req == requestCounter && section == S_STATS) showError(t);
        });
    }

    private void renderStats(JSONObject o) {
        content.removeAllViews();
        JSONArray arr = o.optJSONArray("indexers");
        if (arr == null || arr.length() == 0) {
            content.addView(UiKit.empty(act, tr("No statistics yet"), tr("Statistics appear after your indexers have been searched.")));
            return;
        }
        List<JSONObject> list = new ArrayList<>();
        long tq = 0, tg = 0, tf = 0, avgSum = 0;
        int avgN = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject st = arr.optJSONObject(i);
            if (st == null) continue;
            list.add(st);
            tq += st.optInt("numberOfQueries") + st.optInt("numberOfRssQueries");
            tg += st.optInt("numberOfGrabs");
            tf += failures(st);
            int avg = st.optInt("averageResponseTime");
            if (avg > 0) {
                avgSum += avg;
                avgN++;
            }
        }
        Collections.sort(list, (a, b) -> (b.optInt("numberOfQueries") + b.optInt("numberOfRssQueries"))
                - (a.optInt("numberOfQueries") + a.optInt("numberOfRssQueries")));

        LinearLayout sum = UiKit.card(act);
        sum.addView(UiKit.overline(act, tr("Totals")));
        sum.addView(tileRow(tile(String.valueOf(tq), tr("Queries")), tile(String.valueOf(tg), tr("Grabs"))));
        LinearLayout r2 = tileRow(tile(String.valueOf(tf), tr("Failures")),
                tile(avgN > 0 ? (avgSum / avgN) + " ms" : "-", tr("Avg. response")));
        ((LinearLayout.LayoutParams) r2.getLayoutParams()).topMargin = dp(8);
        sum.addView(r2);
        content.addView(sum);

        TextView h = UiKit.overline(act, tr("Per indexer"));
        h.setPadding(dp(2), dp(6), 0, dp(8));
        content.addView(h);
        LinearLayout card = listCard();
        for (JSONObject st : list) {
            if (card.getChildCount() > 0) card.addView(separator(12));
            int id = st.optInt("indexerId");
            String name = s(st, "indexerName");
            if (name.isEmpty()) name = indexerNames.containsKey(id) ? indexerNames.get(id) : "#" + id;
            LinearLayout row = UiKit.row(act);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout tx = UiKit.column(act);
            tx.addView(rowTitle(name));
            tx.addView(rowMeta(trf("{0} queries · {1} RSS · {2} grabs", st.optInt("numberOfQueries"),
                    st.optInt("numberOfRssQueries"), st.optInt("numberOfGrabs"))));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
            int fl = failures(st);
            int avg = st.optInt("averageResponseTime");
            View badge = fl > 0 ? UiKit.badge(act, trf("{0} failed", fl), AppTheme.danger())
                    : UiKit.badge(act, avg > 0 ? avg + " ms" : tr("OK"), AppTheme.textMuted());
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.setMarginStart(dp(8));
            row.addView(badge, blp);
            card.addView(row);
        }
        content.addView(card);
    }

    private static int failures(JSONObject st) {
        return st.optInt("numberOfFailedQueries") + st.optInt("numberOfFailedRssQueries")
                + st.optInt("numberOfFailedAuthQueries") + st.optInt("numberOfFailedGrabs");
    }

    private LinearLayout tileRow(View a, View b) {
        LinearLayout row = UiKit.row(act);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -2, 1.0f);
        row.addView(a, la);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, -2, 1.0f);
        lb.setMarginStart(dp(8));
        row.addView(b, lb);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private View tile(String value, String label) {
        LinearLayout t = UiKit.inset(act);
        t.addView(UiKit.display(act, value));
        TextView l = UiKit.caption(act, label);
        l.setPadding(0, dp(2), 0, 0);
        t.addView(l);
        return t;
    }

    // ================================================================== HISTORY

    private void loadHistory() {
        final int req = ++requestCounter;
        final int pg = page;
        showLoading(tr("Loading history…"));
        async(api -> {
            JSONObject o = api.history(pg, PAGE_SIZE);
            if (indexerNames.isEmpty()) {
                try { rememberNames(api.indexers()); } catch (Throwable ignored) {}
            }
            return o;
        }, o -> {
            if (req != requestCounter || section != S_HISTORY) return;
            content.removeAllViews();
            JSONArray recs = o.optJSONArray("records");
            int total = o.optInt("totalRecords", recs == null ? 0 : recs.length());
            if (recs == null || recs.length() == 0) {
                content.addView(UiKit.empty(act, tr("No history yet"), tr("Searches, RSS checks and grabs appear here.")));
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

    private View historyRow(JSONObject r) {
        LinearLayout row = UiKit.row(act);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        int id = r.optInt("indexerId");
        String name = indexerNames.containsKey(id) ? indexerNames.get(id) : (id > 0 ? "#" + id : tr("All indexers"));
        LinearLayout tx = UiKit.column(act);
        tx.addView(rowTitle(name));
        JSONObject data = r.optJSONObject("data");
        String ev = s(r, "eventType");
        boolean ok = r.optBoolean("successful", true);
        String detail = dataStr(data, "query");
        if (detail.isEmpty()) detail = dataStr(data, "grabTitle");
        if (detail.isEmpty()) detail = dataStr(data, "title");
        if (!detail.isEmpty() && "indexerQuery".equals(ev)) detail = "“" + detail + "”";
        String source = dataStr(data, "source");
        String results = dataStr(data, "queryResults");
        tx.addView(rowMeta(joinDot(detail, results.isEmpty() ? "" : trf("{0} results", results), source,
                relTime(parseIso(s(r, "date"))))));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        String label;
        int color;
        if ("releaseGrabbed".equals(ev)) { label = "Grabbed"; color = AppTheme.success(); }
        else if ("indexerQuery".equals(ev)) { label = "Search"; color = AppTheme.accent(); }
        else if ("indexerRss".equals(ev)) { label = "RSS"; color = AppTheme.textMuted(); }
        else if ("indexerAuth".equals(ev)) { label = "Login"; color = AppTheme.textMuted(); }
        else if ("indexerInfo".equals(ev)) { label = "Info"; color = AppTheme.textMuted(); }
        else { label = ev.isEmpty() ? "Event" : capitalize(ev); color = AppTheme.textMuted(); }
        if (!ok) color = AppTheme.danger();
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMarginStart(dp(8));
        row.addView(UiKit.badge(act, ok ? tr(label) : trf("{0} failed", tr(label)), color), blp);
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
            try { o.put("tasks", api.tasks()); } catch (Throwable ignored) {}
            try { o.put("clients", api.downloadClients()); } catch (Throwable ignored) {}
            try { o.put("tags", api.tags()); } catch (Throwable ignored) {}
            return o;
        }, o -> {
            if (req != requestCounter || section != S_SYSTEM) return;
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
            String v = s(st, "version");
            if (!v.isEmpty() && !v.equals(version)) {
                version = v;
                updateHeader();
            }
            c.addView(UiKit.kv(act, tr("Version"), orDash(v)));
            c.addView(UiKit.kv(act, tr("Operating system"), orDash((s(st, "osName") + " " + s(st, "osVersion")).trim())));
            String rt = (s(st, "runtimeName") + " " + s(st, "runtimeVersion")).trim();
            if (!rt.isEmpty()) c.addView(UiKit.kv(act, tr("Runtime"), rt));
            String started = fmtDateTime(s(st, "startTime"));
            if (!started.isEmpty()) c.addView(UiKit.kv(act, tr("Started"), started));
            String appData = s(st, "appData");
            if (!appData.isEmpty()) {
                TextView l = UiKit.caption(act, tr("App data"));
                l.setPadding(0, dp(8), 0, dp(2));
                c.addView(l);
                c.addView(UiKit.mono(act, appData));
            }
        }
        content.addView(c);

        addHealthCard(o.optJSONArray("health"));

        c = UiKit.card(act);
        c.addView(UiKit.overline(act, tr("Download clients")));
        JSONArray clients = o.optJSONArray("clients");
        if (clients == null || clients.length() == 0) {
            c.addView(UiKit.body(act, tr("None configured. Releases you grab here need a download client in Prowlarr.")));
        } else {
            for (int i = 0; i < clients.length(); i++) {
                JSONObject cl = clients.optJSONObject(i);
                if (cl == null) continue;
                boolean on = cl.optBoolean("enable", true);
                LinearLayout row = UiKit.row(act);
                row.setPadding(0, dp(6), 0, dp(6));
                LinearLayout tx = UiKit.column(act);
                tx.addView(rowTitle(cl.optString("name")));
                tx.addView(rowMeta(joinDot(cl.optString("implementationName", cl.optString("implementation")),
                        protocolLabel(s(cl, "protocol")))));
                row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
                row.addView(UiKit.badge(act, tr(on ? "Enabled" : "Disabled"), on ? AppTheme.success() : AppTheme.textMuted()));
                c.addView(row);
            }
        }
        content.addView(c);

        JSONArray tags = o.optJSONArray("tags");
        if (tags != null && tags.length() > 0) {
            c = UiKit.card(act);
            c.addView(UiKit.overline(act, tr("Tags")));
            List<String> names = new ArrayList<>();
            for (int i = 0; i < tags.length(); i++) {
                JSONObject tg = tags.optJSONObject(i);
                if (tg != null) names.add(tg.optString("label"));
            }
            c.addView(UiKit.body(act, TextUtils.join(" · ", names)));
            content.addView(c);
        }

        JSONArray tasks = o.optJSONArray("tasks");
        if (tasks != null && tasks.length() > 0) {
            c = UiKit.card(act);
            c.addView(UiKit.overline(act, tr("Scheduled tasks")));
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject tk = tasks.optJSONObject(i);
                if (tk == null) continue;
                LinearLayout row = UiKit.column(act);
                row.setPadding(0, dp(6), 0, dp(6));
                row.addView(rowTitle(tk.optString("name", s(tk, "taskName"))));
                String last = relTime(parseIso(s(tk, "lastExecution")));
                String next = fmtDateTime(s(tk, "nextExecution"));
                row.addView(rowMeta(joinDot(last.isEmpty() ? "" : trf("Last run {0}", last),
                        next.isEmpty() ? "" : trf("Next {0}", next))));
                c.addView(row);
            }
            content.addView(c);
        }

        c = UiKit.card(act);
        c.addView(UiKit.overline(act, tr("Commands")));
        c.addView(UiKit.buttonRow(act,
                commandButton(tr("Sync apps"), "ApplicationIndexerSync", tr("Indexer sync started")),
                commandButton(tr("Check health"), "CheckHealth", tr("Health check started"))));
        c.addView(UiKit.buttonRow(act,
                commandButton(tr("Backup"), "Backup", tr("Backup started")),
                commandButton(tr("Housekeeping"), "Housekeeping", tr("Housekeeping started"))));
        content.addView(c);

        TextView note = UiKit.caption(act, tr("Advanced settings are in Prowlarr's own web interface."));
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
                String type = s(h, "type");
                int color = "error".equalsIgnoreCase(type) ? AppTheme.danger()
                        : "warning".equalsIgnoreCase(type) ? AppTheme.warning() : AppTheme.accent();
                String label = "error".equalsIgnoreCase(type) ? "Error" : "warning".equalsIgnoreCase(type) ? "Warning" : "Notice";
                LinearLayout row = UiKit.row(act);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, dp(6), 0, dp(6));
                row.addView(UiKit.badge(act, tr(label), color));
                TextView t = UiKit.body(act, s(h, "message"));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                lp.setMarginStart(dp(10));
                row.addView(t, lp);
                c.addView(row);
            }
        }
        content.addView(c);
    }

    // ================================================================== dialogs

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

    /** Dialog whose whole body scrolls (forms, detail sheets). */
    private AlertDialog dialog(View body) {
        ScrollView sv = new ScrollView(act);
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        return dialogFor(sv);
    }

    /** Dialog with its own inner scroll area (result lists). */
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

    private TextView rowTitle(String text) {
        return UiKit.singleLine(UiKit.text(act, text, 14f, AppTheme.text(), UiKit.MEDIUM));
    }

    private TextView rowMeta(String text) {
        TextView t = UiKit.singleLine(UiKit.caption(act, text));
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    // ================================================================== formatting

    private static String protocolLabel(String p) {
        if ("torrent".equalsIgnoreCase(p)) return tr("Torrent");
        if ("usenet".equalsIgnoreCase(p)) return tr("Usenet");
        return capitalize(p);
    }

    private static String privacyLabel(String p) {
        if ("public".equalsIgnoreCase(p)) return tr("Public");
        if ("semiPrivate".equalsIgnoreCase(p)) return tr("Semi-private");
        if ("private".equalsIgnoreCase(p)) return tr("Private");
        return capitalize(p);
    }

    private static String capitalize(String v) {
        return v == null || v.isEmpty() ? "" : Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    private static String orDash(String v) {
        return v == null || v.isEmpty() ? "-" : v;
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

    private static String fmtSize(long bytes) {
        return com.jellyarr.app.util.Fmt.bytes(bytes);
    }

    private static String fmtAge(double minutes) {
        if (minutes <= 0) return "";
        if (minutes < 60) return trf("{0}m", (int) minutes);
        if (minutes < 2880) return trf("{0}h", (int) (minutes / 60));
        return trf("{0}d", (int) (minutes / 1440));
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

    private static String fmtTime(long ms) {
        long diff = ms - System.currentTimeMillis();
        String pattern = diff < 20 * 3600000L ? "HH:mm" : "d MMM, HH:mm";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(ms));
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
