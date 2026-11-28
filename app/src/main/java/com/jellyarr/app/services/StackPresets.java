package com.jellyarr.app.services;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ready-made stack presets: Prowlarr indexers, plus Radarr/Sonarr quality profiles with size limits and a few
 * custom formats. The catalogue lives in {@code assets/stack_presets.json}; a file named
 * {@code stack_presets.json} in the app's files directory replaces it (so it can be updated without a new release).
 *
 * Nothing the user created is touched: everything this class creates is named "<prefix> ..." and re-applying
 * updates those items in place instead of duplicating them.
 */
public final class StackPresets {

    public static final String OVERRIDE_FILE = "stack_presets.json";

    public static final class Indexer {
        public String id, label, group, note;
        public boolean recommended, cloudflare;
    }

    public static final class Profile {
        public String id, name, summary, cutoff;
        public final List<String> qualities = new ArrayList<>();
        /** app ("radarr" / "sonarr") -> {min, preferred, max} in MB per minute. */
        public final Map<String, double[]> sizes = new HashMap<>();
        /** custom format key -> score. */
        public final Map<String, Integer> scores = new LinkedHashMap<>();
    }

    public static final class Format {
        public String key, name;
        public JSONArray specs = new JSONArray();
    }

    /** What the user picked in the dialog. */
    public static final class Selection {
        public final Set<String> indexerIds = new HashSet<>();
        public final Set<String> profileIds = new HashSet<>();
        public boolean radarr = true, sonarr = true, sizeLimits = true, smartFilters = true;

        public boolean isEmpty() { return indexerIds.isEmpty() && profileIds.isEmpty(); }
    }

    public interface Log { void line(String s); }

    public String prefix = "Jellyarr";
    public final List<Indexer> indexers = new ArrayList<>();
    public final List<Profile> profiles = new ArrayList<>();
    public final List<Format> formats = new ArrayList<>();

    private StackPresets() {}

    // ------------------------------------------------------------------ loading

    public static StackPresets load(Context ctx) throws Exception {
        String json = null;
        try {
            File f = new File(ctx.getFilesDir(), OVERRIDE_FILE);
            if (f.isFile() && f.length() > 0 && f.length() < 512 * 1024) {
                String t = readAll(new FileInputStream(f));
                new JSONObject(t); // valid JSON or fall back to the bundled copy
                json = t;
            }
        } catch (Throwable ignored) {
            json = null;
        }
        if (json == null) json = readAll(ctx.getAssets().open(OVERRIDE_FILE));
        return parse(new JSONObject(json));
    }

    static StackPresets parse(JSONObject root) throws Exception {
        StackPresets p = new StackPresets();
        p.prefix = root.optString("namePrefix", "Jellyarr");
        JSONArray ix = root.optJSONArray("indexers");
        for (int i = 0; ix != null && i < ix.length(); i++) {
            JSONObject o = ix.getJSONObject(i);
            Indexer d = new Indexer();
            d.id = o.getString("id");
            d.label = o.optString("label", d.id);
            d.group = o.optString("group", "general");
            d.note = o.optString("note", "");
            d.recommended = o.optBoolean("recommended", false);
            d.cloudflare = o.optBoolean("cloudflare", false);
            p.indexers.add(d);
        }
        JSONArray qp = root.optJSONArray("qualityProfiles");
        for (int i = 0; qp != null && i < qp.length(); i++) {
            JSONObject o = qp.getJSONObject(i);
            Profile d = new Profile();
            d.id = o.getString("id");
            d.name = o.getString("name");
            d.summary = o.optString("summary", "");
            d.cutoff = o.optString("cutoff", "");
            JSONArray q = o.getJSONArray("qualities");
            for (int j = 0; j < q.length(); j++) d.qualities.add(q.getString(j));
            JSONObject sizes = o.optJSONObject("sizes");
            if (sizes != null) {
                for (String app : new String[]{"radarr", "sonarr"}) {
                    JSONObject s = sizes.optJSONObject(app);
                    if (s != null) d.sizes.put(app, new double[]{s.optDouble("min", 0), s.optDouble("preferred", 0), s.optDouble("max", 0)});
                }
            }
            JSONObject sc = o.optJSONObject("scores");
            if (sc != null) {
                java.util.Iterator<String> it = sc.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    d.scores.put(k, sc.getInt(k));
                }
            }
            p.profiles.add(d);
        }
        JSONArray cf = root.optJSONArray("customFormats");
        for (int i = 0; cf != null && i < cf.length(); i++) {
            JSONObject o = cf.getJSONObject(i);
            Format f = new Format();
            f.key = o.getString("key");
            f.name = o.getString("name");
            f.specs = o.getJSONArray("specs");
            p.formats.add(f);
        }
        return p;
    }

    private static String readAll(InputStream is) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { is.close(); } catch (Throwable ignored) {}
        }
    }

    public String fullName(String name) { return prefix + " " + name; }

    // ------------------------------------------------------------------ apply

    /** Blocking: run it on a worker thread. Progress goes to {@code log}. Returns true when nothing failed. */
    public boolean apply(Context ctx, Selection sel, Log log) {
        boolean ok = true;
        if (!sel.indexerIds.isEmpty()) ok &= applyIndexers(ctx, sel, log);
        if (!sel.profileIds.isEmpty()) {
            if (sel.radarr) ok &= applyQuality(radarrAdapter(ctx), "radarr", sel, log);
            if (sel.sonarr) ok &= applyQuality(sonarrAdapter(ctx), "sonarr", sel, log);
        }
        return ok;
    }

    // ---- indexers

    private boolean applyIndexers(Context ctx, Selection sel, Log log) {
        log.line("Prowlarr: indexers");
        ProwlarrApi api;
        try {
            api = ProwlarrApi.get(ctx);
        } catch (Throwable t) {
            log.line("  ✖ Prowlarr is not available: " + t.getMessage());
            return false;
        }
        boolean ok = true;
        try {
            Map<String, JSONObject> schema = new HashMap<>();
            JSONArray sch = api.indexerSchema();
            for (int i = 0; i < sch.length(); i++) {
                JSONObject d = sch.optJSONObject(i);
                if (d == null) continue;
                String key = d.optString("definitionName", "").toLowerCase(Locale.ROOT);
                if (!key.isEmpty() && !schema.containsKey(key)) schema.put(key, d);
            }
            Set<String> have = new HashSet<>();
            JSONArray cur = api.indexers();
            for (int i = 0; i < cur.length(); i++) {
                JSONObject o = cur.optJSONObject(i);
                if (o != null) have.add(o.optString("definitionName", "").toLowerCase(Locale.ROOT));
            }
            int appProfileId = 1;
            try {
                JSONArray aps = api.appProfiles();
                if (aps.length() > 0) appProfileId = aps.getJSONObject(0).optInt("id", 1);
                for (int i = 0; i < aps.length(); i++) {
                    JSONObject a = aps.getJSONObject(i);
                    if ("standard".equalsIgnoreCase(a.optString("name"))) { appProfileId = a.optInt("id", appProfileId); break; }
                }
            } catch (Throwable ignored) {
                // keep the default id
            }
            int added = 0;
            for (Indexer ix : indexers) {
                if (!sel.indexerIds.contains(ix.id)) continue;
                String key = ix.id.toLowerCase(Locale.ROOT);
                if (have.contains(key)) { log.line("  • " + ix.label + ": already added"); continue; }
                JSONObject def = schema.get(key);
                if (def == null) { log.line("  • " + ix.label + ": not available in this Prowlarr version, skipped"); continue; }
                try {
                    JSONObject body = new JSONObject(def.toString());
                    body.put("name", ix.label);
                    body.put("enable", true);
                    body.put("appProfileId", appProfileId);
                    body.put("priority", 25);
                    body.put("tags", new JSONArray());
                    body.remove("id");
                    // Without forceSave Prowlarr tests the indexer first and refuses it when the test fails.
                    api.addIndexer(body, false);
                    added++;
                    log.line("  ✔ " + ix.label + ": added and tested");
                } catch (Throwable t) {
                    ok = false;
                    String msg = String.valueOf(t.getMessage());
                    if (msg.length() > 160) msg = msg.substring(0, 160) + "…";
                    log.line("  ✖ " + ix.label + ": " + msg + (ix.cloudflare ? " (this site is often behind Cloudflare)" : "") + " — skipped");
                }
            }
            if (added > 0) {
                try {
                    api.simpleCommand("ApplicationIndexerSync");
                    log.line("  ✔ Indexers are being pushed to Radarr and Sonarr");
                } catch (Throwable t) {
                    log.line("  • Could not trigger the push; tap SYNC STACK to finish");
                }
            }
        } catch (Throwable t) {
            ok = false;
            log.line("  ✖ " + t.getMessage());
        }
        return ok;
    }

    // ---- Radarr / Sonarr

    private interface Arr {
        String label() ;
        JSONObject schema() throws Exception;
        JSONArray profiles() throws Exception;
        JSONObject addProfile(JSONObject p) throws Exception;
        void updateProfile(JSONObject p) throws Exception;
        JSONArray defs() throws Exception;
        void updateDef(JSONObject d) throws Exception;
        JSONArray formats() throws Exception;
        JSONObject addFormat(JSONObject f) throws Exception;
        void updateFormat(JSONObject f) throws Exception;
    }

    private static Arr radarrAdapter(Context ctx) {
        final RadarrApi a;
        try { a = RadarrApi.get(ctx); } catch (Throwable t) { return failed("Radarr", t); }
        return new Arr() {
            public String label() { return "Radarr"; }
            public JSONObject schema() throws Exception { return a.qualityProfileSchema(); }
            public JSONArray profiles() throws Exception { return a.qualityProfiles(); }
            public JSONObject addProfile(JSONObject p) throws Exception { return a.addQualityProfile(p); }
            public void updateProfile(JSONObject p) throws Exception { a.updateQualityProfile(p); }
            public JSONArray defs() throws Exception { return a.qualityDefinitions(); }
            public void updateDef(JSONObject d) throws Exception { a.updateQualityDefinition(d); }
            public JSONArray formats() throws Exception { return a.customFormats(); }
            public JSONObject addFormat(JSONObject f) throws Exception { return a.addCustomFormat(f); }
            public void updateFormat(JSONObject f) throws Exception { a.updateCustomFormat(f); }
        };
    }

    private static Arr sonarrAdapter(Context ctx) {
        final SonarrApi a;
        try { a = SonarrApi.get(ctx); } catch (Throwable t) { return failed("Sonarr", t); }
        return new Arr() {
            public String label() { return "Sonarr"; }
            public JSONObject schema() throws Exception { return a.qualityProfileSchema(); }
            public JSONArray profiles() throws Exception { return a.qualityProfiles(); }
            public JSONObject addProfile(JSONObject p) throws Exception { return a.addQualityProfile(p); }
            public void updateProfile(JSONObject p) throws Exception { a.updateQualityProfile(p); }
            public JSONArray defs() throws Exception { return a.qualityDefinitions(); }
            public void updateDef(JSONObject d) throws Exception { a.updateQualityDefinition(d); }
            public JSONArray formats() throws Exception { return a.customFormats(); }
            public JSONObject addFormat(JSONObject f) throws Exception { return a.addCustomFormat(f); }
            public void updateFormat(JSONObject f) throws Exception { a.updateCustomFormat(f); }
        };
    }

    /** An Arr whose every call fails with the original reason (service not installed or not running). */
    private static Arr failed(final String label, final Throwable why) {
        return new Arr() {
            private <T> T no() throws Exception { throw new Exception(why.getMessage()); }
            public String label() { return label; }
            public JSONObject schema() throws Exception { return no(); }
            public JSONArray profiles() throws Exception { return no(); }
            public JSONObject addProfile(JSONObject p) throws Exception { return no(); }
            public void updateProfile(JSONObject p) throws Exception { no(); }
            public JSONArray defs() throws Exception { return no(); }
            public void updateDef(JSONObject d) throws Exception { no(); }
            public JSONArray formats() throws Exception { return no(); }
            public JSONObject addFormat(JSONObject f) throws Exception { return no(); }
            public void updateFormat(JSONObject f) throws Exception { no(); }
        };
    }

    private static boolean isNotFound(Throwable t) {
        String m = String.valueOf(t.getMessage());
        return m.contains("HTTP 404");
    }

    private boolean applyQuality(Arr arr, String app, Selection sel, Log log) {
        log.line(arr.label() + ": quality profiles");
        boolean ok = true;
        try {
            arr.profiles(); // fails fast (with a readable reason) when the service is down
        } catch (Throwable t) {
            log.line("  ✖ " + arr.label() + " is not available: " + t.getMessage());
            return false;
        }

        List<Profile> chosen = new ArrayList<>();
        for (Profile p : profiles) if (sel.profileIds.contains(p.id)) chosen.add(p);

        // 1. custom formats (only the ones the chosen profiles score)
        Map<String, Integer> formatIds = new HashMap<>();
        if (sel.smartFilters) {
            Set<String> need = new HashSet<>();
            for (Profile p : chosen) need.addAll(p.scores.keySet());
            if (!need.isEmpty()) {
                try {
                    Map<String, Integer> existing = new HashMap<>();
                    JSONArray cur = arr.formats();
                    for (int i = 0; i < cur.length(); i++) {
                        JSONObject o = cur.optJSONObject(i);
                        if (o != null) existing.put(o.optString("name"), o.optInt("id"));
                    }
                    for (Format f : formats) {
                        if (!need.contains(f.key)) continue;
                        try {
                            JSONObject body = formatBody(f);
                            Integer id = existing.get(body.getString("name"));
                            if (id != null) {
                                body.put("id", (int) id);
                                arr.updateFormat(body);
                                formatIds.put(f.key, id);
                            } else {
                                JSONObject made = arr.addFormat(body);
                                formatIds.put(f.key, made.getInt("id"));
                            }
                            log.line("  ✔ Filter: " + f.name);
                        } catch (Throwable t) {
                            ok = false;
                            log.line("  ✖ Filter " + f.name + ": " + t.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    if (isNotFound(t)) log.line("  • This " + arr.label() + " version has no custom formats; filters skipped");
                    else { ok = false; log.line("  ✖ Filters: " + t.getMessage()); }
                }
            }
        }

        // 2. size limits (global per quality in *arr)
        if (sel.sizeLimits) {
            try {
                Map<String, double[]> want = new LinkedHashMap<>();
                for (Profile p : chosen) {
                    double[] s = p.sizes.get(app);
                    if (s == null) continue;
                    for (String q : p.qualities) want.put(q, s);
                }
                JSONArray defs = arr.defs();
                int changed = 0;
                for (int i = 0; i < defs.length(); i++) {
                    JSONObject d = defs.optJSONObject(i);
                    if (d == null) continue;
                    JSONObject quality = d.optJSONObject("quality");
                    String qn = quality == null ? "" : quality.optString("name");
                    double[] s = want.get(qn);
                    if (s == null) continue;
                    d.put("minSize", s[0]);
                    d.put("preferredSize", s[1]);
                    d.put("maxSize", s[2]);
                    arr.updateDef(d);
                    changed++;
                }
                log.line("  ✔ Size limits set for " + changed + " qualities");
            } catch (Throwable t) {
                ok = false;
                log.line("  ✖ Size limits: " + t.getMessage());
            }
        }

        // 3. profiles (the schema is read after the formats exist, so it lists them)
        try {
            Map<String, Integer> existing = new HashMap<>();
            JSONArray cur = arr.profiles();
            for (int i = 0; i < cur.length(); i++) {
                JSONObject o = cur.optJSONObject(i);
                if (o != null) existing.put(o.optString("name"), o.optInt("id"));
            }
            for (Profile p : chosen) {
                try {
                    JSONObject body = profileBody(arr.schema(), p, formatIds);
                    String name = fullName(p.name);
                    body.put("name", name);
                    Integer id = existing.get(name);
                    if (id != null) {
                        body.put("id", (int) id);
                        arr.updateProfile(body);
                        log.line("  ✔ " + name + ": updated");
                    } else {
                        arr.addProfile(body);
                        log.line("  ✔ " + name + ": created");
                    }
                } catch (Throwable t) {
                    ok = false;
                    log.line("  ✖ " + fullName(p.name) + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            ok = false;
            log.line("  ✖ " + t.getMessage());
        }
        return ok;
    }

    private static JSONObject formatBody(Format f) throws Exception {
        JSONObject b = new JSONObject();
        b.put("name", f.name);
        b.put("includeCustomFormatWhenRenaming", false);
        JSONArray specs = new JSONArray();
        for (int i = 0; i < f.specs.length(); i++) {
            JSONObject s = f.specs.getJSONObject(i);
            JSONObject spec = new JSONObject();
            spec.put("name", s.optString("name", f.name));
            spec.put("implementation", s.optString("implementation", "ReleaseTitleSpecification"));
            spec.put("negate", s.optBoolean("negate", false));
            spec.put("required", s.optBoolean("required", true));
            spec.put("fields", new JSONArray().put(new JSONObject().put("name", "value").put("value", s.getString("regex"))));
            specs.put(spec);
        }
        b.put("specifications", specs);
        return b;
    }

    /** Builds a profile from the service's own template: allowed qualities, cutoff and custom format scores. */
    static JSONObject profileBody(JSONObject schema, Profile p, Map<String, Integer> formatIds) throws Exception {
        JSONObject b = new JSONObject(schema.toString());
        b.remove("id");
        b.put("upgradeAllowed", true);

        Set<String> allowedNames = new HashSet<>(p.qualities);
        JSONArray items = b.getJSONArray("items");
        int lastAllowedId = -1;
        int cutoffId = -1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            JSONArray kids = it.optJSONArray("items");
            boolean isGroup = kids != null && kids.length() > 0;
            if (!isGroup) {
                JSONObject q = it.optJSONObject("quality");
                String qn = q == null ? "" : q.optString("name");
                boolean allow = allowedNames.contains(qn);
                it.put("allowed", allow);
                if (allow) {
                    lastAllowedId = q.optInt("id");
                    if (qn.equals(p.cutoff)) cutoffId = q.optInt("id");
                }
            } else {
                boolean any = false;
                boolean isCutoff = false;
                for (int j = 0; j < kids.length(); j++) {
                    JSONObject k = kids.getJSONObject(j);
                    JSONObject q = k.optJSONObject("quality");
                    String qn = q == null ? "" : q.optString("name");
                    if (allowedNames.contains(qn)) any = true;
                    if (qn.equals(p.cutoff)) isCutoff = true;
                }
                for (int j = 0; j < kids.length(); j++) kids.getJSONObject(j).put("allowed", any);
                it.put("allowed", any);
                if (any) {
                    lastAllowedId = it.optInt("id");
                    if (isCutoff) cutoffId = it.optInt("id");
                }
            }
        }
        if (lastAllowedId < 0) throw new Exception("none of the preset qualities exist in this version");
        b.put("cutoff", cutoffId >= 0 ? cutoffId : lastAllowedId);

        JSONArray fi = b.optJSONArray("formatItems");
        if (fi != null) {
            Map<Integer, Integer> byId = new HashMap<>();
            for (Map.Entry<String, Integer> e : p.scores.entrySet()) {
                Integer fid = formatIds.get(e.getKey());
                if (fid != null) byId.put(fid, e.getValue());
            }
            for (int i = 0; i < fi.length(); i++) {
                JSONObject f = fi.getJSONObject(i);
                Integer s = byId.get(f.optInt("format"));
                f.put("score", s == null ? 0 : (int) s);
            }
            b.put("minFormatScore", 0);
            b.put("cutoffFormatScore", 0);
        }
        return b;
    }
}
