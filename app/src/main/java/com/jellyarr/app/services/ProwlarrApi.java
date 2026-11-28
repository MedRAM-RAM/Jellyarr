package com.jellyarr.app.services;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

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

/**
 * Thin client for the local Prowlarr v1 REST API (http://127.0.0.1:9696/api/v1) used by the Prowlarr tab.
 * Every method blocks: call it from a background thread. Failures are thrown as {@link ApiException}.
 */
public final class ProwlarrApi {

    public static final int PORT = 9696;
    private static final String HOST = "http://127.0.0.1:" + PORT;
    private static final String BASE = HOST + "/api/v1";

    /** Error of one Prowlarr call. {@link #status} is the HTTP status, 0 when Prowlarr could not be reached. */
    public static final class ApiException extends Exception {
        public final int status;
        public final boolean offline;
        /** Raw error body (validation failures), for callers that want to offer "save anyway". */
        public final String body;

        public ApiException(String message, int status, boolean offline, String body) {
            super(message);
            this.status = status;
            this.offline = offline;
            this.body = body;
        }
    }

    public static boolean isOffline(Throwable t) {
        return t instanceof ApiException && ((ApiException) t).offline;
    }

    /** True for an HTTP 400 validation answer (the indexer / app test failed). */
    public static boolean isValidationError(Throwable t) {
        return t instanceof ApiException && ((ApiException) t).status == 400;
    }

    private final String key;

    private ProwlarrApi(String key) {
        this.key = key;
    }

    /** Returns a client, or throws: offline when Prowlarr is not listening, otherwise "no API key yet". */
    public static ProwlarrApi get(Context ctx) throws ApiException {
        String k = null;
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(ctx.getApplicationContext());
            k = mgr == null ? null : mgr.getServiceApiKey("prowlarr");
        } catch (Throwable ignored) {
            // treated as "no key" below
        }
        if (k == null || k.isEmpty()) {
            if (!isPortOpen()) throw new ApiException("Prowlarr is not running", 0, true, null);
            throw new ApiException("Prowlarr API key not found yet. Wait for the first start to finish, then retry.", 0, false, null);
        }
        return new ProwlarrApi(k);
    }

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
                throw new ApiException("Prowlarr is not running", 0, true, null);
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
            throw new ApiException("Prowlarr did not answer in time. Try again.", 0, false, null);
        } catch (java.net.ConnectException e) {
            throw new ApiException("Prowlarr is not running", 0, true, null);
        } catch (Exception e) {
            throw new ApiException("Connection to Prowlarr failed: " + e.getClass().getSimpleName(), 0, false, null);
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

    private static ApiException httpError(int code, String body) {
        String detail = RadarrApi.readableError(body);
        String msg;
        if (code == 401 || code == 403) {
            msg = "Prowlarr refused the API key (HTTP " + code + ")";
        } else if (detail.isEmpty()) {
            msg = "Prowlarr returned HTTP " + code;
        } else {
            msg = "Prowlarr returned HTTP " + code + ": " + detail;
        }
        return new ApiException(msg, code, false, body);
    }

    private JSONArray arr(String path) throws Exception {
        String r = call("GET", path, null, 30000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    private JSONObject obj(String path) throws Exception {
        String r = call("GET", path, null, 30000).trim();
        return r.isEmpty() ? new JSONObject() : new JSONObject(r);
    }

    // ------------------------------------------------------------------ indexers

    public JSONArray indexers() throws Exception { return arr("/indexer"); }

    public JSONObject indexer(int id) throws Exception { return obj("/indexer/" + id); }

    /** All indexer definitions that can be added (each carries its configurable "fields"). Large answer. */
    public JSONArray indexerSchema() throws Exception {
        String r = call("GET", "/indexer/schema", null, 90000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    /**
     * POST /indexer. Without forceSave Prowlarr tests the indexer first and answers 400 with the validation
     * failures when the test fails; with forceSave it saves anyway (like "Save anyway" in the web UI).
     */
    public void addIndexer(JSONObject indexer, boolean forceSave) throws Exception {
        call("POST", "/indexer" + (forceSave ? "?forceSave=true" : ""), indexer.toString(), 90000);
    }

    public void updateIndexer(JSONObject indexer, boolean forceSave) throws Exception {
        call("PUT", "/indexer/" + indexer.getInt("id") + (forceSave ? "?forceSave=true" : ""), indexer.toString(), 90000);
    }

    public void deleteIndexer(int id) throws Exception { call("DELETE", "/indexer/" + id, null, 30000); }

    public void testIndexer(JSONObject indexer) throws Exception {
        call("POST", "/indexer/test", indexer.toString(), 90000);
    }

    /**
     * POST /indexer/testall. Answers 200, or 400 when any indexer failed; both carry
     * [{id, isValid, validationFailures[]}], which is returned either way.
     */
    public JSONArray testAllIndexers() throws Exception {
        Raw r = exchange("POST", "/indexer/testall", null, 240000);
        String b = r.body == null ? "" : r.body.trim();
        if (b.startsWith("[")) return new JSONArray(b);
        if (r.code >= 400) throw httpError(r.code, r.body);
        return new JSONArray();
    }

    public JSONArray indexerCategories() throws Exception { return arr("/indexer/categories"); }

    public JSONArray appProfiles() throws Exception { return arr("/appprofile"); }

    /** {indexers: [{indexerId, indexerName, averageResponseTime, numberOfQueries, numberOfGrabs, ...}], userAgents, hosts}. */
    public JSONObject indexerStats() throws Exception { return obj("/indexerstats"); }

    /** [{indexerId, disabledTill, mostRecentFailure, initialFailure}] for indexers in failure back-off. */
    public JSONArray indexerStatus() throws Exception { return arr("/indexerstatus"); }

    // ------------------------------------------------------------------ search

    /**
     * GET /search. type: search, tvsearch, movie, music, book. indexerIds / categories may be null or empty:
     * no indexerIds means every enabled indexer.
     */
    public JSONArray search(String query, String type, int[] indexerIds, int[] categories) throws Exception {
        StringBuilder sb = new StringBuilder("/search?query=").append(enc(query));
        sb.append("&type=").append(enc(type == null || type.isEmpty() ? "search" : type));
        sb.append("&limit=100&offset=0");
        if (indexerIds != null) {
            for (int i : indexerIds) sb.append("&indexerIds=").append(i);
        }
        if (categories != null) {
            for (int c : categories) sb.append("&categories=").append(c);
        }
        String r = call("GET", sb.toString(), null, 150000).trim();
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    /** POST /search with the release {guid, indexerId}: Prowlarr sends it to its own download client. */
    public void grab(JSONObject release) throws Exception {
        JSONObject b = new JSONObject();
        b.put("guid", release.optString("guid"));
        b.put("indexerId", release.optInt("indexerId"));
        call("POST", "/search", b.toString(), 45000);
    }

    // ------------------------------------------------------------------ applications / clients / tags

    public JSONArray applications() throws Exception { return arr("/applications"); }

    public void testApplication(JSONObject app) throws Exception {
        call("POST", "/applications/test", app.toString(), 60000);
    }

    public void deleteApplication(int id) throws Exception { call("DELETE", "/applications/" + id, null, 30000); }

    public JSONArray downloadClients() throws Exception { return arr("/downloadclient"); }

    public JSONArray tags() throws Exception { return arr("/tag"); }

    public JSONObject createTag(String label) throws Exception {
        String r = call("POST", "/tag", new JSONObject().put("label", label).toString(), 30000).trim();
        return r.isEmpty() ? new JSONObject() : new JSONObject(r);
    }

    // ------------------------------------------------------------------ history

    /** Paging object {page, pageSize, totalRecords, records: [{indexerId, date, successful, eventType, data{}}]}. */
    public JSONObject history(int page, int pageSize) throws Exception {
        return obj("/history?page=" + page + "&pageSize=" + pageSize + "&sortKey=date&sortDirection=descending");
    }

    // ------------------------------------------------------------------ system / commands

    public JSONObject status() throws Exception { return obj("/system/status"); }

    public JSONArray health() throws Exception { return arr("/health"); }

    public JSONArray tasks() throws Exception { return arr("/system/task"); }

    public void command(JSONObject body) throws Exception { call("POST", "/command", body.toString(), 20000); }

    /** Queues a command such as ApplicationIndexerSync, CheckHealth, Backup or Housekeeping. */
    public void simpleCommand(String name) throws Exception {
        command(new JSONObject().put("name", name));
    }
}
