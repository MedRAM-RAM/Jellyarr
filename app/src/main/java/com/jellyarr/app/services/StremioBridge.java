package com.jellyarr.app.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.jellyarr.app.NetworkUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jellyarr Bridge: a Stremio addon served from inside the app, so AIOStreams (and Stremio itself)
 * can use the local stack as one source.
 *
 * <ul>
 *   <li><b>Catalogs / meta</b>: the Jellyfin movie and TV libraries. Items with an IMDb id are listed
 *       under that id, so AIOStreams shows the local file next to every other source of the title.</li>
 *   <li><b>Streams, local</b>: direct-play URLs from Jellyfin, with Jellyfin's external subtitles.</li>
 *   <li><b>Streams, torrents</b>: Prowlarr search results played through TorrServer. Each result is a
 *       {@code /play/<token>} URL here; opening it hands the torrent to TorrServer, picks the right file
 *       (the episode inside a season pack, or the largest video) and redirects the player to
 *       TorrServer's HTTP stream. Prowlarr links and API keys never leave the phone.</li>
 * </ul>
 *
 * Every route sits under a random key ({@code /<key>/manifest.json}), because the bridge listens on
 * the local network and hands out Jellyfin-authenticated URLs.
 */
public final class StremioBridge {

    private static final String TAG = "StremioBridge";
    public static final int PORT = 7070;
    public static final int TORRSERVER_PORT = 8090;
    private static final String PREFS = "stremio_bridge";
    private static final String JF = "http://127.0.0.1:8096";
    private static final String PROWLARR = "http://127.0.0.1:9696";
    private static final String TORRSERVER = "http://127.0.0.1:" + TORRSERVER_PORT;
    private static final String CINEMETA = "https://v3-cinemeta.strem.io";
    private static final int PAGE = 50;
    private static final long INDEX_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_TOKENS = 3000;

    private static final StremioBridge INSTANCE = new StremioBridge();

    public static StremioBridge getInstance() { return INSTANCE; }

    private volatile Context app;
    private volatile ServerSocket server;
    private volatile ExecutorService pool;
    private volatile String lastError;

    // Jellyfin caches
    private volatile String jfUserId;
    private volatile long indexBuiltAt;
    private volatile Map<String, List<String>> imdbToItems = Collections.emptyMap();

    /** Play tokens: random id -> torrent candidate. Bounded LRU. */
    private final Map<String, TorrentRef> tokens = Collections.synchronizedMap(
            new LinkedHashMap<String, TorrentRef>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TorrentRef> e) {
                    return size() > MAX_TOKENS;
                }
            });

    private final Map<String, JSONObject> cinemetaCache = Collections.synchronizedMap(
            new LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JSONObject> e) {
                    return size() > 500;
                }
            });

    private static final class TorrentRef {
        String magnet;
        String infoHash;
        String downloadUrl;
        String title;
        int season = -1;
        int episode = -1;
        String resolvedHash; // after first play
        int resolvedIndex = -1;
        String resolvedName;
    }

    private StremioBridge() {}

    // ------------------------------------------------------------------ prefs & lifecycle

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) { return prefs(c).getBoolean("enabled", false); }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
        if (on) getInstance().start(c); else getInstance().stop();
    }

    public static boolean isTorrentsEnabled(Context c) { return prefs(c).getBoolean("torrents", true); }

    public static void setTorrentsEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("torrents", on).apply(); }

    public static int getMaxTorrents(Context c) { return prefs(c).getInt("max_torrents", 15); }

    public static void setMaxTorrents(Context c, int n) {
        prefs(c).edit().putInt("max_torrents", Math.max(1, Math.min(50, n))).apply();
    }

    public static synchronized String getKey(Context c) {
        SharedPreferences sp = prefs(c);
        String k = sp.getString("key", null);
        if (k == null || k.length() < 16) {
            k = randomHex(12);
            sp.edit().putString("key", k).apply();
        }
        return k;
    }

    public static void regenerateKey(Context c) {
        prefs(c).edit().putString("key", randomHex(12)).apply();
        getInstance().tokens.clear();
    }

    /** The manifest URL to paste into AIOStreams / Stremio (LAN address when there is one). */
    public static String getManifestUrl(Context c) {
        String ip = NetworkUtils.getRawLanIp();
        if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
        return "http://" + ip + ":" + PORT + "/" + getKey(c) + "/manifest.json";
    }

    public static String getLocalManifestUrl(Context c) {
        return "http://127.0.0.1:" + PORT + "/" + getKey(c) + "/manifest.json";
    }

    /** Starts the server when the user enabled it. Safe to call repeatedly. */
    public void startIfEnabled(Context c) {
        if (c != null && isEnabled(c)) start(c);
    }

    public synchronized void start(Context c) {
        if (c != null) app = c.getApplicationContext();
        if (server != null && !server.isClosed()) return;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT), 32);
            server = ss;
            pool = Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "Jellyarr-Bridge");
                t.setDaemon(true);
                return t;
            });
            Thread acceptor = new Thread(() -> acceptLoop(ss), "Jellyarr-Bridge-Accept");
            acceptor.setDaemon(true);
            acceptor.start();
            lastError = null;
            Log.i(TAG, "Bridge listening on :" + PORT);
        } catch (Throwable t) {
            lastError = t.getMessage();
            Log.e(TAG, "Bridge failed to start: " + t.getMessage());
            server = null;
        }
    }

    public synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (Throwable ignored) {}
        }
        ExecutorService p = pool;
        pool = null;
        if (p != null) p.shutdownNow();
    }

    public boolean isRunning() {
        ServerSocket ss = server;
        return ss != null && !ss.isClosed();
    }

    public String getLastError() { return lastError; }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                ExecutorService p = pool;
                if (p == null) { s.close(); break; }
                p.execute(() -> handle(s));
            } catch (Throwable t) {
                if (!ss.isClosed()) Log.w(TAG, "accept: " + t.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private static final class Req {
        String method;
        String path;
        String host;
    }

    private static final class Resp {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];
        String location;
        int maxAge = 0;

        static Resp json(Object o, int maxAge) {
            Resp r = new Resp();
            r.body = o.toString().getBytes(StandardCharsets.UTF_8);
            r.maxAge = maxAge;
            return r;
        }

        static Resp text(int status, String msg) {
            Resp r = new Resp();
            r.status = status;
            r.type = "text/plain; charset=utf-8";
            r.body = msg.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        static Resp redirect(String to) {
            Resp r = new Resp();
            r.status = 302;
            r.location = to;
            r.type = "text/plain";
            return r;
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            Req req = readRequest(in);
            Resp resp;
            if (req == null) return;
            if ("OPTIONS".equals(req.method)) {
                resp = Resp.text(204, "");
            } else if (!"GET".equals(req.method) && !"HEAD".equals(req.method)) {
                resp = Resp.text(405, "Method not allowed");
            } else {
                try {
                    resp = route(req);
                } catch (Throwable t) {
                    Log.w(TAG, "route " + req.path + ": " + t);
                    resp = Resp.text(500, "Bridge error: " + t.getMessage());
                }
            }
            write(sock.getOutputStream(), resp, "HEAD".equals(req.method));
        } catch (Throwable t) {
            Log.d(TAG, "connection: " + t.getMessage());
        }
    }

    private static Req readRequest(InputStream in) throws Exception {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        r.path = parts[1];
        String h;
        int n = 0;
        while ((h = readLine(in)) != null && !h.isEmpty() && n++ < 100) {
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("host")) r.host = h.substring(c + 1).trim();
        }
        return r;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new Exception("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void write(OutputStream out, Resp r, boolean head) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        sb.append("Content-Type: ").append(r.type).append("\r\n");
        sb.append("Content-Length: ").append(r.body.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        sb.append("Cache-Control: ").append(r.maxAge > 0 ? "max-age=" + r.maxAge : "no-store").append("\r\n");
        if (r.location != null) sb.append("Location: ").append(r.location).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) out.write(r.body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 302: return "Found";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    // ------------------------------------------------------------------ routing

    private Resp route(Req req) throws Exception {
        Context c = app;
        if (c == null) return Resp.text(503, "Not ready");
        String path = req.path;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/health")) return Resp.text(200, "ok");

        String key = getKey(c);
        String prefix = "/" + key + "/";
        if (!path.startsWith(prefix)) return Resp.text(404, "Not found");
        String rest = path.substring(prefix.length());
        String host = publicHost(req.host);
        String self = "http://" + host + ":" + PORT + "/" + key;

        if (rest.equals("manifest.json")) return Resp.json(manifest(c), 3600);
        if (rest.isEmpty() || rest.equals("configure")) return Resp.text(200, "Jellyarr Bridge: add " + self + "/manifest.json to AIOStreams or Stremio.");

        String[] seg = rest.split("/");
        if (seg.length >= 2 && seg[0].equals("play")) return play(c, seg[1], host);
        if (seg.length < 3) return Resp.text(404, "Not found");
        String resource = seg[0];
        String type = seg[1];
        String id = dec(stripJson(seg[2]));
        String extra = seg.length >= 4 ? dec(stripJson(seg[3])) : "";

        switch (resource) {
            case "catalog": return Resp.json(catalog(c, type, id, extra, host), 60);
            case "meta": return Resp.json(meta(c, type, id, host), 60);
            case "stream": return Resp.json(streams(c, type, id, host, self), 0);
            default: return Resp.text(404, "Not found");
        }
    }

    private static String stripJson(String s) {
        return s.endsWith(".json") ? s.substring(0, s.length() - 5) : s;
    }

    /** Host the client used to reach us; loopback becomes the LAN address so other devices can play. */
    private static String publicHost(String hostHeader) {
        String h = hostHeader == null ? "" : hostHeader.trim();
        if (h.startsWith("[")) {
            int e = h.indexOf(']');
            h = e > 0 ? h.substring(0, e + 1) : h;
        } else {
            int colon = h.lastIndexOf(':');
            if (colon > 0) h = h.substring(0, colon);
        }
        if (h.isEmpty() || h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]")) {
            String lan = NetworkUtils.getRawLanIp();
            return lan != null && !lan.isEmpty() ? lan : "127.0.0.1";
        }
        return h;
    }

    // ------------------------------------------------------------------ manifest

    private JSONObject manifest(Context c) throws Exception {
        JSONObject m = new JSONObject();
        m.put("id", "app.jellyarr.bridge");
        m.put("version", "1.0.0");
        m.put("name", "Jellyarr");
        m.put("description", "Your Jellyfin library on this phone, plus Prowlarr torrents streamed through TorrServer.");
        m.put("logo", "https://raw.githubusercontent.com/MedRAM-RAM/Jellyarr/main/art/jellyarr_logo_v11-1_dark.svg");
        m.put("types", new JSONArray().put("movie").put("series"));
        m.put("idPrefixes", new JSONArray().put("tt").put("jellyarr:"));

        JSONArray resources = new JSONArray();
        resources.put("catalog");
        resources.put(new JSONObject().put("name", "meta")
                .put("types", new JSONArray().put("movie").put("series"))
                .put("idPrefixes", new JSONArray().put("jellyarr:")));
        resources.put(new JSONObject().put("name", "stream")
                .put("types", new JSONArray().put("movie").put("series"))
                .put("idPrefixes", new JSONArray().put("tt").put("jellyarr:")));
        m.put("resources", resources);

        JSONArray catalogs = new JSONArray();
        catalogs.put(catalogDef("movie", "jellyarr-movies", "Jellyfin Movies"));
        catalogs.put(catalogDef("series", "jellyarr-series", "Jellyfin Series"));
        m.put("catalogs", catalogs);
        m.put("behaviorHints", new JSONObject().put("configurable", false).put("configurationRequired", false));
        return m;
    }

    private static JSONObject catalogDef(String type, String id, String name) throws Exception {
        JSONArray extra = new JSONArray()
                .put(new JSONObject().put("name", "search").put("isRequired", false))
                .put(new JSONObject().put("name", "skip").put("isRequired", false));
        return new JSONObject().put("type", type).put("id", id).put("name", name).put("extra", extra);
    }

    // ------------------------------------------------------------------ Jellyfin

    private String jfToken(Context c) throws Exception {
        return JellyfinLibraryClient.getToken(c);
    }

    private String jfGet(Context c, String pathAndQuery) throws Exception {
        String token = jfToken(c);
        HttpURLConnection conn = (HttpURLConnection) new URL(JF + pathAndQuery).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Emby-Token", token);
        conn.setRequestProperty("Authorization", "MediaBrowser Client=\"Jellyarr Bridge\", Device=\"Android\", DeviceId=\"jellyarr-bridge\", Version=\"1.0\", Token=\"" + token + "\"");
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("Jellyfin HTTP " + code + " for " + pathAndQuery);
        try (InputStream in = conn.getInputStream()) {
            return new String(readAll(in, 32 * 1024 * 1024), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private String userId(Context c) throws Exception {
        String u = jfUserId;
        if (u != null) return u;
        JSONArray users = new JSONArray(jfGet(c, "/Users"));
        String first = null;
        for (int i = 0; i < users.length(); i++) {
            JSONObject o = users.getJSONObject(i);
            if (first == null) first = o.optString("Id", null);
            JSONObject pol = o.optJSONObject("Policy");
            if (pol != null && pol.optBoolean("IsAdministrator")) { first = o.optString("Id"); break; }
        }
        if (first == null) throw new Exception("No Jellyfin user");
        jfUserId = first;
        return first;
    }

    /** imdb id -> Jellyfin item ids (movies and series), rebuilt every few minutes. */
    private Map<String, List<String>> imdbIndex(Context c) throws Exception {
        long now = System.currentTimeMillis();
        if (now - indexBuiltAt < INDEX_TTL_MS) return imdbToItems;
        synchronized (this) {
            if (now - indexBuiltAt < INDEX_TTL_MS) return imdbToItems;
            String uid = userId(c);
            JSONObject res = new JSONObject(jfGet(c, "/Users/" + uid + "/Items?IncludeItemTypes=Movie,Series&Recursive=true"
                    + "&Fields=ProviderIds&EnableImages=false&EnableUserData=false"));
            JSONArray items = res.optJSONArray("Items");
            Map<String, List<String>> map = new HashMap<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    String imdb = imdbOf(it);
                    if (imdb == null) continue;
                    List<String> l = map.get(imdb);
                    if (l == null) { l = new ArrayList<>(); map.put(imdb, l); }
                    l.add(it.optString("Id"));
                }
            }
            imdbToItems = map;
            indexBuiltAt = now;
            return map;
        }
    }

    private static String imdbOf(JSONObject item) {
        JSONObject p = item.optJSONObject("ProviderIds");
        if (p == null) return null;
        String v = p.optString("Imdb", p.optString("IMDB", ""));
        return v.startsWith("tt") ? v : null;
    }

    private static String jfImage(String host, String itemId, String kind, int maxH) {
        return "http://" + host + ":8096/Items/" + itemId + "/Images/" + kind + "?maxHeight=" + maxH + "&quality=90";
    }

    private JSONObject catalog(Context c, String type, String id, String extra, String host) throws Exception {
        JSONArray metas = new JSONArray();
        String jfType;
        if ("jellyarr-movies".equals(id) && "movie".equals(type)) jfType = "Movie";
        else if ("jellyarr-series".equals(id) && "series".equals(type)) jfType = "Series";
        else return new JSONObject().put("metas", metas);

        Map<String, String> ex = parseExtra(extra);
        int skip = 0;
        try { skip = Integer.parseInt(ex.getOrDefault("skip", "0")); } catch (Throwable ignored) {}
        String search = ex.get("search");

        StringBuilder q = new StringBuilder("/Users/").append(userId(c)).append("/Items?IncludeItemTypes=").append(jfType)
                .append("&Recursive=true&Fields=ProviderIds,Overview,Genres,ProductionYear&EnableUserData=false")
                .append("&StartIndex=").append(skip).append("&Limit=").append(PAGE);
        if (search != null && !search.isEmpty()) {
            q.append("&SearchTerm=").append(enc(search)).append("&SortBy=SortName");
        } else {
            q.append("&SortBy=DateCreated,SortName&SortOrder=Descending");
        }
        JSONObject res = new JSONObject(jfGet(c, q.toString()));
        JSONArray items = res.optJSONArray("Items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) metas.put(preview(items.getJSONObject(i), type, host));
        }
        return new JSONObject().put("metas", metas);
    }

    private static Map<String, String> parseExtra(String extra) {
        Map<String, String> m = new HashMap<>();
        if (extra == null || extra.isEmpty()) return m;
        for (String part : extra.split("&")) {
            int e = part.indexOf('=');
            if (e > 0) m.put(part.substring(0, e), part.substring(e + 1));
        }
        return m;
    }

    private JSONObject preview(JSONObject it, String type, String host) throws Exception {
        String itemId = it.optString("Id");
        String imdb = imdbOf(it);
        JSONObject m = new JSONObject();
        m.put("id", imdb != null ? imdb : "jellyarr:" + itemId);
        m.put("type", type);
        m.put("name", it.optString("Name"));
        m.put("poster", jfImage(host, itemId, "Primary", 450));
        m.put("background", jfImage(host, itemId, "Backdrop", 720));
        int year = it.optInt("ProductionYear", 0);
        if (year > 0) m.put("releaseInfo", String.valueOf(year));
        String ov = it.optString("Overview", "");
        if (!ov.isEmpty()) m.put("description", ov);
        JSONArray g = it.optJSONArray("Genres");
        if (g != null) m.put("genres", g);
        return m;
    }

    private JSONObject meta(Context c, String type, String id, String host) throws Exception {
        if (!id.startsWith("jellyarr:")) return new JSONObject().put("meta", JSONObject.NULL);
        String itemId = id.substring("jellyarr:".length());
        String uid = userId(c);
        JSONObject it = new JSONObject(jfGet(c, "/Users/" + uid + "/Items/" + enc(itemId)));
        JSONObject m = preview(it, type, host);
        m.put("id", id);
        if ("Series".equals(it.optString("Type"))) {
            JSONObject eps = new JSONObject(jfGet(c, "/Shows/" + enc(itemId) + "/Episodes?userId=" + uid + "&Fields=Overview,PremiereDate"));
            JSONArray arr = eps.optJSONArray("Items");
            JSONArray videos = new JSONArray();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.getJSONObject(i);
                    JSONObject v = new JSONObject();
                    v.put("id", "jellyarr:" + e.optString("Id"));
                    v.put("title", e.optString("Name"));
                    v.put("season", e.optInt("ParentIndexNumber", 0));
                    v.put("episode", e.optInt("IndexNumber", 0));
                    String rel = e.optString("PremiereDate", "");
                    v.put("released", rel.isEmpty() ? "1970-01-01T00:00:00.000Z" : rel);
                    v.put("thumbnail", jfImage(host, e.optString("Id"), "Primary", 300));
                    String ov = e.optString("Overview", "");
                    if (!ov.isEmpty()) v.put("overview", ov);
                    videos.put(v);
                }
            }
            m.put("videos", videos);
        }
        return new JSONObject().put("meta", m);
    }

    // ------------------------------------------------------------------ streams

    private JSONObject streams(Context c, String type, String id, String host, String self) throws Exception {
        JSONArray out = new JSONArray();
        if (id.startsWith("jellyarr:")) {
            safeAddLocal(c, out, id.substring("jellyarr:".length()), host);
            return new JSONObject().put("streams", out);
        }
        String[] p = id.split(":");
        String imdb = p[0];
        int season = p.length >= 3 ? parseInt(p[1]) : -1;
        int episode = p.length >= 3 ? parseInt(p[2]) : -1;

        // Local files first
        try {
            List<String> ids = imdbIndex(c).get(imdb);
            if (ids != null) {
                for (String itemId : ids) {
                    if ("series".equals(type) && season >= 0) {
                        String ep = findEpisode(c, itemId, season, episode);
                        if (ep != null) safeAddLocal(c, out, ep, host);
                    } else {
                        safeAddLocal(c, out, itemId, host);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "local streams: " + t.getMessage());
        }

        // Torrents through Prowlarr + TorrServer
        if (isTorrentsEnabled(c)) {
            try {
                addTorrents(c, out, type, imdb, season, episode, host, self);
            } catch (Throwable t) {
                Log.w(TAG, "torrent streams: " + t.getMessage());
            }
        }
        return new JSONObject().put("streams", out);
    }

    private String findEpisode(Context c, String seriesId, int season, int episode) throws Exception {
        JSONObject eps = new JSONObject(jfGet(c, "/Shows/" + enc(seriesId) + "/Episodes?userId=" + userId(c) + "&season=" + season));
        JSONArray arr = eps.optJSONArray("Items");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            int s = e.optInt("ParentIndexNumber", -1);
            int n = e.optInt("IndexNumber", -1);
            int end = e.optInt("IndexNumberEnd", n);
            if (s == season && episode >= n && episode <= end) return e.optString("Id");
        }
        return null;
    }

    private void safeAddLocal(Context c, JSONArray out, String itemId, String host) {
        try {
            addLocal(c, out, itemId, host);
        } catch (Throwable t) {
            Log.w(TAG, "local item " + itemId + ": " + t.getMessage());
        }
    }

    private void addLocal(Context c, JSONArray out, String itemId, String host) throws Exception {
        String token = jfToken(c);
        JSONObject it = new JSONObject(jfGet(c, "/Users/" + userId(c) + "/Items/" + enc(itemId)));
        JSONArray sources = it.optJSONArray("MediaSources");
        if (sources == null) return;
        String base = "http://" + host + ":8096";
        for (int i = 0; i < sources.length(); i++) {
            JSONObject ms = sources.getJSONObject(i);
            String msId = ms.optString("Id", itemId);
            String path = ms.optString("Path", "");
            String file = path.isEmpty() ? it.optString("Name") : path.substring(path.lastIndexOf('/') + 1);
            long size = ms.optLong("Size", 0);
            String res = "";
            JSONArray subs = new JSONArray();
            JSONArray streamsArr = ms.optJSONArray("MediaStreams");
            if (streamsArr != null) {
                for (int k = 0; k < streamsArr.length(); k++) {
                    JSONObject st = streamsArr.getJSONObject(k);
                    String t = st.optString("Type");
                    if ("Video".equals(t) && res.isEmpty()) res = resolutionLabel(st.optInt("Width", 0), st.optInt("Height", 0));
                    if ("Subtitle".equals(t) && st.optBoolean("IsExternal") && st.optBoolean("SupportsExternalStream", true)) {
                        int idx = st.optInt("Index");
                        String codec = st.optString("Codec", "srt").toLowerCase(Locale.ROOT);
                        String fmt = codec.contains("ass") || codec.contains("ssa") ? "ass" : codec.contains("vtt") ? "vtt" : "srt";
                        subs.put(new JSONObject()
                                .put("id", "jellyfin-" + msId + "-" + idx)
                                .put("url", base + "/Videos/" + itemId + "/" + msId + "/Subtitles/" + idx + "/0/Stream." + fmt + "?api_key=" + token)
                                .put("lang", st.optString("Language", "und")));
                    }
                }
            }
            String url = base + "/Videos/" + itemId + "/stream?static=true&MediaSourceId=" + enc(msId) + "&api_key=" + token;
            JSONObject s = new JSONObject();
            s.put("name", "Jellyarr\n📁 " + (res.isEmpty() ? "Local" : res));
            String desc = "🏠 Jellyfin library\n📄 " + file + (size > 0 ? "\n💾 " + human(size) : "");
            s.put("title", desc);
            s.put("description", desc);
            s.put("url", url);
            if (subs.length() > 0) s.put("subtitles", subs);
            JSONObject hints = new JSONObject();
            hints.put("bingeGroup", "jellyarr-local");
            hints.put("filename", file);
            if (size > 0) hints.put("videoSize", size);
            s.put("behaviorHints", hints);
            out.put(s);
        }
    }

    private void addTorrents(Context c, JSONArray out, String type, String imdb, int season, int episode,
                             String host, String self) throws Exception {
        MediaServiceManager mgr = MediaServiceManager.getInstance(c);
        if (mgr == null || !mgr.isServiceRunning("prowlarr")) return;
        String key = mgr.getServiceApiKey("prowlarr");
        if (key == null || key.isEmpty()) return;
        boolean series = "series".equals(type) && season >= 0;

        List<JSONObject> results = new ArrayList<>();
        if (series) {
            results.addAll(prowlarrSearch(key, "tvsearch", "{ImdbId:" + imdb + "}{Season:" + season + "}{Episode:" + episode + "}", "5000"));
            if (results.size() < 5) {
                results.addAll(prowlarrSearch(key, "tvsearch", "{ImdbId:" + imdb + "}{Season:" + season + "}", "5000"));
            }
        } else {
            results.addAll(prowlarrSearch(key, "movie", "{ImdbId:" + imdb + "}", "2000"));
        }
        if (results.isEmpty()) {
            JSONObject cm = cinemeta(series ? "series" : "movie", imdb);
            if (cm != null) {
                String name = cm.optString("name", "");
                if (!name.isEmpty()) {
                    String q = series ? name + String.format(Locale.US, " S%02dE%02d", season, episode)
                            : name + yearOf(cm);
                    results.addAll(prowlarrSearch(key, "search", q, series ? "5000" : "2000"));
                }
            }
        }

        // Torrents only, matching episode, deduplicated, best seeded first
        List<JSONObject> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JSONObject r : results) {
            if (!"torrent".equalsIgnoreCase(r.optString("protocol", "torrent"))) continue;
            String title = r.optString("title", "");
            if (series && !episodeMatches(title, season, episode)) continue;
            String dedup = r.optString("infoHash", "").toLowerCase(Locale.ROOT);
            if (dedup.isEmpty()) dedup = title.toLowerCase(Locale.ROOT) + "|" + r.optLong("size");
            if (!seen.add(dedup)) continue;
            kept.add(r);
        }
        Collections.sort(kept, (a, b) -> Integer.compare(b.optInt("seeders", 0), a.optInt("seeders", 0)));

        int max = getMaxTorrents(c);
        for (int i = 0; i < kept.size() && i < max; i++) {
            JSONObject r = kept.get(i);
            if (r.optInt("seeders", 0) <= 0 && i > 0) continue;
            TorrentRef ref = new TorrentRef();
            ref.title = r.optString("title", "");
            ref.infoHash = r.optString("infoHash", "");
            ref.magnet = r.optString("magnetUrl", "");
            ref.downloadUrl = r.optString("downloadUrl", "");
            ref.season = series ? season : -1;
            ref.episode = series ? episode : -1;
            if (ref.infoHash.isEmpty() && ref.magnet.isEmpty() && ref.downloadUrl.isEmpty()) continue;
            String tokenId = randomHex(10);
            tokens.put(tokenId, ref);

            String quality = qualityOf(ref.title);
            long size = r.optLong("size", 0);
            String desc = ref.title + "\n👤 " + r.optInt("seeders", 0)
                    + (size > 0 ? "  💾 " + human(size) : "")
                    + "  ⚙️ " + r.optString("indexer", "Prowlarr");
            JSONObject s = new JSONObject();
            s.put("name", "Jellyarr\n🧲 " + quality);
            s.put("title", desc);
            s.put("description", desc);
            s.put("url", self + "/play/" + tokenId);
            JSONObject hints = new JSONObject();
            hints.put("bingeGroup", "jellyarr-torrent-" + quality);
            hints.put("notWebReady", true);
            if (size > 0) hints.put("videoSize", size);
            hints.put("filename", ref.title);
            s.put("behaviorHints", hints);
            out.put(s);
        }
    }

    private List<JSONObject> prowlarrSearch(String key, String type, String query, String cats) {
        List<JSONObject> list = new ArrayList<>();
        try {
            String url = PROWLARR + "/api/v1/search?type=" + type + "&query=" + enc(query)
                    + "&categories=" + cats + "&limit=100";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(45000);
            conn.setRequestProperty("X-Api-Key", key);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Prowlarr search HTTP " + code + " for " + query);
                return list;
            }
            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(readAll(in, 16 * 1024 * 1024), StandardCharsets.UTF_8);
            }
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        } catch (Throwable t) {
            Log.w(TAG, "Prowlarr search failed: " + t.getMessage());
        }
        return list;
    }

    private JSONObject cinemeta(String type, String imdb) {
        String k = type + ":" + imdb;
        JSONObject cached = cinemetaCache.get(k);
        if (cached != null) return cached;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(CINEMETA + "/meta/" + type + "/" + imdb + ".json").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                JSONObject meta = new JSONObject(new String(readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8)).optJSONObject("meta");
                if (meta != null) cinemetaCache.put(k, meta);
                return meta;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String yearOf(JSONObject meta) {
        String y = meta.optString("year", meta.optString("releaseInfo", ""));
        Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(y);
        return m.find() ? " " + m.group() : "";
    }

    // ------------------------------------------------------------------ release parsing

    private static final Pattern SE = Pattern.compile("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})(?:[ ._-]?E?(\\d{1,3}))?\\b");
    private static final Pattern XE = Pattern.compile("(?i)\\b(\\d{1,2})x(\\d{2,3})\\b");
    private static final Pattern SEASON_ONLY = Pattern.compile("(?i)\\b(?:S(\\d{1,2})(?!\\d|[ ._-]?E\\d)|Season[ ._-]?(\\d{1,2}))\\b");

    /** True when the release is that episode, a range containing it, or a pack of its season. */
    static boolean episodeMatches(String title, int season, int episode) {
        Matcher m = SE.matcher(title);
        if (m.find()) {
            int s = parseInt(m.group(1));
            int e1 = parseInt(m.group(2));
            int e2 = m.group(3) != null ? parseInt(m.group(3)) : e1;
            return s == season && episode >= e1 && episode <= Math.max(e1, e2);
        }
        Matcher x = XE.matcher(title);
        if (x.find()) return parseInt(x.group(1)) == season && parseInt(x.group(2)) == episode;
        Matcher so = SEASON_ONLY.matcher(title);
        if (so.find()) {
            int s = parseInt(so.group(1) != null ? so.group(1) : so.group(2));
            return s == season;
        }
        // Complete series packs and odd names: keep, the file is picked at play time.
        return true;
    }

    static boolean fileMatchesEpisode(String path, int season, int episode) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        Matcher m = SE.matcher(name);
        if (m.find()) {
            int s = parseInt(m.group(1));
            int e1 = parseInt(m.group(2));
            int e2 = m.group(3) != null ? parseInt(m.group(3)) : e1;
            return s == season && episode >= e1 && episode <= Math.max(e1, e2);
        }
        Matcher x = XE.matcher(name);
        if (x.find()) return parseInt(x.group(1)) == season && parseInt(x.group(2)) == episode;
        // "Season 2/05 - Title.mkv" style: episode number in the file, season in the folder
        Matcher so = SEASON_ONLY.matcher(path);
        boolean seasonOk = !so.find() || parseInt(so.group(1) != null ? so.group(1) : so.group(2)) == season;
        Matcher ep = Pattern.compile("(?i)(?:^|[ ._\\-\\[(])(?:E|EP|Episode[ ._-]?)?0*" + episode + "(?=[ ._\\-\\])]|$)").matcher(name);
        return seasonOk && ep.find();
    }

    private static String qualityOf(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("2160p") || t.contains("4k") || t.contains("uhd")) return "4K";
        if (t.contains("1080p")) return "1080p";
        if (t.contains("720p")) return "720p";
        if (t.contains("576p") || t.contains("480p") || t.contains("dvdrip")) return "SD";
        return "Torrent";
    }

    private static String resolutionLabel(int w, int h) {
        if (w >= 3200 || h >= 2000) return "4K";
        if (w >= 1800 || h >= 1000) return "1080p";
        if (w >= 1200 || h >= 700) return "720p";
        if (h > 0) return h + "p";
        return "";
    }

    // ------------------------------------------------------------------ play (TorrServer)

    private static final Pattern VIDEO_EXT = Pattern.compile("(?i)\\.(mkv|mp4|m4v|avi|mov|wmv|ts|m2ts|webm|mpg|mpeg|flv)$");

    private Resp play(Context c, String tokenId, String host) throws Exception {
        TorrentRef ref = tokens.get(tokenId);
        if (ref == null) return Resp.text(404, "This link expired. Open the title again to refresh the list.");
        MediaServiceManager mgr = MediaServiceManager.getInstance(c);
        if (mgr == null || !mgr.isServiceRunning("torrserver")) {
            return Resp.text(503, "TorrServer is not running. Start it in Jellyarr.");
        }

        if (ref.resolvedHash == null) {
            String link = resolveLink(ref);
            if (link == null) return Resp.text(502, "Could not get the torrent from Prowlarr.");
            JSONObject status = torrStat(link);
            if (status == null) return Resp.text(502, "TorrServer could not load this torrent (no peers?).");
            String hash = status.optString("hash", "");
            JSONArray files = status.optJSONArray("file_stats");
            if (hash.isEmpty() || files == null || files.length() == 0) {
                return Resp.text(502, "TorrServer returned no files for this torrent.");
            }
            int bestId = -1;
            long bestLen = -1;
            String bestPath = null;
            int epId = -1;
            long epLen = -1;
            String epPath = null;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String path = f.optString("path", "");
                long len = f.optLong("length", 0);
                if (!VIDEO_EXT.matcher(path).find()) continue;
                if (path.toLowerCase(Locale.ROOT).contains("sample") && len < 300L * 1024 * 1024) continue;
                if (len > bestLen) { bestLen = len; bestId = f.optInt("id"); bestPath = path; }
                if (ref.season >= 0 && fileMatchesEpisode(path, ref.season, ref.episode) && len > epLen) {
                    epLen = len; epId = f.optInt("id"); epPath = path;
                }
            }
            if (ref.season >= 0 && epId >= 0) { bestId = epId; bestPath = epPath; }
            if (bestId < 0) return Resp.text(502, "No video file in this torrent.");
            ref.resolvedHash = hash;
            ref.resolvedIndex = bestId;
            ref.resolvedName = bestPath.substring(bestPath.lastIndexOf('/') + 1);
        }
        String to = "http://" + host + ":" + TORRSERVER_PORT + "/stream/" + encPath(ref.resolvedName)
                + "?link=" + ref.resolvedHash + "&index=" + ref.resolvedIndex + "&play";
        return Resp.redirect(to);
    }

    /** A magnet for the release: Prowlarr's infohash, its magnet, or the .torrent it serves. */
    private String resolveLink(TorrentRef ref) {
        if (ref.magnet != null && ref.magnet.startsWith("magnet:")) return ref.magnet;
        if (ref.infoHash != null && ref.infoHash.matches("(?i)[0-9a-f]{40}")) {
            return "magnet:?xt=urn:btih:" + ref.infoHash.toLowerCase(Locale.ROOT) + "&dn=" + enc(ref.title);
        }
        for (String u : new String[]{ref.magnet, ref.downloadUrl}) {
            if (u == null || u.isEmpty()) continue;
            if (u.startsWith("magnet:")) return u;
            try {
                String m = fetchTorrentAsMagnet(u, 0);
                if (m != null) return m;
            } catch (Throwable t) {
                Log.w(TAG, "torrent fetch: " + t.getMessage());
            }
        }
        return null;
    }

    private String fetchTorrentAsMagnet(String url, int depth) throws Exception {
        if (depth > 5) return null;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code >= 300 && code < 400) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if (loc == null) return null;
            if (loc.startsWith("magnet:")) return loc;
            return fetchTorrentAsMagnet(new URL(new URL(url), loc).toString(), depth + 1);
        }
        if (code != 200) { conn.disconnect(); return null; }
        byte[] data;
        try (InputStream in = conn.getInputStream()) {
            data = readAll(in, 20 * 1024 * 1024);
        } finally {
            conn.disconnect();
        }
        return torrentToMagnet(data);
    }

    private JSONObject torrStat(String magnet) {
        try {
            String url = TORRSERVER + "/stream/fname?link=" + enc(magnet) + "&stat";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(90000);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "TorrServer stat HTTP " + code);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(new String(readAll(in, 8 * 1024 * 1024), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "TorrServer stat failed: " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ bencode (.torrent -> magnet)

    /** Info hash and trackers of a .torrent file, as a magnet link. Null when it does not parse. */
    static String torrentToMagnet(byte[] t) {
        try {
            if (t.length == 0 || t[0] != 'd') return null;
            int[] pos = {1};
            int infoStart = -1, infoEnd = -1;
            String name = null;
            List<String> trackers = new ArrayList<>();
            while (pos[0] < t.length && t[pos[0]] != 'e') {
                String key = new String(readBytes(t, pos), StandardCharsets.UTF_8);
                int start = pos[0];
                if ("info".equals(key)) {
                    infoStart = start;
                    skip(t, pos);
                    infoEnd = pos[0];
                    name = findName(t, infoStart);
                } else if ("announce".equals(key) && t[start] >= '0' && t[start] <= '9') {
                    trackers.add(new String(readBytes(t, pos), StandardCharsets.UTF_8));
                } else if ("announce-list".equals(key) && t[start] == 'l') {
                    pos[0]++;
                    while (t[pos[0]] != 'e') {
                        if (t[pos[0]] == 'l') {
                            pos[0]++;
                            while (t[pos[0]] != 'e') {
                                if (t[pos[0]] >= '0' && t[pos[0]] <= '9') {
                                    String tr = new String(readBytes(t, pos), StandardCharsets.UTF_8);
                                    if (!trackers.contains(tr)) trackers.add(tr);
                                } else skip(t, pos);
                            }
                            pos[0]++;
                        } else skip(t, pos);
                    }
                    pos[0]++;
                } else {
                    skip(t, pos);
                }
            }
            if (infoStart < 0) return null;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(t, infoStart, infoEnd - infoStart);
            StringBuilder hex = new StringBuilder();
            for (byte b : sha1.digest()) hex.append(String.format(Locale.US, "%02x", b));
            StringBuilder m = new StringBuilder("magnet:?xt=urn:btih:").append(hex);
            if (name != null) m.append("&dn=").append(enc(name));
            int n = 0;
            for (String tr : trackers) {
                if (n++ >= 30) break;
                m.append("&tr=").append(enc(tr));
            }
            return m.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String findName(byte[] t, int dictStart) {
        try {
            int[] pos = {dictStart + 1};
            while (t[pos[0]] != 'e') {
                String key = new String(readBytes(t, pos), StandardCharsets.UTF_8);
                if ("name".equals(key) && t[pos[0]] >= '0' && t[pos[0]] <= '9') {
                    return new String(readBytes(t, pos), StandardCharsets.UTF_8);
                }
                skip(t, pos);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] readBytes(byte[] t, int[] pos) {
        int colon = pos[0];
        while (t[colon] != ':') colon++;
        int len = Integer.parseInt(new String(t, pos[0], colon - pos[0], StandardCharsets.US_ASCII));
        byte[] out = new byte[len];
        System.arraycopy(t, colon + 1, out, 0, len);
        pos[0] = colon + 1 + len;
        return out;
    }

    private static void skip(byte[] t, int[] pos) {
        byte b = t[pos[0]];
        if (b == 'i') {
            while (t[pos[0]] != 'e') pos[0]++;
            pos[0]++;
        } else if (b == 'l' || b == 'd') {
            pos[0]++;
            while (t[pos[0]] != 'e') skip(t, pos);
            pos[0]++;
        } else if (b >= '0' && b <= '9') {
            readBytes(t, pos);
        } else {
            throw new IllegalArgumentException("bad bencode at " + pos[0]);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) != -1) {
            b.write(buf, 0, r);
            if (b.size() > max) throw new Exception("response too large");
        }
        return b.toByteArray();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String encPath(String s) {
        return enc(s).replace("%2F", "/");
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    private static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return bytes + " B";
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
