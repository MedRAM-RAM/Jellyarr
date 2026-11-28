package com.jellyarr.app.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Thin client for the local Sonarr REST API (http://127.0.0.1:8989/api/v3; the v3 API is also what
 * Sonarr v4 serves). Every method blocks: call it from a background thread.
 *
 * Errors are {@link ApiException}s with readable messages: "not running" for a refused connection,
 * and the server's own validation {@code errorMessage} / {@code message} for 4xx answers.
 */
public final class SonarrApi {

    private static final String HOST = "http://127.0.0.1:8989";
    private static final String BASE = HOST + "/api/v3";
    private static final int MAX_BODY_BYTES = 48 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final long KEY_TTL_MS = 60_000L;

    public static final String NOT_RUNNING =
            "Sonarr is not running (cannot reach 127.0.0.1:8989). Start it from the Services tab, then retry.";
    public static final String NO_KEY = "Sonarr API key not found. Start Sonarr once so it creates its config.";

    /** Readable failure with a coarse kind so the screen can show a "not running" state. */
    public static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;

        public static final int OTHER = 0;
        public static final int NOT_RUNNING = 1;
        public static final int AUTH = 2;
        public static final int TIMEOUT = 3;
        public static final int NO_KEY = 4;

        public final int kind;
        public final int httpCode;

        public ApiException(int kind, int httpCode, String message) {
            super(message);
            this.kind = kind;
            this.httpCode = httpCode;
        }
    }

    public static boolean isNotRunning(Throwable t) {
        return t instanceof ApiException && ((ApiException) t).kind == ApiException.NOT_RUNNING;
    }

    public static boolean isNoKey(Throwable t) {
        return t instanceof ApiException && ((ApiException) t).kind == ApiException.NO_KEY;
    }

    public static boolean isAuthError(Throwable t) {
        return t instanceof ApiException && ((ApiException) t).kind == ApiException.AUTH;
    }

    private static volatile String cachedKey = null;
    private static volatile long cachedKeyAt = 0L;

    private final String key;

    private SonarrApi(String key) {
        this.key = key;
    }

    /** Returns a client, or throws a readable error when Sonarr has no API key yet. */
    public static SonarrApi get(Context ctx) throws Exception {
        String k = cachedKey;
        if (k == null || System.currentTimeMillis() - cachedKeyAt > KEY_TTL_MS) {
            MediaServiceManager mgr = MediaServiceManager.getInstance(ctx.getApplicationContext());
            k = mgr == null ? null : mgr.getServiceApiKey("sonarr");
            if (k == null || k.trim().isEmpty()) {
                cachedKey = null;
                throw new ApiException(ApiException.NO_KEY, 0, NO_KEY);
            }
            k = k.trim();
            cachedKey = k;
            cachedKeyAt = System.currentTimeMillis();
        }
        return new SonarrApi(k);
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** ISO-8601 UTC instant (what Sonarr's own UI sends for calendar ranges). */
    public static String isoUtc(long epochMs) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMs));
    }

    // ------------------------------------------------------------------ transport

    private String call(String method, String path, String body, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("X-Api-Key", key);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                } finally {
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is, MAX_BODY_BYTES);
            if (code == 401) {
                cachedKey = null;
                throw new ApiException(ApiException.AUTH, code,
                        "Sonarr rejected the API key (HTTP 401). Restart Sonarr from the Services tab, then retry.");
            }
            if (code >= 400) throw new ApiException(ApiException.OTHER, code, errorMessage(code, text));
            return text;
        } catch (ConnectException | NoRouteToHostException e) {
            throw new ApiException(ApiException.NOT_RUNNING, 0, NOT_RUNNING);
        } catch (SocketTimeoutException e) {
            throw new ApiException(ApiException.TIMEOUT, 0, "Sonarr did not answer in time. It may be busy; try again shortly.");
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("ECONNREFUSED") || m.contains("Failed to connect") || m.contains("Connection refused")) {
                throw new ApiException(ApiException.NOT_RUNNING, 0, NOT_RUNNING);
            }
            throw new ApiException(ApiException.OTHER, 0, "Sonarr connection failed: " + m);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Builds a readable message from Sonarr's error body (validation array or {message, description}). */
    private static String errorMessage(int code, String detail) {
        String base = "Sonarr returned HTTP " + code;
        String d = detail == null ? "" : detail.trim();
        try {
            if (d.startsWith("[")) {
                JSONArray a = new JSONArray(d);
                List<String> msgs = new ArrayList<>();
                for (int i = 0; i < a.length() && msgs.size() < 3; i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String m = o.optString("errorMessage", "").trim();
                    if (!m.isEmpty() && !msgs.contains(m)) msgs.add(m);
                }
                if (!msgs.isEmpty()) return join(msgs, "\n");
            } else if (d.startsWith("{")) {
                JSONObject o = new JSONObject(d);
                String m = o.optString("message", "").trim();
                if (m.isEmpty()) m = o.optString("errorMessage", "").trim();
                if (m.isEmpty()) m = o.optString("title", "").trim();
                String desc = o.optString("description", "").trim();
                if (!m.isEmpty() && !desc.isEmpty() && !desc.equals(m) && desc.length() < 300) m = m + " (" + desc + ")";
                if (!m.isEmpty()) return m;
            }
        } catch (Throwable ignored) {
            // fall through to the generic message
        }
        if (code == 404) return base + ": not found.";
        if (code >= 500) return base + ": internal error. Check Sonarr's logs.";
        return base + ".";
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String readAll(InputStream is, int max) throws Exception {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > max) throw new ApiException(ApiException.OTHER, 0, "Sonarr response is too large.");
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    private JSONArray arr(String path) throws Exception {
        return arr(path, 30000);
    }

    private JSONArray arr(String path, int timeoutMs) throws Exception {
        String r = call("GET", path, null, timeoutMs).trim();
        if (r.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(r);
        } catch (org.json.JSONException e) {
            throw new ApiException(ApiException.OTHER, 0, "Sonarr sent an unexpected response.");
        }
    }

    private JSONObject obj(String path) throws Exception {
        String r = call("GET", path, null, 30000).trim();
        if (r.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(r);
        } catch (org.json.JSONException e) {
            throw new ApiException(ApiException.OTHER, 0, "Sonarr sent an unexpected response.");
        }
    }

    // ------------------------------------------------------------------ images

    /**
     * Downloads and decodes a cover image (local MediaCover path or a remote URL). For local posters the
     * small "-250" variant Sonarr generates is tried first. Returns null on failure.
     */
    public Bitmap loadImage(String url, int maxWidthPx) {
        if (url == null || url.isEmpty()) return null;
        String full = url.startsWith("http") ? url : HOST + (url.startsWith("/") ? "" : "/") + url;
        if (full.startsWith(HOST) && maxWidthPx <= 400) {
            String small = full.replaceFirst("/poster\\.jpg", "/poster-250.jpg");
            if (!small.equals(full)) {
                Bitmap b = fetchBitmap(small, maxWidthPx);
                if (b != null) return b;
            }
        }
        return fetchBitmap(full, maxWidthPx);
    }

    private Bitmap fetchBitmap(String full, int maxWidthPx) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(full).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            if (full.startsWith(HOST)) conn.setRequestProperty("X-Api-Key", key);
            if (conn.getResponseCode() >= 400) return null;
            byte[] data;
            InputStream is = conn.getInputStream();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_IMAGE_BYTES) return null;
                }
                data = bos.toByteArray();
            } finally {
                try { is.close(); } catch (Exception ignored) {}
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= Math.max(1, maxWidthPx)) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ series

    public JSONArray series() throws Exception { return arr("/series", 60000); }

    public JSONObject series(int id) throws Exception { return obj("/series/" + id); }

    public JSONArray lookup(String term) throws Exception { return arr("/series/lookup?term=" + enc(term), 60000); }

    /** PUT /series/{id}: monitored flags and season monitoring (Sonarr updates the season's episodes too). */
    public void putSeries(JSONObject series) throws Exception {
        call("PUT", "/series/" + series.getInt("id"), series.toString(), 30000);
    }

    public void deleteSeries(int id, boolean deleteFiles) throws Exception {
        call("DELETE", "/series/" + id + "?deleteFiles=" + deleteFiles + "&addImportListExclusion=false", null, 120000);
    }

    /**
     * POST /series from a lookup result. monitor (MonitorTypes): all, future, missing, existing, pilot,
     * firstSeason, lastSeason, none.
     */
    public void addSeries(JSONObject lookup, String rootFolder, int qualityProfileId, String monitor,
                          boolean seasonFolder, String seriesType, boolean searchMissing) throws Exception {
        JSONObject body = new JSONObject(lookup.toString());
        body.remove("id");
        body.put("rootFolderPath", rootFolder);
        body.put("qualityProfileId", qualityProfileId);
        body.put("monitored", !"none".equals(monitor));
        body.put("seasonFolder", seasonFolder);
        body.put("seriesType", seriesType);
        if (!body.has("tags")) body.put("tags", new JSONArray());
        if (!body.has("languageProfileId")) {
            try {
                // Sonarr v3 requires a language profile; v4 keeps a single deprecated one (or none).
                JSONArray lp = arr("/languageprofile");
                JSONObject first = lp.length() > 0 ? lp.optJSONObject(0) : null;
                if (first != null) body.put("languageProfileId", first.optInt("id", 1));
            } catch (Throwable ignored) {
                // not available: v4
            }
        }
        JSONObject opts = new JSONObject();
        opts.put("monitor", monitor);
        opts.put("searchForMissingEpisodes", searchMissing);
        opts.put("searchForCutoffUnmetEpisodes", false);
        opts.put("ignoreEpisodesWithFiles", false);
        opts.put("ignoreEpisodesWithoutFiles", false);
        body.put("addOptions", opts);
        call("POST", "/series", body.toString(), 60000);
    }

    // ------------------------------------------------------------------ episodes

    public JSONArray episodes(int seriesId) throws Exception {
        return arr("/episode?seriesId=" + seriesId + "&includeEpisodeFile=true", 60000);
    }

    /** PUT /episode/monitor {episodeIds, monitored} (EpisodesMonitoredResource). */
    public void monitorEpisodes(int[] episodeIds, boolean monitored) throws Exception {
        JSONObject b = new JSONObject();
        JSONArray ids = new JSONArray();
        for (int id : episodeIds) ids.put(id);
        b.put("episodeIds", ids);
        b.put("monitored", monitored);
        call("PUT", "/episode/monitor", b.toString(), 20000);
    }

    public void deleteEpisodeFile(int episodeFileId) throws Exception {
        call("DELETE", "/episodefile/" + episodeFileId, null, 30000);
    }

    // ------------------------------------------------------------------ commands

    public void command(String json) throws Exception { call("POST", "/command", json, 20000); }

    public void searchSeries(int seriesId) throws Exception {
        command("{\"name\":\"SeriesSearch\",\"seriesId\":" + seriesId + "}");
    }

    public void searchSeason(int seriesId, int season) throws Exception {
        command("{\"name\":\"SeasonSearch\",\"seriesId\":" + seriesId + ",\"seasonNumber\":" + season + "}");
    }

    public void searchEpisode(int episodeId) throws Exception {
        command("{\"name\":\"EpisodeSearch\",\"episodeIds\":[" + episodeId + "]}");
    }

    public void searchAllMissing() throws Exception {
        command("{\"name\":\"MissingEpisodeSearch\",\"monitored\":true}");
    }

    /** v4 reads "seriesIds", v3 reads "seriesId"; each ignores the other, so one series is refreshed either way. */
    public void refreshSeries(int seriesId) throws Exception {
        command("{\"name\":\"RefreshSeries\",\"seriesIds\":[" + seriesId + "],\"seriesId\":" + seriesId + "}");
    }

    public void rescanSeries(int seriesId) throws Exception {
        command("{\"name\":\"RescanSeries\",\"seriesId\":" + seriesId + "}");
    }

    public void simpleCommand(String name) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        command(o.toString());
    }

    // ------------------------------------------------------------------ releases (interactive search)

    public JSONArray releasesForEpisode(int episodeId) throws Exception {
        return arr("/release?episodeId=" + episodeId, 180000);
    }

    public JSONArray releasesForSeason(int seriesId, int season) throws Exception {
        return arr("/release?seriesId=" + seriesId + "&seasonNumber=" + season, 180000);
    }

    /**
     * POST /release with the release's guid + indexerId (Sonarr finds the cached search result by them;
     * the cache expires about 30 minutes after the search).
     */
    public void grabRelease(JSONObject release) throws Exception {
        JSONObject b = new JSONObject();
        b.put("guid", release.optString("guid"));
        b.put("indexerId", release.optInt("indexerId"));
        call("POST", "/release", b.toString(), 60000);
    }

    // ------------------------------------------------------------------ queue / history / calendar / wanted

    /** Paging resource: {page, pageSize, totalRecords, records[]}. */
    public JSONObject queue(int page, int pageSize) throws Exception {
        return obj("/queue?page=" + page + "&pageSize=" + pageSize
                + "&includeUnknownSeriesItems=false&includeSeries=true&includeEpisode=true");
    }

    /**
     * DELETE /queue/{id}. skipRedownload=false lets Sonarr search for a replacement after blocklisting
     * (Sonarr v4; ignored by v3).
     */
    public void removeQueueItem(int id, boolean removeFromClient, boolean blocklist, boolean skipRedownload) throws Exception {
        call("DELETE", "/queue/" + id + "?removeFromClient=" + removeFromClient + "&blocklist=" + blocklist
                + "&skipRedownload=" + skipRedownload, null, 30000);
    }

    /** Paging resource: {page, pageSize, totalRecords, records[]}. */
    public JSONObject history(int page, int pageSize) throws Exception {
        return obj("/history?page=" + page + "&pageSize=" + pageSize
                + "&includeSeries=true&includeEpisode=true&sortKey=date&sortDirection=descending");
    }

    /** start / end are ISO-8601 UTC instants, see {@link #isoUtc(long)}. */
    public JSONArray calendar(String startIsoUtc, String endIsoUtc) throws Exception {
        return arr("/calendar?start=" + enc(startIsoUtc) + "&end=" + enc(endIsoUtc)
                + "&unmonitored=false&includeSeries=true&includeEpisodeFile=false");
    }

    /** Paging resource of monitored missing episodes, newest air date first. */
    public JSONObject wantedMissing(int page, int pageSize) throws Exception {
        return obj("/wanted/missing?page=" + page + "&pageSize=" + pageSize
                + "&monitored=true&includeSeries=true&sortKey=episodes.airDateUtc&sortDirection=descending");
    }

    // ------------------------------------------------------------------ system / settings

    public JSONObject status() throws Exception { return obj("/system/status"); }

    public JSONArray health() throws Exception { return arr("/health"); }

    public JSONArray diskSpace() throws Exception { return arr("/diskspace"); }

    public JSONArray rootFolders() throws Exception { return arr("/rootfolder"); }

    public void addRootFolder(String path) throws Exception {
        call("POST", "/rootfolder", new JSONObject().put("path", path).toString(), 30000);
    }

    public void deleteRootFolder(int id) throws Exception { call("DELETE", "/rootfolder/" + id, null, 30000); }

    // ------------------------------------------------------------------ file management (media management / naming / import)

    public JSONObject mediaManagementConfig() throws Exception { return obj("/config/mediamanagement"); }

    public void saveMediaManagementConfig(JSONObject cfg) throws Exception {
        call("PUT", "/config/mediamanagement/" + cfg.optInt("id", 1), cfg.toString(), 30000);
    }

    public JSONObject namingConfig() throws Exception { return obj("/config/naming"); }

    public void saveNamingConfig(JSONObject cfg) throws Exception {
        call("PUT", "/config/naming/" + cfg.optInt("id", 1), cfg.toString(), 30000);
    }

    /** Files Sonarr could import from {@code folder} (GET /manualimport). Scanning a large folder can take a while. */
    public JSONArray manualImportCandidates(String folder) throws Exception {
        String r = call("GET", "/manualimport?folder=" + enc(folder) + "&filterExistingFiles=true", null, 120000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    /** Imports the given candidates; {@code mode} is "move" or "copy". */
    public void manualImport(JSONArray files, String mode) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", "ManualImport");
        o.put("files", files);
        o.put("importMode", mode);
        command(o.toString());
    }

    /** Renames every episode file to the current naming format (the "Organize" action). Returns how many series were queued. */
    /**
     * Points every series stored under {@code oldRoot} to {@code newRoot} without moving files (the files were
     * already moved). Adds {@code newRoot} as a root folder and removes {@code oldRoot} when it was one.
     * Returns how many series were updated.
     */
    public int repointRoot(String oldRoot, String newRoot) throws Exception {
        String prefix = oldRoot.endsWith("/") ? oldRoot : oldRoot + "/";
        JSONArray all = series();
        JSONArray ids = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.optJSONObject(i);
            if (s != null && s.optString("path", "").startsWith(prefix)) ids.put(s.optInt("id"));
        }
        boolean haveNew = false;
        int oldId = -1;
        JSONArray roots = rootFolders();
        for (int i = 0; i < roots.length(); i++) {
            JSONObject r = roots.optJSONObject(i);
            if (r == null) continue;
            String p = trimSlash(r.optString("path", ""));
            if (p.equals(trimSlash(newRoot))) haveNew = true;
            if (p.equals(trimSlash(oldRoot))) oldId = r.optInt("id", -1);
        }
        if (!haveNew && (ids.length() > 0 || oldId >= 0)) addRootFolder(newRoot);
        if (ids.length() > 0) {
            JSONObject body = new JSONObject();
            body.put("seriesIds", ids);
            body.put("rootFolderPath", newRoot);
            body.put("moveFiles", false);
            call("PUT", "/series/editor", body.toString(), 120000);
        }
        if (oldId >= 0) {
            try { deleteRootFolder(oldId); } catch (Throwable ignored) { /* still referenced: harmless */ }
        }
        return ids.length();
    }

    private static String trimSlash(String p) {
        return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
    }

    public int renameAllSeries() throws Exception {
        JSONArray all = series();
        JSONArray ids = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.optJSONObject(i);
            if (s == null) continue;
            JSONObject st = s.optJSONObject("statistics");
            if (st != null && st.optInt("episodeFileCount", 0) > 0) ids.put(s.optInt("id"));
        }
        if (ids.length() > 0) {
            JSONObject o = new JSONObject();
            o.put("name", "RenameSeries");
            o.put("seriesIds", ids);
            command(o.toString());
        }
        return ids.length();
    }

    public JSONArray qualityProfiles() throws Exception { return arr("/qualityprofile"); }

    // ---- preset support: quality profiles, quality definitions and custom formats

    public JSONObject qualityProfileSchema() throws Exception { return obj("/qualityprofile/schema"); }

    public JSONObject addQualityProfile(JSONObject profile) throws Exception {
        String r = call("POST", "/qualityprofile", profile.toString(), 30000).trim();
        return r.isEmpty() ? new JSONObject() : new JSONObject(r);
    }

    public void updateQualityProfile(JSONObject profile) throws Exception {
        call("PUT", "/qualityprofile/" + profile.getInt("id"), profile.toString(), 30000);
    }

    public JSONArray qualityDefinitions() throws Exception { return arr("/qualitydefinition"); }

    public void updateQualityDefinition(JSONObject def) throws Exception {
        call("PUT", "/qualitydefinition/" + def.getInt("id"), def.toString(), 30000);
    }

    /** Custom formats exist in Radarr 4+ and Sonarr 4+; older versions answer 404. */
    public JSONArray customFormats() throws Exception { return arr("/customformat"); }

    public JSONObject addCustomFormat(JSONObject format) throws Exception {
        String r = call("POST", "/customformat", format.toString(), 30000).trim();
        return r.isEmpty() ? new JSONObject() : new JSONObject(r);
    }

    public void updateCustomFormat(JSONObject format) throws Exception {
        call("PUT", "/customformat/" + format.getInt("id"), format.toString(), 30000);
    }

    public JSONArray indexers() throws Exception { return arr("/indexer"); }

    public JSONArray downloadClients() throws Exception { return arr("/downloadclient"); }

    public JSONArray tags() throws Exception { return arr("/tag"); }

    /** kind: indexer | downloadclient */
    public String testAll(String kind) throws Exception {
        return call("POST", "/" + kind + "/testall", null, 120000);
    }
}
