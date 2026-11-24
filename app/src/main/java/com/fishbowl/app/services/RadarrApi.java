package com.fishbowl.app.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin client for the local Radarr REST API (http://127.0.0.1:7878/api/v3, Radarr v3 - v6) used by the Radarr tab.
 * Every method blocks: call it from a background thread.
 *
 * <p>All failures are thrown as {@link ApiException}, which tells "Radarr is not running" (no connection)
 * apart from HTTP errors and carries the server's own validation message when there is one.
 */
public final class RadarrApi {

    public static final int PORT = 7878;
    private static final String HOST = "http://127.0.0.1:" + PORT;
    private static final String BASE = HOST + "/api/v3";
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    /** Error of one Radarr call. {@link #status} is the HTTP status, 0 when Radarr could not be reached. */
    public static final class ApiException extends Exception {
        public final int status;
        public final boolean offline;

        public ApiException(String message, int status, boolean offline) {
            super(message);
            this.status = status;
            this.offline = offline;
        }
    }

    /** True when the error means Radarr is not running / not reachable. */
    public static boolean isOffline(Throwable t) {
        return t instanceof ApiException && ((ApiException) t).offline;
    }

    /** HTTP status of the error, or 0. */
    public static int statusOf(Throwable t) {
        return t instanceof ApiException ? ((ApiException) t).status : 0;
    }

    private final String key;

    private RadarrApi(String key) {
        this.key = key;
    }

    /** Returns a client, or throws: offline when Radarr is not listening, otherwise "no API key yet". */
    public static RadarrApi get(Context ctx) throws ApiException {
        String k = null;
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(ctx.getApplicationContext());
            k = mgr == null ? null : mgr.getServiceApiKey("radarr");
        } catch (Throwable ignored) {
            // treated as "no key" below
        }
        if (k == null || k.isEmpty()) {
            if (!isPortOpen()) throw new ApiException("Radarr is not running", 0, true);
            throw new ApiException("Radarr API key not found yet. Wait for the first start to finish, then retry.", 0, false);
        }
        return new RadarrApi(k);
    }

    /** Quick TCP probe of 127.0.0.1:7878. */
    public static boolean isPortOpen() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 700);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ transport

    private static final class Raw {
        final int code;
        final String body;

        Raw(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Raw exchange(String method, String path, String body, int timeoutMs) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("X-Api-Key", key);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
            }
            try {
                conn.connect();
            } catch (Exception e) {
                throw new ApiException("Radarr is not running", 0, true);
            }
            if (body != null) {
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                } finally {
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Raw(code, readAll(is));
        } catch (ApiException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new ApiException("Radarr did not answer in time. Try again.", 0, false);
        } catch (java.net.ConnectException e) {
            throw new ApiException("Radarr is not running", 0, true);
        } catch (Exception e) {
            throw new ApiException("Connection to Radarr failed: " + e.getClass().getSimpleName(), 0, false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String call(String method, String path, String body, int timeoutMs) throws ApiException {
        Raw r = exchange(method, path, body, timeoutMs);
        if (r.code >= 400) throw httpError(r.code, r.body);
        return r.body;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        Reader rd = new InputStreamReader(is, StandardCharsets.UTF_8);
        try {
            char[] buf = new char[8192];
            int n;
            while ((n = rd.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            rd.close();
        }
        return sb.toString();
    }

    static ApiException httpError(int code, String body) {
        String detail = readableError(body);
        String msg;
        if (code == 401 || code == 403) {
            msg = "Radarr refused the API key (HTTP " + code + ")";
        } else if (detail.isEmpty()) {
            msg = "Radarr returned HTTP " + code;
        } else {
            msg = "Radarr returned HTTP " + code + ": " + detail;
        }
        return new ApiException(msg, code, false);
    }

    /**
     * Turns an *arr error body into one short line: validation failure arrays
     * ([{propertyName, errorMessage}]), provider test results ([{isValid, validationFailures}]),
     * {message} objects and ASP.NET problem details ({title, errors}).
     */
    public static String readableError(String body) {
        try {
            String t = body == null ? "" : body.trim();
            if (t.isEmpty()) return "";
            if (t.startsWith("[")) {
                List<String> parts = new ArrayList<>();
                collectMessages(new JSONArray(t), parts);
                return join(parts, 3);
            }
            if (t.startsWith("{")) {
                JSONObject o = new JSONObject(t);
                String m = o.optString("message", "");
                if (m.isEmpty()) m = o.optString("errorMessage", "");
                List<String> parts = new ArrayList<>();
                JSONObject errs = o.optJSONObject("errors");
                if (errs != null) {
                    java.util.Iterator<String> it = errs.keys();
                    while (it.hasNext()) {
                        JSONArray a = errs.optJSONArray(it.next());
                        for (int i = 0; a != null && i < a.length(); i++) parts.add(a.optString(i));
                    }
                }
                if (m.isEmpty() && !parts.isEmpty()) m = join(parts, 2);
                if (m.isEmpty()) m = o.optString("title", "");
                String desc = o.optString("description", "");
                if (!desc.isEmpty() && !desc.equals(m) && desc.length() < 160) m = m.isEmpty() ? desc : m + " (" + desc + ")";
                return m;
            }
            if (t.startsWith("<")) return "";
            return t.length() > 160 ? t.substring(0, 160) : t;
        } catch (Throwable e) {
            return "";
        }
    }

    private static void collectMessages(JSONArray a, List<String> out) {
        for (int i = 0; i < a.length() && out.size() < 6; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            JSONArray nested = o.optJSONArray("validationFailures");
            if (nested != null) {
                collectMessages(nested, out);
                continue;
            }
            String m = o.optString("errorMessage", "");
            if (m.isEmpty()) m = o.optString("message", "");
            if (!m.isEmpty() && !out.contains(m)) out.add(m);
        }
    }

    private static String join(List<String> parts, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() && i < max; i++) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private JSONArray arr(String path) throws Exception {
        String r = call("GET", path, null, 30000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    private JSONObject obj(String path) throws Exception {
        String r = call("GET", path, null, 30000).trim();
        return r.isEmpty() ? new JSONObject() : new JSONObject(r);
    }

    // ------------------------------------------------------------------ images

    /**
     * Downloads and decodes a poster, sub-sampled to roughly {@code reqWidthPx}. Accepts Radarr's local
     * MediaCover path (served resized as poster-250 / poster-500) or a remote TMDb URL. Returns null on failure.
     */
    public Bitmap loadPoster(String url, int reqWidthPx) {
        if (url == null || url.isEmpty()) return null;
        boolean local = !url.startsWith("http");
        if (local) {
            String path = url.startsWith("/") ? url : "/" + url;
            String sized = path.replace("/poster.jpg", reqWidthPx <= 260 ? "/poster-250.jpg" : "/poster-500.jpg");
            Bitmap b = fetchBitmap(HOST + sized, reqWidthPx, true);
            if (b == null && !sized.equals(path)) b = fetchBitmap(HOST + path, reqWidthPx, true);
            return b;
        }
        String remote = url;
        if (remote.contains("image.tmdb.org/t/p/original/")) {
            remote = remote.replace("/t/p/original/", reqWidthPx <= 200 ? "/t/p/w185/" : "/t/p/w342/");
        }
        return fetchBitmap(remote, reqWidthPx, false);
    }

    private Bitmap fetchBitmap(String full, int reqWidthPx, boolean withKey) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(full).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(15000);
            if (withKey) conn.setRequestProperty("X-Api-Key", key);
            if (conn.getResponseCode() >= 400) return null;
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_IMAGE_BYTES) return null;
                }
            } finally {
                is.close();
            }
            return decodeSampled(bos.toByteArray(), reqWidthPx);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Decodes with inSampleSize so the result is at least {@code reqWidthPx} wide but not much more (RGB_565). */
    public static Bitmap decodeSampled(byte[] data, int reqWidthPx) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= Math.max(1, reqWidthPx)) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    // ------------------------------------------------------------------ movies

    public JSONArray movies() throws Exception {
        String r = call("GET", "/movie", null, 60000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    public JSONObject movie(int id) throws Exception { return obj("/movie/" + id); }

    /** GET /movie/lookup?term= (TMDb search through Radarr). */
    public JSONArray lookup(String term) throws Exception {
        String r = call("GET", "/movie/lookup?term=" + enc(term), null, 45000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    /** PUT /movie/{id}?moveFiles=false with the full movie resource. Returns the updated movie. */
    public JSONObject putMovie(JSONObject movie) throws Exception {
        JSONObject copy = new JSONObject(movie.toString());
        copy.remove("movieFile");
        String r = call("PUT", "/movie/" + copy.getInt("id") + "?moveFiles=false", copy.toString(), 30000).trim();
        return r.startsWith("{") ? new JSONObject(r) : copy;
    }

    /** DELETE /movie/{id}?deleteFiles=&addImportExclusion= (Radarr's name; Sonarr's addImportListExclusion is ignored by Radarr). */
    public void deleteMovie(int id, boolean deleteFiles, boolean addImportExclusion) throws Exception {
        call("DELETE", "/movie/" + id + "?deleteFiles=" + deleteFiles + "&addImportExclusion=" + addImportExclusion, null, 60000);
    }

    /**
     * POST /movie from a lookup result. The body keeps the lookup fields (tmdbId, title, year, titleSlug, images)
     * and sets rootFolderPath, qualityProfileId, monitored, minimumAvailability (announced / inCinemas / released)
     * and addOptions {searchForMovie, monitor}. Returns the created movie.
     */
    public JSONObject addMovie(JSONObject lookup, String rootFolder, int qualityProfileId, boolean monitored,
                               String minimumAvailability, boolean searchForMovie) throws Exception {
        JSONObject body = new JSONObject(lookup.toString());
        body.remove("id");
        body.remove("path");
        body.remove("movieFile");
        if (body.optInt("tmdbId", 0) <= 0) throw new ApiException("This result has no TMDb id and cannot be added.", 0, false);
        if (body.optString("title", "").isEmpty()) body.put("title", lookup.optString("originalTitle", "Movie"));
        body.put("rootFolderPath", rootFolder);
        body.put("qualityProfileId", qualityProfileId);
        body.put("monitored", monitored);
        body.put("minimumAvailability", minimumAvailability);
        if (body.optJSONArray("tags") == null) body.put("tags", new JSONArray());
        JSONObject opts = new JSONObject();
        opts.put("searchForMovie", searchForMovie);
        opts.put("monitor", monitored ? "movieOnly" : "none");
        body.put("addOptions", opts);
        String r = call("POST", "/movie", body.toString(), 60000).trim();
        return r.startsWith("{") ? new JSONObject(r) : new JSONObject();
    }

    public void deleteMovieFile(int movieFileId) throws Exception {
        call("DELETE", "/moviefile/" + movieFileId, null, 30000);
    }

    // ------------------------------------------------------------------ commands

    public void command(JSONObject body) throws Exception { call("POST", "/command", body.toString(), 20000); }

    private static JSONArray ids(int id) {
        JSONArray a = new JSONArray();
        a.put(id);
        return a;
    }

    public void searchMovie(int movieId) throws Exception {
        command(new JSONObject().put("name", "MoviesSearch").put("movieIds", ids(movieId)));
    }

    public void refreshMovie(int movieId) throws Exception {
        command(new JSONObject().put("name", "RefreshMovie").put("movieIds", ids(movieId)));
    }

    public void rescanMovie(int movieId) throws Exception {
        command(new JSONObject().put("name", "RescanMovie").put("movieId", movieId));
    }

    public void searchMissing() throws Exception {
        command(new JSONObject().put("name", "MissingMoviesSearch").put("monitored", true));
    }

    public void searchCutoffUnmet() throws Exception {
        command(new JSONObject().put("name", "CutoffUnmetMoviesSearch").put("monitored", true));
    }

    /** Name-only commands: RssSync, RefreshMonitoredDownloads, Backup, RefreshMovie (all), RescanMovie (all). */
    public void simpleCommand(String name) throws Exception {
        command(new JSONObject().put("name", name));
    }

    // ------------------------------------------------------------------ releases (interactive search)

    /** GET /release?movieId= : searches every indexer, can take a minute or more. */
    public JSONArray releasesForMovie(int movieId) throws Exception {
        String r = call("GET", "/release?movieId=" + movieId, null, 150000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    /** POST /release with {guid, indexerId}; Radarr looks the release up in its search cache by both. */
    public void grabRelease(JSONObject release) throws Exception {
        JSONObject b = new JSONObject();
        b.put("guid", release.optString("guid"));
        b.put("indexerId", release.optInt("indexerId"));
        call("POST", "/release", b.toString(), 45000);
    }

    // ------------------------------------------------------------------ queue / history / calendar / wanted

    /** Paging object {page, pageSize, totalRecords, records[]}; records carry "movie" (includeMovie=true). */
    public JSONObject queue(int page, int pageSize) throws Exception {
        return obj("/queue?page=" + page + "&pageSize=" + pageSize
                + "&includeMovie=true&includeUnknownMovieItems=true");
    }

    public void removeQueueItem(int id, boolean removeFromClient, boolean blocklist) throws Exception {
        call("DELETE", "/queue/" + id + "?removeFromClient=" + removeFromClient + "&blocklist=" + blocklist
                + "&skipRedownload=false", null, 30000);
    }

    public JSONObject history(int page, int pageSize) throws Exception {
        return obj("/history?page=" + page + "&pageSize=" + pageSize
                + "&sortKey=date&sortDirection=descending&includeMovie=true");
    }

    /** start / end as yyyy-MM-dd. */
    public JSONArray calendar(String startDate, String endDate) throws Exception {
        return arr("/calendar?start=" + enc(startDate) + "&end=" + enc(endDate) + "&unmonitored=false");
    }

    // Wanted sort keys are not documented and changed when Radarr moved metadata into MovieMetadata (v4+).
    // Try the current key first, then let the server use its default order; remember what worked.
    private static final String[] SORT_KEYS = {"movieMetadata.sortTitle", ""};
    private static volatile String workingSortKey = null;

    private JSONObject wanted(String kind, int page, int pageSize) throws Exception {
        String base = "/wanted/" + kind + "?page=" + page + "&pageSize=" + pageSize + "&monitored=true";
        String known = workingSortKey;
        if (known != null) return obj(base + sortQuery(known));
        ApiException last = null;
        for (String k : SORT_KEYS) {
            try {
                JSONObject o = obj(base + sortQuery(k));
                workingSortKey = k;
                return o;
            } catch (ApiException e) {
                // Retry only for "bad request / server error" answers; offline, auth and 404 are final.
                if (e.offline || e.status == 0 || e.status == 401 || e.status == 403 || e.status == 404) throw e;
                last = e;
            }
        }
        throw last != null ? last : new ApiException("Radarr wanted list failed", 0, false);
    }

    private static String sortQuery(String k) {
        return k.isEmpty() ? "" : "&sortKey=" + enc(k) + "&sortDirection=ascending";
    }

    public JSONObject wantedMissing(int page, int pageSize) throws Exception { return wanted("missing", page, pageSize); }

    public JSONObject wantedCutoff(int page, int pageSize) throws Exception { return wanted("cutoff", page, pageSize); }

    /**
     * Fallback for Radarr builds without /wanted: builds the same paging object from /movie.
     * Missing = monitored, available, no file. Cutoff unmet = monitored with a file whose qualityCutoffNotMet is true.
     */
    public JSONObject wantedFromLibrary(boolean cutoff, int page, int pageSize) throws Exception {
        JSONArray all = movies();
        List<JSONObject> hits = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null || !m.optBoolean("monitored")) continue;
            if (cutoff) {
                JSONObject f = m.optJSONObject("movieFile");
                if (m.optBoolean("hasFile") && f != null && f.optBoolean("qualityCutoffNotMet")) hits.add(m);
            } else if (!m.optBoolean("hasFile") && m.optBoolean("isAvailable", true)) {
                hits.add(m);
            }
        }
        java.util.Collections.sort(hits, (a, b) -> a.optString("sortTitle", a.optString("title"))
                .compareToIgnoreCase(b.optString("sortTitle", b.optString("title"))));
        JSONArray recs = new JSONArray();
        int from = Math.max(0, (page - 1) * pageSize);
        for (int i = from; i < hits.size() && i < from + pageSize; i++) recs.put(hits.get(i));
        return new JSONObject().put("page", page).put("pageSize", pageSize)
                .put("totalRecords", hits.size()).put("records", recs);
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

    /** Files Radarr could import from {@code folder} (GET /manualimport). Scanning a large folder can take a while. */
    public JSONArray manualImportCandidates(String folder) throws Exception {
        String r = call("GET", "/manualimport?folder=" + enc(folder) + "&filterExistingFiles=true", null, 120000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    /** Imports the given candidates; {@code mode} is "move" or "copy". */
    public void manualImport(JSONArray files, String mode) throws Exception {
        command(new JSONObject().put("name", "ManualImport").put("files", files).put("importMode", mode));
    }

    /** Renames every movie file to the current naming format (the "Organize" action). Returns how many movies were queued. */
    public int renameAllMovies() throws Exception {
        JSONArray all = movies();
        JSONArray ids = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m != null && m.optBoolean("hasFile", false)) ids.put(m.optInt("id"));
        }
        if (ids.length() > 0) command(new JSONObject().put("name", "RenameMovie").put("movieIds", ids));
        return ids.length();
    }

    public JSONArray qualityProfiles() throws Exception { return arr("/qualityprofile"); }

    public JSONArray indexers() throws Exception { return arr("/indexer"); }

    public JSONArray downloadClients() throws Exception { return arr("/downloadclient"); }

    public JSONArray tags() throws Exception { return arr("/tag"); }

    /**
     * POST /{kind}/testall (kind: indexer | downloadclient). Radarr answers 200, or 400 when at least one
     * provider failed; both carry [{id, isValid, validationFailures[]}], which is returned either way.
     */
    public JSONArray testAll(String kind) throws Exception {
        Raw r = exchange("POST", "/" + kind + "/testall", null, 180000);
        String b = r.body == null ? "" : r.body.trim();
        if (b.startsWith("[")) return new JSONArray(b);
        if (r.code >= 400) throw httpError(r.code, r.body);
        return new JSONArray();
    }

    /** Null-safe string field: JSON null and missing keys both become "". */
    public static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        return o.optString(k, "");
    }
}
