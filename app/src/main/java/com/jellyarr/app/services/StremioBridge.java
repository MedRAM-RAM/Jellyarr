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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    private static final String OPENSUBS = "https://opensubtitles-v3.strem.io";
    // Package-private and not final so tests can point them at a local server.
    static String subdlApi = "https://api.subdl.com/api/v1/subtitles";
    static String subdlDl = "https://dl.subdl.com";
    static String wyzieApi = "https://sub.wyzie.io";
    static String subsourceApi = "https://api.subsource.net/api/v1";
    static String oscomApi = "https://api.opensubtitles.com/api/v1";
    /** How many of the best torrent results also get a home-network entry when a public address is set. */
    private static final int LAN_TORRENTS = 5;
    /** How long /stream waits for Prowlarr; slower searches keep running and fill the cache for the next request. */
    static long prowlarrBudgetMs = 12000;
    private static final long PROWLARR_TTL_MS = 15 * 60 * 1000L;
    private static final String[] GENRES_MOVIE = {"Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
            "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport",
            "Thriller", "War", "Western"};
    private static final String[] GENRES_SERIES = {"Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
            "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport",
            "Thriller", "War", "Western", "Reality-TV", "Talk-Show", "Game-Show"};
    private static final long CATALOG_TTL_MS = 10 * 60 * 1000L;
    private static final long META_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long SUBS_TTL_MS = 15 * 60 * 1000L;
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

    private static final class CachedJson {
        final long at;
        final JSONObject json;
        CachedJson(long at, JSONObject json) { this.at = at; this.json = json; }
    }

    private final Map<String, CachedJson> jsonCache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedJson>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedJson> e) {
                    return size() > 300;
                }
            });

    private static final class TorrentRef {
        String magnet;
        String infoHash;
        String downloadUrl;
        String title;
        int season = -1;
        int episode = -1;
        List<String[]> subFiles = new ArrayList<>(); // {file index, path} of .srt files shipped in the torrent
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

    public static boolean isCatalogsEnabled(Context c) { return prefs(c).getBoolean("catalogs", true); }

    public static void setCatalogsEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("catalogs", on).apply(); }

    public static boolean isSubtitlesEnabled(Context c) { return prefs(c).getBoolean("subtitles", true); }

    public static void setSubtitlesEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("subtitles", on).apply(); }

    /** ISO 639-2 codes of the subtitle languages to offer, in order (e.g. "ara,eng"). Empty means every language. */
    public static String getSubtitleLangs(Context c) { return prefs(c).getString("sub_langs", "ara,eng"); }

    public static void setSubtitleLangs(Context c, String langs) {
        prefs(c).edit().putString("sub_langs", normalizeLangs(langs)).apply();
    }

    static String normalizeLangs(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            String l = part.trim().toLowerCase(Locale.ROOT);
            if (l.isEmpty()) continue;
            if (l.equals("ar")) l = "ara";
            else if (l.equals("en")) l = "eng";
            if (!l.matches("[a-z]{2,3}(-[a-z]{2,4})?") || !seen.add(l)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(l);
        }
        return sb.toString();
    }

    // SubDL (free API key from the SubDL account page), searched directly from this phone.
    public static String getSubdlKey(Context c) { return prefs(c).getString("subdl_key", ""); }

    public static boolean setSubdlKey(Context c, String key) {
        String v = key == null ? "" : key.trim();
        if (!v.isEmpty() && !v.matches("[A-Za-z0-9_\\-]{10,128}")) return false;
        prefs(c).edit().putString("subdl_key", v).apply();
        return true;
    }

    public static String getWyzieKey(Context c) { return prefs(c).getString("wyzie_key", ""); }
    public static boolean setWyzieKey(Context c, String k) { return saveKey(c, "wyzie_key", k); }
    public static String getSubsourceKey(Context c) { return prefs(c).getString("subsource_key", ""); }
    public static boolean setSubsourceKey(Context c, String k) { return saveKey(c, "subsource_key", k); }
    public static String getOscomKey(Context c) { return prefs(c).getString("oscom_key", ""); }
    public static boolean setOscomKey(Context c, String k) { return saveKey(c, "oscom_key", k); }

    private static boolean saveKey(Context c, String pref, String key) {
        String v = key == null ? "" : key.trim();
        if (!v.isEmpty() && !v.matches("[A-Za-z0-9_\\-]{10,200}")) return false;
        prefs(c).edit().putString(pref, v).apply();
        return true;
    }

    /** Other Stremio subtitle addons to ask as well (SubSource, SubHero, OpenSubtitles V3+...), one address per line. */
    public static String getSubAddons(Context c) { return prefs(c).getString("sub_addons", ""); }

    public static boolean setSubAddons(Context c, String text) {
        String n = normalizeAddonList(text);
        if (n == null) return false;
        prefs(c).edit().putString("sub_addons", n).apply();
        return true;
    }

    /** Other Stremio catalog addons whose lists are added to this one, one address per line. */
    public static String getCatAddons(Context c) { return prefs(c).getString("cat_addons", ""); }

    public static boolean setCatAddons(Context c, String text) {
        String n = normalizeAddonList(text);
        if (n == null) return false;
        prefs(c).edit().putString("cat_addons", n).apply();
        return true;
    }

    private static final int MAX_ADDONS = 5;

    /** Lines to manifest URLs (stremio:// becomes https://, a missing /manifest.json is added); null when a line is invalid. */
    static String normalizeAddonList(String text) {
        List<String> out = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("[\\r\\n]+")) {
            String u = line.trim();
            if (u.isEmpty()) continue;
            if (u.regionMatches(true, 0, "stremio://", 0, 10)) u = "https://" + u.substring(10);
            if (!u.matches("(?i)https?://[^\\s/?#]+(/[^\\s?#]*)?")) return null;
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (!u.toLowerCase(Locale.ROOT).endsWith("/manifest.json")) u = u + "/manifest.json";
            if (!out.contains(u)) out.add(u);
            if (out.size() > MAX_ADDONS) return null;
        }
        return String.join("\n", out);
    }

    /** Base addresses (without /manifest.json) of a saved addon list. */
    static List<String> addonBases(String saved) {
        List<String> out = new ArrayList<>();
        for (String u : (saved == null ? "" : saved).split("\n")) {
            u = u.trim();
            if (u.isEmpty()) continue;
            out.add(u.substring(0, u.length() - "/manifest.json".length()));
        }
        return out;
    }

    // iso3, alternative iso3, iso2, SubDL code, English name
    private static final String[][] LANGS = {
            {"ara", "", "ar", "AR", "arabic"}, {"eng", "", "en", "EN", "english"}, {"fre", "fra", "fr", "FR", "french"},
            {"spa", "", "es", "ES", "spanish"}, {"ger", "deu", "de", "DE", "german"}, {"ita", "", "it", "IT", "italian"},
            {"por", "", "pt", "PT", "portuguese"}, {"pob", "", "pb", "BR_PT", "brazilian"}, {"tur", "", "tr", "TR", "turkish"},
            {"rus", "", "ru", "RU", "russian"}, {"per", "fas", "fa", "FA", "persian"}, {"heb", "", "he", "HE", "hebrew"},
            {"hin", "", "hi", "HI", "hindi"}, {"ind", "", "id", "ID", "indonesian"}, {"chi", "zho", "zh", "ZH", "chinese"},
            {"jpn", "", "ja", "JA", "japanese"}, {"kor", "", "ko", "KO", "korean"}, {"dut", "nld", "nl", "NL", "dutch"},
            {"pol", "", "pl", "PL", "polish"}, {"swe", "", "sv", "SV", "swedish"}, {"ell", "gre", "el", "EL", "greek"},
            {"rum", "ron", "ro", "RO", "romanian"}, {"hun", "", "hu", "HU", "hungarian"}, {"cze", "ces", "cs", "CS", "czech"},
            {"ukr", "", "uk", "UK", "ukrainian"}, {"vie", "", "vi", "VI", "vietnamese"}, {"tha", "", "th", "TH", "thai"},
            {"ben", "", "bn", "BN", "bengali"}, {"urd", "", "ur", "UR", "urdu"}, {"bul", "", "bg", "BG", "bulgarian"},
            {"srp", "", "sr", "SR", "serbian"}, {"hrv", "", "hr", "HR", "croatian"}, {"nor", "", "no", "NO", "norwegian"},
            {"dan", "", "da", "DA", "danish"}, {"fin", "", "fi", "FI", "finnish"}, {"kur", "", "ku", "KU", "kurdish"},
    };

    /** Any spelling of a language (ara, ar, AR, Arabic, BR_PT...) as the three-letter code; null when unknown. */
    static String canonLang(String raw) {
        if (raw == null) return null;
        String l = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (l.isEmpty()) return null;
        if (l.equals("pt_br") || l.equals("br_pt") || l.startsWith("portuguese (br") || l.equals("brazilian portuguese")) return "pob";
        for (String[] row : LANGS) {
            if (l.equals(row[0]) || l.equals(row[1]) || l.equals(row[2]) || l.equals(row[3].toLowerCase(Locale.ROOT)) || l.equals(row[4])) return row[0];
        }
        return l.matches("[a-z]{3}") ? l : null;
    }

    static String subdlCode(String iso3) {
        for (String[] row : LANGS) if (row[0].equals(iso3)) return row[3];
        return null;
    }

    /** Character set old subtitle files in that language were usually saved in. */
    static String legacyCharset(String iso3) {
        if (iso3 == null) return "windows-1252";
        switch (iso3) {
            case "ara": case "per": case "urd": case "kur": return "windows-1256";
            case "heb": return "windows-1255";
            case "rus": case "ukr": case "bul": case "srp": return "windows-1251";
            case "tur": return "windows-1254";
            case "ell": return "windows-1253";
            case "chi": return "GBK";
            case "jpn": return "Shift_JIS";
            case "kor": return "EUC-KR";
            case "pol": case "cze": case "hun": case "rum": case "hrv": return "windows-1250";
            case "tha": return "windows-874";
            case "vie": return "windows-1258";
            default: return "windows-1252";
        }
    }

    /**
     * Where video, subtitles and posters are served from when a public address is set: "both" (a home-network entry
     * and a public one), "lan" (home network only: the tunnel carries just the addon's lists) or "public".
     */
    public static String getStreamLinks(Context c) {
        String v = prefs(c).getString("stream_links", "both");
        return v.equals("lan") || v.equals("public") ? v : "both";
    }

    public static void setStreamLinks(Context c, String v) {
        prefs(c).edit().putString("stream_links", v.equals("lan") || v.equals("public") ? v : "both").apply();
    }

    // ---- subtitle options
    private static int intPref(Context c, String k, int d, int lo, int hi) {
        return Math.max(lo, Math.min(hi, prefs(c).getInt(k, d)));
    }

    private static boolean saveInt(Context c, String k, String text, int lo, int hi) {
        try {
            int v = Integer.parseInt(text == null ? "" : text.trim());
            if (v < lo || v > hi) return false;
            prefs(c).edit().putInt(k, v).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Most subtitles offered per language. */
    public static int getSubMax(Context c) { return intPref(c, "sub_max", 12, 1, 40); }
    public static boolean setSubMax(Context c, String t) { return saveInt(c, "sub_max", t, 1, 40); }
    /** How many per language are downloaded and checked while the list is built (0 = none). */
    public static int getSubVerify(Context c) { return intPref(c, "sub_verify", 6, 0, 12); }
    public static boolean setSubVerify(Context c, String t) { return saveInt(c, "sub_verify", t, 0, 12); }
    /** Seconds the list may wait for those checks. */
    public static int getSubBudget(Context c) { return intPref(c, "sub_budget", 12, 3, 40); }
    public static boolean setSubBudget(Context c, String t) { return saveInt(c, "sub_budget", t, 3, 40); }
    /** Checked subtitles that match the video less than this many percent are hidden. */
    public static int getSubMinAcc(Context c) { return intPref(c, "sub_min_acc", 0, 0, 90); }
    public static boolean setSubMinAcc(Context c, String t) { return saveInt(c, "sub_min_acc", t, 0, 90); }

    /** "auto" shifts subtitles that are clearly out of sync, "check" only reports it, "off" never touches them. */
    public static String getSubSync(Context c) {
        String v = prefs(c).getString("sub_sync", "auto");
        return v.equals("check") || v.equals("off") ? v : "auto";
    }
    public static void setSubSync(Context c, String v) {
        prefs(c).edit().putString("sub_sync", v.equals("check") || v.equals("off") ? v : "auto").apply();
    }
    /** "any", "exclude" or "prefer" for hearing-impaired subtitles. */
    public static String getSubHi(Context c) {
        String v = prefs(c).getString("sub_hi", "any");
        return v.equals("exclude") || v.equals("prefer") ? v : "any";
    }
    public static void setSubHi(Context c, String v) {
        prefs(c).edit().putString("sub_hi", v.equals("exclude") || v.equals("prefer") ? v : "any").apply();
    }
    public static boolean isSubTitles(Context c) { return prefs(c).getBoolean("sub_titles", true); }
    public static void setSubTitles(Context c, boolean on) { prefs(c).edit().putBoolean("sub_titles", on).apply(); }
    public static boolean isOsV3(Context c) { return prefs(c).getBoolean("sub_osv3", true); }
    public static void setOsV3(Context c, boolean on) { prefs(c).edit().putBoolean("sub_osv3", on).apply(); }
    /** Checking OpenSubtitles.com results downloads them, which counts against the key's daily quota. */
    public static boolean isVerifyOscom(Context c) { return prefs(c).getBoolean("sub_verify_oscom", false); }
    public static void setVerifyOscom(Context c, boolean on) { prefs(c).edit().putBoolean("sub_verify_oscom", on).apply(); }

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

    /** Optional public base address (e.g. https://jellyarr.example.com) that reaches this phone's port 7070. */
    public static String getPublicUrl(Context c) { return prefs(c).getString("public_url", ""); }

    /** Saves the public address; returns false (and saves nothing) when it is not a valid http(s) address. */
    public static boolean setPublicUrl(Context c, String url) {
        String n = normalizePublicUrl(url);
        if (n == null) return false;
        prefs(c).edit().putString("public_url", n).apply();
        return true;
    }

    /** "" for empty input, the address without trailing slashes or path-less noise, or null when invalid. */
    static String normalizePublicUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.matches("(?i)https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?(/[A-Za-z0-9._~\\-/]*)?")) return null;
        return u;
    }

    /** The manifest URL to paste into an app: the public address when set, else the LAN one. */
    public static String getManifestUrl(Context c) {
        String pub = getPublicUrl(c);
        if (!pub.isEmpty()) return pub + "/" + getKey(c) + "/manifest.json";
        return getLanManifestUrl(c);
    }

    public static String getLanManifestUrl(Context c) {
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
            pool = Executors.newFixedThreadPool(32, r -> {
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
        String range;
        String via = "";
    }

    // ---- activity log: what was asked and how long it took, to find out what is slow
    private final java.util.ArrayDeque<String> activity = new java.util.ArrayDeque<>();

    private void log(String line) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
        synchronized (activity) {
            activity.addFirst(t + "  " + line);
            while (activity.size() > 60) activity.removeLast();
        }
    }

    /** The newest {@code max} lines, newest first. */
    public String getActivity(int max) {
        StringBuilder sb = new StringBuilder();
        synchronized (activity) {
            int n = 0;
            for (String l : activity) {
                if (n++ >= max) break;
                sb.append(l).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** Set per request when a public address is configured: the same server reached over the home network. */
    private static final ThreadLocal<String> LAN_SELF = new ThreadLocal<>();

    private String viaOf(Context c, String host) {
        String h = host == null ? "" : host.trim();
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) h = h.substring(0, colon);
        String pub = getPublicUrl(c);
        if (!pub.isEmpty() && pub.toLowerCase(Locale.ROOT).contains("//" + h.toLowerCase(Locale.ROOT))) return "public";
        if (h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("172.") || h.startsWith("100.")
                || h.equals("localhost") || h.startsWith("127.")) return "lan";
        return h.isEmpty() ? "?" : "other";
    }

    private static final class Resp {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];
        String location;
        int maxAge = 0;
        /** When set, the body is streamed from this local URL (Range passed through) instead of {@link #body}. */
        String proxyUrl;
        Map<String, String> proxyHeaders;

        static Resp proxy(String url, Map<String, String> headers, int maxAge) {
            Resp r = new Resp();
            r.proxyUrl = url;
            r.proxyHeaders = headers;
            r.maxAge = maxAge;
            return r;
        }

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
            Context cc = app;
            if (cc != null) req.via = viaOf(cc, req.host);
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
            if (resp.proxyUrl != null) {
                sock.setSoTimeout(0);
                pipe(sock.getOutputStream(), req, resp);
                return;
            }
            write(sock.getOutputStream(), resp, "HEAD".equals(req.method));
        } catch (Throwable t) {
            Log.d(TAG, "connection: " + t.getMessage());
        } finally {
            LAN_SELF.remove();
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
            if (c <= 0) continue;
            String name = h.substring(0, c).trim();
            if (name.equalsIgnoreCase("host")) r.host = h.substring(c + 1).trim();
            else if (name.equalsIgnoreCase("range")) r.range = h.substring(c + 1).trim();
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

    private static final String[] PASS_HEADERS = {"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"};

    /** Streams an upstream response (Jellyfin, TorrServer) to the client, passing Range through for seeking. */
    private void pipe(OutputStream out, Req req, Resp r) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(r.proxyUrl).openConnection();
        final long t0 = System.currentTimeMillis();
        long ttfb = -1;
        long sent = 0;
        boolean closedEarly = false;
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "Jellyarr-Bridge/1.1");
            if (req.range != null) conn.setRequestProperty("Range", req.range);
            if (r.proxyHeaders != null) {
                for (Map.Entry<String, String> e : r.proxyHeaders.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            }
            int code;
            try {
                code = conn.getResponseCode();
            } catch (Throwable t) {
                Log.w(TAG, "upstream " + t.getMessage());
                write(out, Resp.text(502, "The source did not answer."), "HEAD".equals(req.method));
                return;
            }
            ttfb = System.currentTimeMillis() - t0;
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            boolean ranges = false;
            for (String h : PASS_HEADERS) {
                String v = conn.getHeaderField(h);
                if (v == null) continue;
                if (h.equals("Accept-Ranges")) ranges = true;
                sb.append(h).append(": ").append(v).append("\r\n");
            }
            if (!ranges && (code == 200 || code == 206)) sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Access-Control-Allow-Headers: *\r\n");
            sb.append("Cache-Control: ").append(r.maxAge > 0 ? "max-age=" + r.maxAge : "no-store").append("\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            if ("HEAD".equals(req.method) || in == null) return;
            byte[] buf = new byte[64 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    sent += n;
                }
                out.flush();
            } catch (java.io.IOException e) {
                closedEarly = true; // the player closed the connection (seeking, stopping)
            }
        } finally {
            conn.disconnect();
            String kind = r.proxyUrl.contains("/Videos/") && r.proxyUrl.contains("/stream") ? "video" : r.proxyUrl.contains(":" + TORRSERVER_PORT + "/") ? "torrent" : null;
            if (kind != null && (sent > 256 * 1024 || ttfb > 3000)) {
                long ms = Math.max(1, System.currentTimeMillis() - t0);
                String rate = sent > 256 * 1024 ? String.format(Locale.US, " %.1f Mbit/s", sent * 8.0 / 1000.0 / ms) : "";
                log(String.format(Locale.US, "%s via=%s  first byte %s  %s sent in %.1fs%s%s", kind, req.via,
                        ttfb < 0 ? "-" : ttfb + "ms", human(sent), ms / 1000.0, rate, closedEarly ? "  (player closed)" : ""));
                Matcher hm = Pattern.compile("link=([0-9a-fA-F]{40})").matcher(r.proxyUrl);
                if (kind.equals("torrent") && hm.find()) {
                    final String hash = hm.group(1);
                    fetchPool.execute(() -> {
                        String line = peerLine(torrStat(hash));
                        if (!line.isEmpty()) log("torrent swarm now" + line);
                    });
                }
            }
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 429: return "Too Many Requests";
            case 302: return "Found";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 416: return "Range Not Satisfiable";
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
        String pub = getPublicUrl(c);
        String self = (pub.isEmpty() ? "http://" + publicHost(req.host) + ":" + PORT : pub) + "/" + key;
        if (!pub.isEmpty()) {
            String lanIp = NetworkUtils.getRawLanIp();
            String lanSelf = lanIp != null && !lanIp.isEmpty() ? "http://" + lanIp + ":" + PORT + "/" + key : null;
            String mode = getStreamLinks(c);
            if (lanSelf != null) {
                if (mode.equals("lan")) self = lanSelf;        // only the lists travel through the tunnel
                else if (mode.equals("both")) LAN_SELF.set(lanSelf);
            }
        }

        if (rest.equals("manifest.json")) return Resp.json(manifest(c), 0);
        if (rest.isEmpty() || rest.equals("configure")) return Resp.text(200, "Jellyarr Bridge: add " + self + "/manifest.json to AIOStreams or Stremio.");

        String[] seg = rest.split("/");
        if (seg.length >= 2 && seg[0].equals("play")) return play(c, seg[1]);
        if (seg.length >= 2 && seg[0].equals("jf")) return jf(c, seg, q >= 0 ? req.path.substring(q + 1) : "");
        if (seg.length == 3 && seg[0].equals("sub") && seg[1].equals("t")) return subFile(c, seg[2]);
        if (seg.length < 3) return Resp.text(404, "Not found");
        String resource = seg[0];
        String type = seg[1];
        String id = dec(stripJson(seg[2]));
        String extraRaw = seg.length >= 4 ? stripJson(seg[3]) : "";
        String extra = dec(extraRaw);

        switch (resource) {
            case "catalog": return Resp.json(catalog(c, type, id, extra, extraRaw, self), 60);
            case "meta": return Resp.json(meta(c, type, id, self), 60);
            case "stream": return Resp.json(streams(c, type, id, self), 0);
            case "subtitles": return Resp.json(subtitles(c, type, id, extraRaw, self), 0);
            default: return Resp.text(404, "Not found");
        }
    }

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    private static final Pattern SUB_FILE = Pattern.compile("^(\\d{1,3})\\.(srt|vtt|ass)$");
    private static final Pattern MAX_H = Pattern.compile("(?:^|&)maxHeight=(\\d{2,4})(?:&|$)");

    /**
     * Jellyfin through the bridge: video, subtitle and image routes only, authenticated here so the token
     * never appears in a URL and port 8096 never has to be reachable from outside.
     */
    private Resp jf(Context c, String[] seg, String query) throws Exception {
        if (seg.length < 4) return Resp.text(404, "Not found");
        String token;
        try {
            token = jfToken(c);
        } catch (Throwable t) {
            return Resp.text(503, "Jellyfin is not available.");
        }
        Map<String, String> h = new HashMap<>();
        h.put("X-Emby-Token", token);
        String item = seg[2];
        if (!SAFE_ID.matcher(item).matches()) return Resp.text(404, "Not found");
        switch (seg[1]) {
            case "video": {
                String ms = dec(seg[3]);
                if (seg.length != 4 || !SAFE_ID.matcher(ms).matches()) return Resp.text(404, "Not found");
                return Resp.proxy(JF + "/Videos/" + item + "/stream?static=true&MediaSourceId=" + enc(ms), h, 0);
            }
            case "sub": {
                if (seg.length != 5) return Resp.text(404, "Not found");
                String ms = dec(seg[3]);
                Matcher m = SUB_FILE.matcher(seg[4]);
                if (!SAFE_ID.matcher(ms).matches() || !m.matches()) return Resp.text(404, "Not found");
                return Resp.proxy(JF + "/Videos/" + item + "/" + ms + "/Subtitles/" + m.group(1) + "/0/Stream." + m.group(2), h, 0);
            }
            case "img": {
                String kind = seg[3];
                if (seg.length != 4 || !(kind.equals("Primary") || kind.equals("Backdrop") || kind.equals("Logo") || kind.equals("Thumb"))) {
                    return Resp.text(404, "Not found");
                }
                Matcher mh = MAX_H.matcher(query);
                String maxH = mh.find() ? mh.group(1) : "450";
                return Resp.proxy(JF + "/Items/" + item + "/Images/" + kind + "?maxHeight=" + maxH + "&quality=90", h, 86400);
            }
            default:
                return Resp.text(404, "Not found");
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
        m.put("version", "1.1.0");
        m.put("name", "Jellyarr");
        m.put("description", "Your Jellyfin library, Prowlarr torrents (via TorrServer), catalogs and subtitles in one addon.");
        m.put("logo", "https://raw.githubusercontent.com/MedRAM-RAM/Jellyarr/main/art/jellyarr_logo_v11-1_dark.svg");
        m.put("types", new JSONArray().put("movie").put("series"));
        m.put("idPrefixes", new JSONArray().put("tt").put("jellyarr:"));

        JSONArray types = new JSONArray().put("movie").put("series");
        JSONArray prefixes = new JSONArray().put("tt").put("jellyarr:");
        JSONArray resources = new JSONArray();
        resources.put("catalog");
        resources.put(new JSONObject().put("name", "meta").put("types", types).put("idPrefixes", prefixes));
        resources.put(new JSONObject().put("name", "stream").put("types", types).put("idPrefixes", prefixes));
        if (isSubtitlesEnabled(c)) {
            resources.put(new JSONObject().put("name", "subtitles").put("types", types).put("idPrefixes", prefixes));
        }
        m.put("resources", resources);

        JSONArray catalogs = new JSONArray();
        catalogs.put(catalogDef("movie", "jellyarr-movies", "Jellyfin Movies", null, true));
        catalogs.put(catalogDef("series", "jellyarr-series", "Jellyfin Series", null, true));
        if (isCatalogsEnabled(c)) {
            catalogs.put(catalogDef("movie", "jellyarr-popular", "Jellyarr Popular", GENRES_MOVIE, true));
            catalogs.put(catalogDef("series", "jellyarr-popular", "Jellyarr Popular", GENRES_SERIES, true));
            catalogs.put(catalogDef("movie", "jellyarr-featured", "Jellyarr Featured", GENRES_MOVIE, false));
            catalogs.put(catalogDef("series", "jellyarr-featured", "Jellyarr Featured", GENRES_SERIES, false));
            int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            String[] years = new String[12];
            for (int i = 0; i < years.length; i++) years[i] = String.valueOf(year - i);
            catalogs.put(catalogDef("movie", "jellyarr-new", "Jellyarr New", years, false, true));
            catalogs.put(catalogDef("series", "jellyarr-new", "Jellyarr New", years, false, true));
            addExternalCatalogs(c, catalogs);
        }
        m.put("catalogs", catalogs);
        m.put("behaviorHints", new JSONObject().put("configurable", false).put("configurationRequired", false));
        return m;
    }

    /** Lists of the user's other catalog addons, as extra catalogs here (ids "x<n>-<their id>"). */
    private void addExternalCatalogs(Context c, JSONArray catalogs) {
        List<String> bases = addonBases(getCatAddons(c));
        List<Future<JSONObject>> fs = new ArrayList<>();
        for (String b : bases) fs.add(fetchPool.submit(() -> fetchJson(b + "/manifest.json", 8000, META_TTL_MS)));
        for (int n = 0; n < fs.size(); n++) {
            try {
                JSONObject em = fs.get(n).get(10, TimeUnit.SECONDS);
                JSONArray cs = em == null ? null : em.optJSONArray("catalogs");
                if (cs == null) continue;
                String addonName = em.optString("name", "");
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject cat = cs.optJSONObject(i);
                    if (cat == null) continue;
                    String type = cat.optString("type");
                    if (!type.equals("movie") && !type.equals("series")) continue;
                    JSONArray extra = new JSONArray();
                    boolean usable = true;
                    JSONArray ex = cat.optJSONArray("extra");
                    if (ex != null) {
                        for (int k = 0; k < ex.length(); k++) {
                            JSONObject e = ex.optJSONObject(k);
                            if (e == null) continue;
                            String en = e.optString("name");
                            boolean allowed = en.equals("genre") || en.equals("search") || en.equals("skip");
                            if (!allowed) { if (e.optBoolean("isRequired")) usable = false; continue; }
                            extra.put(e);
                        }
                    } else {
                        JSONArray supported = cat.optJSONArray("extraSupported");
                        if (supported != null) {
                            for (int k = 0; k < supported.length(); k++) {
                                String en = supported.optString(k);
                                if (en.equals("genre") || en.equals("search") || en.equals("skip")) extra.put(new JSONObject().put("name", en));
                            }
                        }
                        JSONArray required = cat.optJSONArray("extraRequired");
                        if (required != null && required.length() > 0) usable = false;
                    }
                    if (!usable) continue;
                    String nm = cat.optString("name", cat.optString("id"));
                    if (!addonName.isEmpty() && !nm.toLowerCase(Locale.ROOT).contains(addonName.toLowerCase(Locale.ROOT))) nm = nm + " · " + addonName;
                    catalogs.put(new JSONObject().put("type", type).put("id", "x" + n + "-" + cat.optString("id")).put("name", nm).put("extra", extra));
                }
            } catch (Throwable t) {
                Log.w(TAG, "catalog addon " + n + ": " + t.getMessage());
            }
        }
    }

    private static JSONObject catalogDef(String type, String id, String name, String[] genres, boolean search) throws Exception {
        return catalogDef(type, id, name, genres, search, false);
    }

    private static JSONObject catalogDef(String type, String id, String name, String[] genres, boolean search, boolean genreRequired) throws Exception {
        JSONArray extra = new JSONArray();
        if (genres != null) {
            JSONArray opts = new JSONArray();
            for (String g : genres) opts.put(g);
            extra.put(new JSONObject().put("name", "genre").put("isRequired", genreRequired).put("options", opts));
        }
        if (search) extra.put(new JSONObject().put("name", "search").put("isRequired", false));
        extra.put(new JSONObject().put("name", "skip").put("isRequired", false));
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

    private static String jfImage(String self, String itemId, String kind, int maxH) {
        return self + "/jf/img/" + itemId + "/" + kind + "?maxHeight=" + maxH;
    }

    private JSONObject catalog(Context c, String type, String id, String extra, String extraRaw, String self) throws Exception {
        JSONArray metas = new JSONArray();
        if ("jellyarr-popular".equals(id) || "jellyarr-featured".equals(id) || "jellyarr-new".equals(id)) {
            if (!isCatalogsEnabled(c) || !("movie".equals(type) || "series".equals(type))) return new JSONObject().put("metas", metas);
            String upstream = "jellyarr-popular".equals(id) ? "top" : "jellyarr-featured".equals(id) ? "imdbRating" : "year";
            return proxyCatalog(c, CINEMETA, type, upstream, extraRaw);
        }
        Matcher ext = EXTERNAL_CATALOG.matcher(id);
        if (ext.matches() && isCatalogsEnabled(c) && ("movie".equals(type) || "series".equals(type))) {
            List<String> bases = addonBases(getCatAddons(c));
            int n = parseInt(ext.group(1));
            if (n >= 0 && n < bases.size()) return proxyCatalog(c, bases.get(n), type, encPath(ext.group(2)), extraRaw);
            return new JSONObject().put("metas", metas);
        }
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
            for (int i = 0; i < items.length(); i++) metas.put(preview(items.getJSONObject(i), type, self));
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

    private JSONObject preview(JSONObject it, String type, String self) throws Exception {
        String itemId = it.optString("Id");
        String imdb = imdbOf(it);
        JSONObject m = new JSONObject();
        m.put("id", imdb != null ? imdb : "jellyarr:" + itemId);
        m.put("type", type);
        m.put("name", it.optString("Name"));
        m.put("poster", jfImage(self, itemId, "Primary", 450));
        m.put("background", jfImage(self, itemId, "Backdrop", 720));
        int year = it.optInt("ProductionYear", 0);
        if (year > 0) m.put("releaseInfo", String.valueOf(year));
        String ov = it.optString("Overview", "");
        if (!ov.isEmpty()) m.put("description", ov);
        JSONArray g = it.optJSONArray("Genres");
        if (g != null) m.put("genres", g);
        return m;
    }

    private static final String LOCAL_BADGE = "📁 In your Jellyfin library";

    private boolean isLocal(Context c, String imdb) {
        try {
            return imdb != null && imdbIndex(c).containsKey(imdb);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void badge(JSONObject meta) throws Exception {
        String d = meta.optString("description", "");
        meta.put("description", d.isEmpty() ? LOCAL_BADGE : LOCAL_BADGE + "\n\n" + d);
    }

    /** Cinemeta's Popular / Featured catalogs (genre, search and paging pass through), flagged when already local. */
    private static final Pattern EXTERNAL_CATALOG = Pattern.compile("^x(\\d)-(.+)$");

    private JSONObject proxyCatalog(Context c, String base, String type, String upstream, String extraRaw) throws Exception {
        String url = base + "/catalog/" + type + "/" + upstream + (extraRaw.isEmpty() ? "" : "/" + extraRaw) + ".json";
        JSONObject d = fetchJson(url, 15000, CATALOG_TTL_MS);
        JSONArray out = new JSONArray();
        JSONArray metas = d == null ? null : d.optJSONArray("metas");
        if (metas != null) {
            for (int i = 0; i < metas.length(); i++) {
                JSONObject src = metas.optJSONObject(i);
                if (src == null) continue;
                String imdb = src.optString("id", src.optString("imdb_id", ""));
                if (!imdb.startsWith("tt")) continue;
                JSONObject m = new JSONObject();
                m.put("id", imdb);
                m.put("type", type);
                m.put("name", src.optString("name"));
                for (String k : new String[]{"poster", "background", "logo", "description", "releaseInfo", "imdbRating", "genres", "runtime"}) {
                    if (src.has(k)) m.put(k, src.get(k));
                }
                if (isLocal(c, imdb)) badge(m);
                out.put(m);
            }
        }
        return new JSONObject().put("metas", out);
    }

    private JSONObject meta(Context c, String type, String id, String self) throws Exception {
        if (id.startsWith("tt")) {
            String imdb = id.split(":")[0];
            JSONObject cm = cinemeta(type, imdb);
            if (cm == null) return new JSONObject().put("meta", JSONObject.NULL);
            JSONObject m = new JSONObject(cm.toString());
            m.put("id", imdb);
            m.put("type", type);
            if (isLocal(c, imdb)) badge(m);
            return new JSONObject().put("meta", m);
        }
        if (!id.startsWith("jellyarr:")) return new JSONObject().put("meta", JSONObject.NULL);
        String itemId = id.substring("jellyarr:".length());
        String uid = userId(c);
        JSONObject it = new JSONObject(jfGet(c, "/Users/" + uid + "/Items/" + enc(itemId)));
        JSONObject m = preview(it, type, self);
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
                    v.put("thumbnail", jfImage(self, e.optString("Id"), "Primary", 300));
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

    private JSONObject streams(Context c, String type, String id, String self) throws Exception {
        JSONArray out = new JSONArray();
        if (id.startsWith("jellyarr:")) {
            safeAddLocal(c, out, id.substring("jellyarr:".length()), self);
            return new JSONObject().put("streams", out);
        }
        String[] p = id.split(":");
        String imdb = p[0];
        int season = p.length >= 3 ? parseInt(p[1]) : -1;
        int episode = p.length >= 3 ? parseInt(p[2]) : -1;
        final long t0 = System.currentTimeMillis();

        // Local files first
        try {
            List<String> ids = imdbIndex(c).get(imdb);
            if (ids != null) {
                for (String itemId : ids) {
                    if ("series".equals(type) && season >= 0) {
                        String ep = findEpisode(c, itemId, season, episode);
                        if (ep != null) safeAddLocal(c, out, ep, self);
                    } else {
                        safeAddLocal(c, out, itemId, self);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "local streams: " + t.getMessage());
        }

        final int local = out.length();
        final long t1 = System.currentTimeMillis();

        // Torrents through Prowlarr + TorrServer
        if (isTorrentsEnabled(c)) {
            try {
                addTorrents(c, out, type, imdb, season, episode, self);
            } catch (Throwable t) {
                Log.w(TAG, "torrent streams: " + t.getMessage());
            }
        }
        log(String.format(Locale.US, "streams %s  local %d (%dms)  torrents %d (%dms)", id, local, t1 - t0,
                out.length() - local, System.currentTimeMillis() - t1));
        return new JSONObject().put("streams", out);
    }

    private static final Pattern STREMIO_ID = Pattern.compile("^tt\\d+(:\\d+:\\d+)?$");
    private final ExecutorService fetchPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Jellyarr-Bridge-Fetch");
        t.setDaemon(true);
        return t;
    });

    /** One subtitle candidate from any source. */
    private static final class Sub {
        String id, url, lang, name;
        int score;
        int source; // 0 SubDL/Wyzie/SubSource/OpenSubtitles.com, 1 OpenSubtitles v3, 2 other addons
        String provider = "";
        boolean hi;
        boolean trusted;     // matched the exact file by hash
        SubRef ref;
        Verdict verdict;     // set when the file was downloaded and checked
    }

    // ------------------------------------------------------------------ what is being played, and what to check against

    /** Identifies the file being played (as far as the player says) and what its subtitles can be checked against. */
    private static final class SyncCtx {
        String key = "", type, imdb, filename, videoHash;
        int season = -1, episode = -1;
        long videoSize;
        LocalMatch local;
        final List<SubRef> hashRefs = Collections.synchronizedList(new ArrayList<SubRef>());
        boolean refDone;
        RefInfo ref;
    }

    private static final class LocalMatch {
        String itemId, msId, name;
        long size;
        double runtimeSec;
        JSONArray streams;
    }

    private static final class RefInfo {
        List<SubtitleSync.Cue> cues;
        String label;
    }

    /** The outcome of downloading and checking one subtitle. */
    private static final class Verdict {
        boolean drop, verified, trusted;
        int accuracy = -1;
        String status = "", detail = "", text;
    }

    private final Map<String, Verdict> verdicts = Collections.synchronizedMap(
            new LinkedHashMap<String, Verdict>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Verdict> e) {
                    return size() > 200;
                }
            });

    private final Map<String, String> rawTexts = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > 80;
                }
            });

    private static String rawKey(SubRef r) { return r.kind + ":" + r.id + "|" + r.lang + "|" + r.season + "|" + r.episode; }

    private static String verdictKey(SubRef r, String mode) {
        return rawKey(r) + "|" + (r.sync == null ? "" : r.sync.key) + "|" + mode;
    }

    /** The subtitle exactly as the provider gives it (cached, so checking and serving download it once). */
    private String fetchRaw(Context c, SubRef r) throws Exception {
        String k = rawKey(r);
        String hit = rawTexts.get(k);
        if (hit != null) return hit;
        String text = fetchSubtitleText(c, r);
        if (text != null) rawTexts.put(k, text);
        return text;
    }

    /**
     * Jellyfin's own subtitle files, then every provider asked in parallel, in the configured languages. The best
     * candidates of each language are downloaded and checked while the list is built (script, then timing against
     * a reference that fits the video), and each entry's title says where it came from and how well it fits.
     */
    private JSONObject subtitles(Context c, String type, String id, String extraRaw, String self) throws Exception {
        JSONArray out = new JSONArray();
        if (!isSubtitlesEnabled(c)) return new JSONObject().put("subtitles", out);

        try {
            addLocalSubtitles(c, out, type, id, self);
        } catch (Throwable t) {
            Log.w(TAG, "local subtitles: " + t.getMessage());
        }
        if (!STREMIO_ID.matcher(id).matches() || !("movie".equals(type) || "series".equals(type))) {
            return new JSONObject().put("subtitles", out);
        }

        final List<String> wanted = new ArrayList<>();
        for (String l : getSubtitleLangs(c).split(",")) {
            String canon = canonLang(l);
            if (canon != null && !wanted.contains(canon)) wanted.add(canon);
        }
        final Map<String, String> extras = parseExtra(dec(extraRaw));
        final String[] p = id.split(":");
        final boolean series = "series".equals(type) && p.length >= 3;
        final SyncCtx x = new SyncCtx();
        x.type = type; x.imdb = p[0];
        x.season = series ? parseInt(p[1]) : -1;
        x.episode = series ? parseInt(p[2]) : -1;
        x.filename = extras.get("filename");
        String vh = extras.get("videoHash");
        x.videoHash = vh != null && vh.matches("[0-9a-fA-F]{16}") ? vh.toLowerCase(Locale.ROOT) : null;
        try { x.videoSize = Long.parseLong(extras.getOrDefault("videoSize", "0")); } catch (Throwable ignored) {}
        x.key = Integer.toHexString((x.filename + "|" + x.videoHash + "|" + x.videoSize).hashCode());
        final String mode = getSubSync(c);

        // The file being played may be the library file: then its hash is known and so is its own timing.
        if (!mode.equals("off")) {
            x.local = localMatch(c, x);
            if (x.local != null && x.videoHash == null) x.videoHash = localHash(c, x.local);
        }

        final String key = getSubdlKey(c), wk = getWyzieKey(c), sk = getSubsourceKey(c), ok = getOscomKey(c);
        final String hash = x.videoHash;
        List<Callable<List<Sub>>> jobs = new ArrayList<>();
        if (isOsV3(c)) jobs.add(() -> addonSubs(OPENSUBS, type, id, extraRaw, 1, "os-", "OpenSubtitles", self));
        if (!key.isEmpty()) jobs.add(() -> subdlSubs(key, p[0], series, x.season, x.episode, wanted, self));
        if (!wk.isEmpty()) jobs.add(() -> wyzieSubs(wk, p[0], series, x.season, x.episode, wanted, self));
        if (!sk.isEmpty()) jobs.add(() -> subsourceSubs(sk, p[0], series, x.season, x.episode, wanted, self));
        if (!ok.isEmpty()) jobs.add(() -> oscomSubs(ok, p[0], series, x.season, x.episode, wanted, self, hash));
        int n = 0;
        for (String base : addonBases(getSubAddons(c))) {
            final String tag = "x" + n + "-";
            final String label = hostOf(base);
            n++;
            jobs.add(() -> addonSubs(base, type, id, extraRaw, 2, tag, label, self));
        }
        List<Future<List<Sub>>> futures = new ArrayList<>();
        for (Callable<List<Sub>> j : jobs) futures.add(fetchPool.submit(j));
        List<Sub> all = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 25000;
        for (Future<List<Sub>> f : futures) {
            try {
                all.addAll(f.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                f.cancel(true);
                Log.w(TAG, "subtitle source: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }

        final String hi = getSubHi(c);
        Set<String> seen = new HashSet<>();
        List<Sub> pool = new ArrayList<>();
        for (Sub sub : all) {
            if (sub.lang == null || sub.url == null || !seen.add(sub.url)) continue;
            if (hi.equals("exclude") && sub.hi) continue;
            if (sub.ref != null) sub.ref.sync = x;
            if (sub.trusted && sub.ref != null) x.hashRefs.add(sub.ref);
            sub.score = similarity(sub.name, x.filename);
            pool.add(sub);
        }
        // before checking: exact-file matches first, then the closest release names, hearing-impaired last if preferred
        final boolean preferHi = hi.equals("prefer");
        Collections.sort(pool, (u, w) -> {
            if (u.trusted != w.trusted) return u.trusted ? -1 : 1;
            if (u.score != w.score) return Integer.compare(w.score, u.score);
            if (preferHi && u.hi != w.hi) return u.hi ? -1 : 1;
            return Integer.compare(u.source, w.source);
        });

        List<String> groups = new ArrayList<>(wanted);
        if (groups.isEmpty()) for (Sub sub : pool) if (!groups.contains(sub.lang)) groups.add(sub.lang);
        int maxPerLang = getSubMax(c);

        // ---- download and check the best of each language, within a time limit
        int verifyN = getSubVerify(c);
        final boolean oscomOk = isVerifyOscom(c);
        List<Sub> toCheck = new ArrayList<>();
        if (verifyN > 0) {
            for (String g : groups) {
                // one from each provider in turn (each one's best first), so every source gets checked
                Map<String, List<Sub>> byProvider = new LinkedHashMap<>();
                for (Sub sub : pool) {
                    if (!g.equals(sub.lang) || sub.ref == null) continue;
                    if (sub.provider.equals("OpenSubtitles.com") && !sub.trusted && !oscomOk) continue;
                    List<Sub> q = byProvider.get(sub.provider);
                    if (q == null) { q = new ArrayList<>(); byProvider.put(sub.provider, q); }
                    q.add(sub);
                }
                int k = 0;
                for (int round = 0; k < verifyN; round++) {
                    boolean any = false;
                    for (List<Sub> q : byProvider.values()) {
                        if (round < q.size() && k < verifyN) { toCheck.add(q.get(round)); k++; any = true; }
                    }
                    if (!any) break;
                }
            }
        }
        List<Future<Verdict>> checks = new ArrayList<>();
        for (Sub sub : toCheck) checks.add(fetchPool.submit(() -> verify(c, x, sub, mode)));
        long vDeadline = System.currentTimeMillis() + getSubBudget(c) * 1000L;
        int done = 0;
        for (int i = 0; i < toCheck.size(); i++) {
            try {
                toCheck.get(i).verdict = checks.get(i).get(Math.max(1, vDeadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                done++;
            } catch (Throwable t) {
                // too slow: it is listed unchecked and keeps going, so the next request already has the answer
            }
        }
        // anything checked earlier (a previous request) counts even if it was not in this round
        for (Sub sub : pool) {
            if (sub.verdict == null && sub.ref != null) sub.verdict = verdicts.get(verdictKey(sub.ref, mode));
        }
        int minAcc = getSubMinAcc(c);
        List<Sub> kept = new ArrayList<>();
        for (Sub sub : pool) {
            Verdict v = sub.verdict;
            if (v != null && v.drop) continue;
            if (v != null && v.verified && !v.trusted && minAcc > 0 && v.accuracy >= 0 && v.accuracy < minAcc) continue;
            kept.add(sub);
        }
        // checked-and-fitting first, then unchecked, then checked-but-unconvincing; best accuracy first inside each
        Collections.sort(kept, (u, w) -> {
            int tu = tier(u), tw = tier(w);
            if (tu != tw) return Integer.compare(tu, tw);
            int au = accuracyOf(u, x.filename), aw = accuracyOf(w, x.filename);
            if (au != aw) return Integer.compare(aw, au);
            if (preferHi && u.hi != w.hi) return u.hi ? -1 : 1;
            return Integer.compare(u.source, w.source);
        });
        boolean titles = isSubTitles(c);
        for (String g : groups) {
            for (Sub sub : spread(kept, g, maxPerLang)) put(out, sub, titles, x.filename);
        }
        log(String.format(Locale.US, "subtitles %s  %d found, %d checked in %dms%s%s", id, pool.size(), done,
                System.currentTimeMillis() - (vDeadline - getSubBudget(c) * 1000L),
                x.local != null ? "  · library file recognised" : "", x.videoHash != null ? "  · hash " + x.videoHash.substring(0, 8) : ""));
        return new JSONObject().put("subtitles", out);
    }

    private static String hostOf(String base) {
        try { return new URL(base).getHost().replaceFirst("^www\\.", ""); } catch (Throwable t) { return "addon"; }
    }

    /** Candidates of one language in the given order, but no provider takes more than its share of the slots. */
    private static List<Sub> spread(List<Sub> ordered, String lang, int max) {
        List<Sub> ofLang = new ArrayList<>();
        Set<String> providers = new HashSet<>();
        for (Sub s : ordered) if (lang.equals(s.lang)) { ofLang.add(s); providers.add(s.provider); }
        int cap = Math.max(2, (int) Math.ceil(max / (double) Math.max(1, providers.size())) + 1);
        Map<String, Integer> used = new HashMap<>();
        List<Sub> out = new ArrayList<>(), skipped = new ArrayList<>();
        for (Sub s : ofLang) {
            if (out.size() >= max) break;
            int u = used.containsKey(s.provider) ? used.get(s.provider) : 0;
            if (u >= cap) { skipped.add(s); continue; }
            used.put(s.provider, u + 1);
            out.add(s);
        }
        for (Sub s : skipped) if (out.size() < max) out.add(s);
        return out;
    }

    /** 0 exact file / fitted, 1 not checked, 2 checked but not convincing. */
    private static int tier(Sub s) {
        Verdict v = s.verdict;
        if (v == null) return s.trusted ? 0 : 1;
        if (v.trusted) return 0;
        if (v.verified && (v.status.startsWith("✔"))) return 0;
        if (v.verified) return 2;
        return 1;
    }

    private static int accuracyOf(Sub s, String filename) {
        if (s.verdict != null && s.verdict.accuracy >= 0) return s.verdict.accuracy;
        int r = releasePct(s.name, filename);
        return r < 0 ? 0 : r / 2;   // a release-name guess counts for less than a measured fit
    }

    private static void put(JSONArray out, Sub s, boolean titles, String filename) {
        try {
            JSONObject o = new JSONObject().put("id", s.id).put("url", s.url).put("lang", s.lang);
            if (titles) {
                o.put("title", titleOf(s, filename));
                String f = (s.name == null || s.name.isEmpty() ? s.provider + "-" + s.lang : s.name).replaceAll("[^A-Za-z0-9._ \\-]", "_");
                o.put("file", f.endsWith(".srt") ? f : f + ".srt");
            }
            out.put(o);
        } catch (Throwable ignored) {}
    }

    /** Two lines, which players such as Nuvio show: how it fits, then where it comes from. */
    private static String titleOf(Sub s, String filename) {
        Verdict v = s.verdict;
        String line1;
        if (v != null && v.trusted) {
            line1 = "[✔ Exact file match (hash)]";
        } else if (v != null && !v.status.isEmpty()) {
            line1 = "[" + v.status + (v.accuracy >= 0 ? " | " + v.accuracy + "%" : "") + "]" + (v.detail.isEmpty() ? "" : " (" + v.detail + ")");
        } else {
            int r = releasePct(s.name, filename);
            line1 = r >= 0 ? "[≈ Release " + r + "% | not checked]" : "[Not checked]";
        }
        String name = s.name == null || s.name.isEmpty() ? "" : " " + s.name;
        return line1 + "\n[" + s.provider + (s.hi ? " · HI" : "") + "]" + name;
    }

    // ------------------------------------------------------------------ references, checking and sync

    private static String normName(String n) {
        if (n == null) return "";
        String t = n.replaceAll("(?i)\\s*\\[LAN\\]\\s*$", "").trim();
        int dot = t.lastIndexOf('.');
        if (dot > 0 && t.length() - dot <= 5) t = t.substring(0, dot);
        return t.toLowerCase(Locale.ROOT);
    }

    private static boolean sameFile(String played, String local) {
        String a = normName(played), b = normName(local);
        return !a.isEmpty() && (a.equals(b) || releasePct(a, b) >= 95);
    }

    /** The library file the player says it is playing, if the title is in Jellyfin; null when it cannot be told. */
    private LocalMatch localMatch(Context c, SyncCtx x) {
        if (x.filename == null && x.videoSize <= 0) return null;
        try {
            List<String> ids = imdbIndex(c).get(x.imdb);
            if (ids == null) return null;
            for (String itemId : ids) {
                String target = itemId;
                if (x.season >= 0) {
                    target = findEpisode(c, itemId, x.season, x.episode);
                    if (target == null) continue;
                }
                JSONObject it = new JSONObject(jfGet(c, "/Users/" + userId(c) + "/Items/" + enc(target)));
                JSONArray srcs = it.optJSONArray("MediaSources");
                for (int i = 0; srcs != null && i < srcs.length(); i++) {
                    JSONObject ms = srcs.getJSONObject(i);
                    String path = ms.optString("Path", "");
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    long size = ms.optLong("Size", 0);
                    if ((x.videoSize > 0 && size == x.videoSize) || (x.filename != null && sameFile(x.filename, name))) {
                        LocalMatch m = new LocalMatch();
                        m.itemId = target; m.msId = ms.optString("Id", target); m.name = name; m.size = size;
                        m.runtimeSec = it.optLong("RunTimeTicks", 0) / 1e7;
                        m.streams = ms.optJSONArray("MediaStreams");
                        return m;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "library match: " + t.getMessage());
        }
        return null;
    }

    /** The OpenSubtitles hash of the library file (its size plus its first and last 64 KB): two small range reads. */
    private String localHash(Context c, LocalMatch m) {
        if (m.size < 131072) return null;
        try {
            Map<String, String> h = new HashMap<>();
            h.put("X-Emby-Token", jfToken(c));
            String url = JF + "/Videos/" + m.itemId + "/stream?static=true&MediaSourceId=" + enc(m.msId);
            int[] st = new int[1];
            h.put("Range", "bytes=0-65535");
            byte[] head = httpBytes("GET", url, h, null, 70000, st);
            if ((st[0] != 206 && st[0] != 200) || head.length != 65536) return null;
            h.put("Range", "bytes=" + (m.size - 65536) + "-" + (m.size - 1));
            byte[] tail = httpBytes("GET", url, h, null, 70000, st);
            if ((st[0] != 206 && st[0] != 200) || tail.length != 65536) return null;
            return osHash(m.size, head, tail);
        } catch (Throwable t) {
            Log.w(TAG, "library hash: " + t.getMessage());
            return null;
        }
    }

    static String osHash(long size, byte[] head, byte[] tail) {
        return String.format(Locale.US, "%016x", size + sumLe64(head) + sumLe64(tail));
    }

    private static long sumLe64(byte[] b) {
        long sum = 0;
        for (int i = 0; i + 8 <= b.length; i += 8) {
            long v = 0;
            for (int k = 7; k >= 0; k--) v = (v << 8) | (b[i + k] & 0xFF);
            sum += v;
        }
        return sum;
    }

    private static RefInfo refInfo(String text, String label, double runtimeSec) {
        if (text == null) return null;
        List<SubtitleSync.Cue> cues = SubtitleSync.parse(text);
        if (cues.size() < SubtitleSync.MIN_CUES) return null;
        long end = 0;
        for (SubtitleSync.Cue c : cues) end = Math.max(end, c.end);
        if (runtimeSec > 600 && end / 1000.0 < runtimeSec * 0.5) return null; // forced / signs-only tracks
        RefInfo r = new RefInfo();
        r.cues = cues; r.label = label;
        return r;
    }

    private static RefInfo refIfReady(SyncCtx x) {
        synchronized (x) { return x.refDone ? x.ref : null; }
    }

    /**
     * Something that is known to fit the video, in order of trust: a subtitle that matched the file's hash, a text
     * track of the library file itself, a subtitle shipped inside the torrent. Null when there is none.
     */
    private RefInfo resolveRef(Context c, SyncCtx x) {
        synchronized (x) {
            if (x.refDone) return x.ref;
            RefInfo r = null;
            try {
                for (SubRef hr : new ArrayList<>(x.hashRefs)) {
                    if (r != null) break;
                    r = refInfo(fetchRaw(c, hr), "OpenSubtitles hash match", 0);
                }
                if (r == null && x.local != null) r = refFromLibrary(c, x.local);
                if (r == null) r = refFromTorrent(c, x);
            } catch (Throwable t) {
                Log.w(TAG, "reference: " + t.getMessage());
            }
            x.ref = r;
            x.refDone = true;
            log(r == null ? "sync reference: none for this file" : "sync reference: " + r.label + " (" + r.cues.size() + " lines)");
            return r;
        }
    }

    private RefInfo refFromLibrary(Context c, LocalMatch m) throws Exception {
        if (m.streams == null) return null;
        List<JSONObject> tracks = new ArrayList<>();
        for (int i = 0; i < m.streams.length(); i++) {
            JSONObject st = m.streams.optJSONObject(i);
            if (st == null || !"Subtitle".equals(st.optString("Type")) || st.optBoolean("IsForced") || !st.optBoolean("IsTextSubtitleStream", true)) continue;
            tracks.add(st);
        }
        Collections.sort(tracks, (a, b) -> {
            boolean ea = "eng".equals(canonLang(a.optString("Language", ""))), eb = "eng".equals(canonLang(b.optString("Language", "")));
            return ea == eb ? 0 : ea ? -1 : 1;
        });
        for (int i = 0; i < tracks.size() && i < 3; i++) {
            JSONObject st = tracks.get(i);
            SubRef r = new SubRef();
            r.kind = "jf";
            r.id = "/Videos/" + m.itemId + "/" + m.msId + "/Subtitles/" + st.optInt("Index") + "/0/Stream.srt";
            String lang = canonLang(st.optString("Language", ""));
            r.lang = lang == null ? "und" : lang;
            try {
                RefInfo ri = refInfo(fetchSubtitleText(c, r), "Jellyfin file (" + r.lang + " track)", m.runtimeSec);
                if (ri != null) return ri;
            } catch (Throwable t) {
                Log.w(TAG, "library track: " + t.getMessage());
            }
        }
        return null;
    }

    private RefInfo refFromTorrent(Context c, SyncCtx x) {
        if (x.filename == null) return null;
        TorrentRef best = null;
        int bestPct = 79;
        List<TorrentRef> all;
        synchronized (tokens) { all = new ArrayList<>(tokens.values()); }
        for (TorrentRef t : all) {
            if (t.resolvedHash == null || t.subFiles.isEmpty()) continue;
            int pct = releasePct(t.title, x.filename);
            if (pct > bestPct) { bestPct = pct; best = t; }
        }
        if (best == null) return null;
        for (int i = 0; i < best.subFiles.size() && i < 3; i++) {
            String[] f = best.subFiles.get(i);
            SubRef r = new SubRef();
            r.kind = "tor";
            r.id = "/stream/" + encPath(f[1].substring(f[1].lastIndexOf('/') + 1)) + "?link=" + best.resolvedHash + "&index=" + f[0] + "&play";
            r.lang = "und";
            try {
                RefInfo ri = refInfo(fetchSubtitleText(c, r), "subtitle inside the torrent", 0);
                if (ri != null) return ri;
            } catch (Throwable t) {
                Log.w(TAG, "torrent subtitle: " + t.getMessage());
            }
        }
        return null;
    }

    /** Downloads a candidate and checks it: really the right script, then its timing against the reference. */
    private Verdict verify(Context c, SyncCtx x, Sub s, String mode) {
        SubRef r = s.ref;
        String vk = verdictKey(r, mode);
        Verdict cached = verdicts.get(vk);
        if (cached != null) return cached;
        Verdict v = new Verdict();
        String text;
        try {
            text = fetchRaw(c, r);
        } catch (QuotaException q) {
            v.status = "≈ Not checked (daily download limit reached)";
            return v;
        } catch (Throwable t) {
            v.status = "≈ Could not download to check";
            log("subtitle " + s.provider + " " + r.lang + " could not be downloaded: " + t.getMessage());
            return v;
        }
        if (text == null) { v.drop = true; log("subtitle " + s.provider + " " + r.lang + " has no matching file"); verdicts.put(vk, v); return v; }
        String problem = scriptProblem(r.lang, text);
        if (problem != null) {
            v.drop = true; v.status = problem;
            log("subtitle dropped (" + s.provider + " " + r.lang + "): " + problem);
            verdicts.put(vk, v);
            return v;
        }
        if (SubtitleSync.parse(text).size() < 15) { v.drop = true; v.status = "too few lines"; verdicts.put(vk, v); return v; }
        v.text = text;
        if (s.trusted) {
            v.trusted = true; v.verified = true; v.accuracy = 100; v.status = "✔ Exact file match (hash)";
        } else if (mode.equals("off")) {
            v.status = "≈ Sync check is off";
            v.accuracy = Math.max(-1, releasePct(s.name, x.filename));
        } else {
            RefInfo ref = resolveRef(c, x);
            if (ref == null) {
                v.status = "≈ Not verified (nothing to compare with)";
                v.accuracy = Math.max(-1, releasePct(s.name, x.filename));
            } else {
                SubtitleSync.Result res = SubtitleSync.align(ref.cues, SubtitleSync.parse(text));
                log(String.format(Locale.US, "sync %s %s: %s  [vs %s]", s.provider, r.lang, res.note, ref.label));
                String n = res.note;
                v.accuracy = res.scoreAfter > 0 || res.scoreBefore > 0 ? (int) Math.round(Math.max(res.scoreAfter, res.scoreBefore) * 100) : -1;
                v.verified = true;
                if (res.applied) {
                    boolean parts = n.startsWith("applied in two parts");
                    v.detail = parts ? "fixed in two parts" : String.format(Locale.US, "%+.2fs%s", res.offsetSec,
                            Math.abs(res.ratio - 1) > 0.0004 ? String.format(Locale.US, " ×%.4f", res.ratio) : "");
                    if (mode.equals("check")) {
                        v.status = "≈ Out of sync with " + ref.label + ", not changed";
                    } else {
                        v.status = "✔ Synced to " + ref.label;
                        v.text = SubtitleSync.write(res.cues);
                    }
                } else if (n.startsWith("already in sync") || n.startsWith("no measurable gain")) {
                    v.status = "✔ In sync with " + ref.label;
                } else if (n.startsWith("timing differs")) {
                    v.status = "⚠ Different cut from " + ref.label + ", left as is";
                } else if (n.startsWith("covers a different part")) {
                    v.status = "⚠ Partial subtitle"; v.accuracy = -1;
                } else if (n.startsWith("too few cues")) {
                    v.status = "≈ Too few lines to check"; v.verified = false; v.accuracy = -1;
                } else {
                    v.status = "⚠ Does not match " + ref.label;
                }
            }
        }
        verdicts.put(vk, v);
        return v;
    }

    /** Percent of shared words between a subtitle's release name and the playing file, plus a bonus for the same group. */
    static int releasePct(String release, String filename) {
        if (release == null || filename == null || release.isEmpty() || filename.isEmpty()) return -1;
        String[] a = release.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"), b = filename.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        Set<String> sa = new HashSet<>(), sb = new HashSet<>();
        for (String t : a) if (t.length() >= 2 && !t.equals("srt") && !t.equals("mkv") && !t.equals("mp4")) sa.add(t);
        for (String t : b) if (t.length() >= 2 && !t.equals("srt") && !t.equals("mkv") && !t.equals("mp4")) sb.add(t);
        if (sa.isEmpty() || sb.isEmpty()) return -1;
        int inter = 0;
        for (String t : sa) if (sb.contains(t)) inter++;
        int pct = (int) Math.round(100.0 * inter / (sa.size() + sb.size() - inter));
        String ga = lastToken(a), gb = lastToken(b);
        if (!ga.isEmpty() && ga.equals(gb) && ga.length() >= 3) pct = Math.min(100, pct + 15);
        return pct;
    }

    /** null when the text is in the language it claims (or too short to tell); otherwise why it was dropped. */
    static String scriptProblem(String lang, String text) {
        String expect = scriptOf(lang);
        if (expect == null || text == null) return null;
        int[] counts = new int[8]; // latin, arabic, hebrew, cyrillic, greek, cjk, thai, indic
        int letters = 0, seen = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.contains("-->") || t.matches("\\d+")) continue;
            t = t.replaceAll("<[^>]*>|\\{[^}]*\\}", "");
            for (int i = 0; i < t.length() && seen < 80000; i++, seen++) {
                int cp = t.charAt(i);
                if (!Character.isLetter(cp)) continue;
                letters++;
                if (cp >= 0x0600 && cp <= 0x06FF || cp >= 0x0750 && cp <= 0x077F || cp >= 0xFB50 && cp <= 0xFDFF || cp >= 0xFE70 && cp <= 0xFEFF) counts[1]++;
                else if (cp >= 0x0590 && cp <= 0x05FF) counts[2]++;
                else if (cp >= 0x0400 && cp <= 0x04FF) counts[3]++;
                else if (cp >= 0x0370 && cp <= 0x03FF) counts[4]++;
                else if (cp >= 0x4E00 && cp <= 0x9FFF || cp >= 0x3040 && cp <= 0x30FF || cp >= 0xAC00 && cp <= 0xD7AF) counts[5]++;
                else if (cp >= 0x0E00 && cp <= 0x0E7F) counts[6]++;
                else if (cp >= 0x0900 && cp <= 0x09FF) counts[7]++;
                else if (cp < 0x250) counts[0]++;
            }
        }
        if (letters < 120) return null;
        int idx = "latin arabic hebrew cyrillic greek cjk thai indic".indexOf(expect);
        int which = idx < 0 ? 0 : java.util.Arrays.asList("latin", "arabic", "hebrew", "cyrillic", "greek", "cjk", "thai", "indic").indexOf(expect);
        if (counts[which] < letters * 0.5) return "✖ Not really " + expect + " text";
        return null;
    }

    private static String scriptOf(String lang) {
        if (lang == null) return null;
        switch (lang) {
            case "ara": case "per": case "urd": case "kur": return "arabic";
            case "heb": return "hebrew";
            case "rus": case "ukr": case "bul": return "cyrillic";
            case "ell": return "greek";
            case "chi": case "jpn": case "kor": return "cjk";
            case "tha": return "thai";
            case "hin": case "ben": return "indic";
            case "eng": case "fre": case "spa": case "ger": case "ita": case "por": case "pob": case "tur": case "pol": case "dut":
            case "swe": case "dan": case "nor": case "fin": case "hun": case "cze": case "rum": case "hrv": case "vie": case "ind":
                return "latin";
            default: return null;
        }
    }

    /** How many words the release name shares with the playing file, plus a bonus for the same release group. */
    static int similarity(String release, String filename) {
        if (release == null || filename == null || release.isEmpty() || filename.isEmpty()) return 0;
        String[] a = release.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        String[] b = filename.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        Set<String> inFile = new HashSet<>();
        for (String t : b) if (t.length() >= 2) inFile.add(t);
        int score = 0;
        Set<String> counted = new HashSet<>();
        for (String t : a) if (t.length() >= 2 && inFile.contains(t) && counted.add(t)) score++;
        String lastA = lastToken(a), lastB = lastToken(b);
        if (!lastA.isEmpty() && lastA.equals(lastB) && lastA.length() >= 3) score += 3;
        return score;
    }

    private static String lastToken(String[] t) {
        for (int i = t.length - 1; i >= 0; i--) {
            if (t[i].length() >= 2 && !t[i].equals("srt") && !t[i].equals("mkv") && !t[i].equals("mp4")) return t[i];
        }
        return "";
    }

    /** Any Stremio subtitle addon (OpenSubtitles V3 is one): its /subtitles answer, served through the bridge. */
    private List<Sub> addonSubs(String base, String type, String id, String extraRaw, int source, String tag, String provider, String self) {
        List<Sub> out = new ArrayList<>();
        String url = base + "/subtitles/" + type + "/" + id + (extraRaw.isEmpty() ? "" : "/" + extraRaw) + ".json";
        JSONObject d = fetchJson(url, 20000, SUBS_TTL_MS);
        JSONArray arr = d == null ? null : d.optJSONArray("subtitles");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url", "");
            String lang = canonLang(o.optString("lang", ""));
            if (!u.startsWith("https://") || lang == null) continue;
            SubRef r = new SubRef();
            r.kind = "url"; r.id = u; r.lang = lang;
            r.season = seasonOf(id); r.episode = episodeOf(id);
            String name = o.optString("subtitleFileName", o.optString("movieReleaseName", o.optString("title", "")));
            out.add(meta(makeSub(tag + o.optString("id", String.valueOf(i)), subLink(self, r), lang, name, source), provider, false, false, r));
        }
        return out;
    }

    private static int seasonOf(String id) { String[] p = id.split(":"); return p.length >= 3 ? parseInt(p[1]) : -1; }
    private static int episodeOf(String id) { String[] p = id.split(":"); return p.length >= 3 ? parseInt(p[2]) : -1; }

    private static final Pattern SUBDL_PATH = Pattern.compile("^/subtitle/(\\d{1,12}-\\d{1,12})\\.zip$");
    private static final String PROVIDER_UA = "Jellyarr v3.0";

    /** What a subtitle link served by the bridge stands for; the provider is asked only when the file is opened. */
    private static final class SubRef {
        String kind;   // subdl, subsource, wyzie, oscom
        String id;     // provider id (Wyzie: its download address)
        String lang;
        int season = -1, episode = -1;
        SyncCtx sync;
    }

    private final Map<String, SubRef> subRefs = Collections.synchronizedMap(
            new LinkedHashMap<String, SubRef>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SubRef> e) {
                    return size() > 600;
                }
            });

    private String subLink(String self, SubRef ref) {
        String t = randomHex(8);
        subRefs.put(t, ref);
        return self + "/sub/t/" + t + ".srt";
    }

    private static Sub meta(Sub s, String provider, boolean hi, boolean trusted, SubRef r) {
        s.provider = provider; s.hi = hi; s.trusted = trusted; s.ref = r;
        return s;
    }

    private static Sub makeSub(String id, String url, String lang, String name, int source) {
        Sub s = new Sub();
        s.id = id; s.url = url; s.lang = lang; s.name = name; s.source = source;
        return s;
    }

    private static List<String> codes(List<String> wanted, int column, boolean lower) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) {
            for (String[] row : LANGS) {
                if (row[0].equals(w)) {
                    String v = row[column];
                    if (w.equals("pob") && column == 2) v = "pt-br";
                    if (!v.isEmpty()) out.add(lower ? v.toLowerCase(Locale.ROOT) : v);
                }
            }
        }
        return out;
    }

    /** SubDL: searched with the user's own key. Its result urls carry that key; only the zip's id is kept. */
    private List<Sub> subdlSubs(String key, String imdb, boolean series, int season, int episode, List<String> wanted, String self) {
        List<Sub> out = new ArrayList<>();
        List<String> cs = codes(wanted, 3, false);
        if (!wanted.isEmpty() && cs.isEmpty()) return out;
        StringBuilder q = new StringBuilder(subdlApi).append("?api_key=").append(enc(key))
                .append("&imdb_id=").append(imdb).append("&subs_per_page=30&client=jellyarr");
        if (!cs.isEmpty()) q.append("&languages=").append(String.join(",", cs));
        if (series) q.append("&type=tv&season_number=").append(season).append("&episode_number=").append(episode);
        else q.append("&type=movie");
        JSONObject d = fetchJson(q.toString(), 20000, SUBS_TTL_MS);
        JSONArray arr = d == null || !d.optBoolean("status", false) ? null : d.optJSONArray("subtitles");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String path = o.optString("url", "");
            int qm = path.indexOf('?');
            if (qm >= 0) path = path.substring(0, qm);
            Matcher m = SUBDL_PATH.matcher(path);
            String lang = canonLang(o.optString("language", o.optString("lang", "")));
            if (!m.matches() || lang == null) continue;
            SubRef r = new SubRef();
            r.kind = "subdl"; r.id = m.group(1); r.lang = lang;
            if (series) { r.season = season; r.episode = episode; }
            out.add(meta(makeSub("subdl-" + m.group(1), subLink(self, r), lang, o.optString("release_name", o.optString("name", "")), 0), "SubDL", o.optBoolean("hi"), false, r));
        }
        return out;
    }

    /** Wyzie: results are direct UTF-8 .srt addresses; they are served through the bridge to keep its tokens private. */
    private List<Sub> wyzieSubs(String key, String imdb, boolean series, int season, int episode, List<String> wanted, String self) {
        List<Sub> out = new ArrayList<>();
        List<String> cs = codes(wanted, 2, true);
        if (!wanted.isEmpty() && cs.isEmpty()) return out;
        StringBuilder q = new StringBuilder(wyzieApi).append("/search?id=").append(imdb).append("&format=srt");
        if (series) q.append("&season=").append(season).append("&episode=").append(episode);
        if (!cs.isEmpty()) q.append("&language=").append(String.join(",", cs));
        q.append("&key=").append(enc(key));
        String body = fetchText(q.toString(), null, 20000, SUBS_TTL_MS);
        if (body == null) return out;
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length() && out.size() < 40; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optBoolean("machine") || o.optBoolean("ai")) continue;
                String url = o.optString("url", "");
                String lang = canonLang(o.optString("language", ""));
                if (!url.startsWith("https://") || lang == null) continue;
                SubRef r = new SubRef();
                r.kind = "wyzie"; r.id = url; r.lang = lang;
                out.add(meta(makeSub("wyzie-" + o.optString("id", String.valueOf(i)), subLink(self, r), lang,
                        o.optString("release", o.optString("fileName", "")), 0), "Wyzie", o.optBoolean("isHearingImpaired"), false, r));
            }
        } catch (Throwable t) {
            Log.w(TAG, "wyzie: " + t.getMessage());
        }
        return out;
    }

    /** SubSource: the title (and season) is found by IMDb id, then its subtitles are listed per language. */
    private List<Sub> subsourceSubs(String key, String imdb, boolean series, int season, int episode, List<String> wanted, String self) {
        List<Sub> out = new ArrayList<>();
        Map<String, String> h = new HashMap<>();
        h.put("X-API-Key", key);
        try {
            String mv = fetchText(subsourceApi + "/movies/search?searchType=imdb&imdb=" + imdb + (series ? "&season=" + season : ""), h, 15000, META_TTL_MS);
            if (mv == null) return out;
            JSONArray data = new JSONObject(mv).optJSONArray("data");
            long movieId = -1;
            for (int i = 0; data != null && i < data.length() && movieId < 0; i++) {
                JSONObject o = data.optJSONObject(i);
                if (o != null && (!series || o.isNull("season") || o.optInt("season", season) == season)) movieId = o.optLong("movieId", -1);
            }
            if (movieId < 0) return out;
            List<String> names = codes(wanted, 4, true);
            if (names.isEmpty()) names.add("");
            for (String lang : names) {
                String body = fetchText(subsourceApi + "/subtitles?movieId=" + movieId + (lang.isEmpty() ? "" : "&language=" + enc(lang)) + "&limit=30", h, 15000, SUBS_TTL_MS);
                JSONArray arr = body == null ? null : new JSONObject(body).optJSONArray("data");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    String l = o == null ? null : canonLang(o.optString("language", lang));
                    if (o == null || l == null || o.optBoolean("foreignParts")) continue;
                    JSONArray ri = o.optJSONArray("releaseInfo");
                    StringBuilder rel = new StringBuilder();
                    for (int k = 0; ri != null && k < ri.length() && k < 4; k++) rel.append(ri.optString(k)).append(' ');
                    SubRef r = new SubRef();
                    r.kind = "subsource"; r.id = String.valueOf(o.optLong("subtitleId")); r.lang = l;
                    if (series) { r.season = season; r.episode = episode; }
                    out.add(meta(makeSub("subsource-" + r.id, subLink(self, r), l, rel.toString().trim(), 0), "SubSource", o.optBoolean("hearingImpaired"), false, r));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "subsource: " + t.getMessage());
        }
        return out;
    }

    /** OpenSubtitles.com: its API wants the query parameters in alphabetical order, or it redirects. */
    private List<Sub> oscomSubs(String key, String imdb, boolean series, int season, int episode, List<String> wanted, String self, String hash) {
        List<Sub> out = new ArrayList<>();
        Map<String, String> h = new HashMap<>();
        h.put("Api-Key", key);
        h.put("User-Agent", PROVIDER_UA);
        java.util.TreeMap<String, String> q = new java.util.TreeMap<>();
        String digits = imdb.replaceFirst("^tt", "");
        if (series) {
            q.put("parent_imdb_id", digits); q.put("season_number", String.valueOf(season));
            q.put("episode_number", String.valueOf(episode)); q.put("type", "episode");
        } else {
            q.put("imdb_id", digits); q.put("type", "movie");
        }
        List<String> cs = codes(wanted, 2, false);
        if (!cs.isEmpty()) q.put("languages", String.join(",", cs).toLowerCase(Locale.ROOT));
        if (hash != null) q.put("moviehash", hash);
        StringBuilder url = new StringBuilder(oscomApi).append("/subtitles?");
        for (Map.Entry<String, String> e : q.entrySet()) url.append(e.getKey()).append('=').append(enc(e.getValue()).replace("%2C", ",")).append('&');
        url.setLength(url.length() - 1);
        String body = fetchText(url.toString(), h, 20000, SUBS_TTL_MS);
        if (body == null) return out;
        try {
            JSONArray arr = new JSONObject(body).optJSONArray("data");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject at = arr.getJSONObject(i).optJSONObject("attributes");
                if (at == null || at.optBoolean("machine_translated") || at.optBoolean("ai_translated") || at.optBoolean("foreign_parts_only")) continue;
                JSONArray files = at.optJSONArray("files");
                String lang = canonLang(at.optString("language", ""));
                if (files == null || files.length() == 0 || lang == null) continue;
                SubRef r = new SubRef();
                r.kind = "oscom"; r.id = String.valueOf(files.getJSONObject(0).optLong("file_id")); r.lang = lang;
                out.add(meta(makeSub("oscom-" + r.id, subLink(self, r), lang, at.optString("release", ""), 0), "OpenSubtitles.com",
                        at.optBoolean("hearing_impaired"), at.optBoolean("moviehash_match"), r));
            }
        } catch (Throwable t) {
            Log.w(TAG, "opensubtitles.com: " + t.getMessage());
        }
        return out;
    }

    private final Map<String, byte[]> srtCache = Collections.synchronizedMap(
            new LinkedHashMap<String, byte[]>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
                    return size() > 80;
                }
            });

    private static final Pattern SUB_TOKEN = Pattern.compile("^([0-9a-f]{16})\\.srt$");

    /** Opens a subtitle link: the checked (and synced) text when it exists, else the download, synced if possible. */
    private Resp subFile(Context c, String name) {
        Matcher m = SUB_TOKEN.matcher(name);
        if (!m.matches()) return Resp.text(404, "Not found");
        SubRef ref = subRefs.get(m.group(1));
        if (ref == null) return Resp.text(404, "This subtitle link expired. Open the title again.");
        String mode = getSubSync(c);
        String vk = verdictKey(ref, mode);
        byte[] body = srtCache.get(vk);
        if (body == null) {
            Verdict v = verdicts.get(vk);
            long t0 = System.currentTimeMillis();
            try {
                String text = v != null && v.text != null ? v.text : fetchRaw(c, ref);
                if (text == null) return Resp.text(404, "No matching subtitle file in this download.");
                if (v == null && ref.sync != null && mode.equals("auto")) {
                    RefInfo ri = refIfReady(ref.sync);   // never waits here: the check normally ran while the list was built
                    if (ri != null) {
                        SubtitleSync.Result res = SubtitleSync.align(ri.cues, SubtitleSync.parse(text));
                        log(String.format(Locale.US, "sync %s %s: %s  [vs %s]", ref.kind, ref.lang, res.note, ri.label));
                        if (res.applied) text = SubtitleSync.write(res.cues);
                    }
                }
                body = text.getBytes(StandardCharsets.UTF_8);
                srtCache.put(vk, body);
                log(String.format(Locale.US, "subtitle %s %s  %s  (%.1fs)", ref.kind, ref.lang, human(body.length), (System.currentTimeMillis() - t0) / 1000.0));
            } catch (QuotaException q) {
                log("subtitle " + ref.kind + ": " + q.getMessage());
                return Resp.text(429, q.getMessage());
            } catch (Throwable t) {
                Log.w(TAG, "subtitle " + ref.kind + ": " + t.getMessage());
                log("subtitle " + ref.kind + " failed: " + t.getMessage());
                return Resp.text(502, "Could not download the subtitle.");
            }
        }
        Resp r = new Resp();
        r.type = "application/x-subrip; charset=utf-8";
        r.body = body;
        r.maxAge = 3600;
        return r;
    }

    private static final class QuotaException extends Exception {
        QuotaException(String m) { super(m); }
    }

    private String fetchSubtitleText(Context c, SubRef ref) throws Exception {
        int[] st = new int[1];
        switch (ref.kind) {
            case "subdl": {
                byte[] zip = httpBytes("GET", subdlDl + "/subtitle/" + ref.id + ".zip", null, null, 6 * 1024 * 1024, st);
                if (st[0] != 200) throw new Exception("SubDL HTTP " + st[0]);
                return pickSrt(zip, ref.lang, ref.season, ref.episode);
            }
            case "subsource": {
                Map<String, String> h = new HashMap<>();
                h.put("X-API-Key", getSubsourceKey(c));
                byte[] zip = httpBytes("GET", subsourceApi + "/subtitles/" + ref.id + "/download", h, null, 10 * 1024 * 1024, st);
                if (st[0] != 200) throw new Exception("SubSource HTTP " + st[0]);
                return pickSrt(zip, ref.lang, ref.season, ref.episode);
            }
            case "wyzie": {
                URL u = new URL(ref.id);
                if (!u.getHost().equals(new URL(wyzieApi).getHost())) throw new Exception("unexpected Wyzie host");
                byte[] b = httpBytes("GET", ref.id, null, null, 3 * 1024 * 1024, st);
                if (st[0] != 200) throw new Exception("Wyzie HTTP " + st[0]);
                return decodeSubtitle(b, ref.lang);
            }
            case "oscom": {
                Map<String, String> h = new HashMap<>();
                h.put("Api-Key", getOscomKey(c));
                h.put("User-Agent", PROVIDER_UA);
                h.put("Content-Type", "application/json");
                h.put("Accept", "application/json");
                byte[] resp = httpBytes("POST", oscomApi + "/download", h, ("{\"file_id\":" + Long.parseLong(ref.id) + "}").getBytes(StandardCharsets.UTF_8), 64 * 1024, st);
                if (st[0] == 406 || st[0] == 429) throw new QuotaException("OpenSubtitles daily download limit reached.");
                if (st[0] != 200) throw new Exception("OpenSubtitles HTTP " + st[0]);
                JSONObject j = new JSONObject(new String(resp, StandardCharsets.UTF_8));
                String link = j.optString("link", "");
                URL lu = new URL(link);
                if (!lu.getProtocol().equals("https") || !lu.getHost().endsWith("opensubtitles.com")) throw new Exception("unexpected download host");
                byte[] b = httpBytes("GET", link, null, null, 3 * 1024 * 1024, st);
                if (st[0] != 200) throw new Exception("OpenSubtitles file HTTP " + st[0]);
                return decodeSubtitle(b, ref.lang);
            }
            case "url": {
                URL u = new URL(ref.id);
                if (!publicHttps(u)) throw new Exception("blocked address");
                byte[] b = httpBytes("GET", ref.id, null, null, 6 * 1024 * 1024, st);
                if (st[0] != 200) throw new Exception("HTTP " + st[0]);
                if (b.length > 4 && b[0] == 'P' && b[1] == 'K') return pickSrt(b, ref.lang, ref.season, ref.episode);
                return decodeSubtitle(b, ref.lang);
            }
            case "jf": { // a subtitle track of the library file (the reference), kept only for the sync check
                Map<String, String> h = new HashMap<>();
                h.put("X-Emby-Token", jfToken(c));
                byte[] b = httpBytes("GET", JF + ref.id, h, null, 6 * 1024 * 1024, st);
                if (st[0] != 200) throw new Exception("Jellyfin HTTP " + st[0]);
                return decodeSubtitle(b, ref.lang);
            }
            case "tor": { // a subtitle file shipped inside the torrent
                byte[] b = httpBytes("GET", TORRSERVER + ref.id, null, null, 3 * 1024 * 1024, st);
                if (st[0] != 200) throw new Exception("TorrServer HTTP " + st[0]);
                return decodeSubtitle(b, ref.lang);
            }
            default:
                return null;
        }
    }

    /** Only https addresses that are not on this device or its networks: the address came from a third party. */
    private static boolean publicHttps(URL u) {
        try {
            if (!u.getProtocol().equals("https") || u.getHost().equalsIgnoreCase("localhost")) return false;
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(u.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** One HTTP call returning the body (up to {@code max} bytes); the status goes to {@code status[0]}. */
    private static byte[] httpBytes(String method, String url, Map<String, String> headers, byte[] body, int max, int[] status) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", PROVIDER_UA);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
            }
            status[0] = conn.getResponseCode();
            InputStream in = status[0] >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return in == null ? new byte[0] : readAll(in, max);
        } finally {
            conn.disconnect();
        }
    }

    /** GET returning the body as text (redirects followed), cached; null when unreachable. */
    private String fetchText(String url, Map<String, String> headers, int readTimeoutMs, long ttlMs) {
        long now = System.currentTimeMillis();
        CachedJson hit = jsonCache.get("text|" + url);
        if (hit != null && now - hit.at < ttlMs) return hit.json.optString("t", null);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", PROVIDER_UA);
            conn.setRequestProperty("Accept", "application/json");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + redact(url));
                conn.disconnect();
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                String body = new String(readAll(in, 8 * 1024 * 1024), StandardCharsets.UTF_8);
                jsonCache.put("text|" + url, new CachedJson(now, new JSONObject().put("t", body)));
                return body;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetch " + redact(url) + ": " + t.getMessage());
            return null;
        }
    }

    /** The subtitle text inside a zip: the episode's file for a series, the main one for a movie; null when unsure. */
    static String pickSrt(byte[] zip, String lang, int season, int episode) throws Exception {
        List<String> names = new ArrayList<>();
        List<byte[]> data = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null && names.size() < 400) {
                if (e.isDirectory() || !e.getName().toLowerCase(Locale.ROOT).endsWith(".srt")) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = zin.read(buf)) != -1) {
                    b.write(buf, 0, n);
                    if (b.size() > 3 * 1024 * 1024) break;
                }
                names.add(e.getName());
                data.add(b.toByteArray());
            }
        }
        if (names.isEmpty()) return null;
        int pick = -1;
        if (season >= 0 && episode >= 0) {
            for (int i = 0; i < names.size(); i++) {
                if (fileMatchesEpisode(names.get(i), season, episode) && (pick < 0 || data.get(i).length > data.get(pick).length)) pick = i;
            }
            if (pick < 0 && names.size() == 1) pick = 0; // single file: the search already asked for this episode
        } else {
            for (int i = 0; i < names.size(); i++) {
                String low = names.get(i).toLowerCase(Locale.ROOT);
                if (low.contains("commentary") || low.contains("forced")) continue;
                if (pick < 0 || data.get(i).length > data.get(pick).length) pick = i;
            }
            if (pick < 0) pick = 0;
        }
        return pick < 0 ? null : decodeSubtitle(data.get(pick), lang);
    }

    /** UTF-8 when the bytes are valid UTF-8, otherwise the legacy character set of the language. */
    static String decodeSubtitle(byte[] b, String lang) {
        int off = (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b, off, b.length - off)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            try {
                return new String(b, off, b.length - off, java.nio.charset.Charset.forName(legacyCharset(lang)));
            } catch (Throwable t) {
                return new String(b, off, b.length - off, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private void addLocalSubtitles(Context c, JSONArray out, String type, String id, String self) throws Exception {
        List<String> itemIds = new ArrayList<>();
        if (id.startsWith("jellyarr:")) {
            itemIds.add(id.substring("jellyarr:".length()));
        } else {
            String[] p = id.split(":");
            int season = p.length >= 3 ? parseInt(p[1]) : -1;
            int episode = p.length >= 3 ? parseInt(p[2]) : -1;
            List<String> ids = imdbIndex(c).get(p[0]);
            if (ids == null) return;
            for (String itemId : ids) {
                if ("series".equals(type) && season >= 0) {
                    String ep = findEpisode(c, itemId, season, episode);
                    if (ep != null) itemIds.add(ep);
                } else if (!"series".equals(type)) {
                    itemIds.add(itemId);
                }
            }
        }
        for (String itemId : itemIds) {
            JSONObject it = new JSONObject(jfGet(c, "/Users/" + userId(c) + "/Items/" + enc(itemId)));
            JSONArray sources = it.optJSONArray("MediaSources");
            if (sources == null) continue;
            for (int i = 0; i < sources.length(); i++) {
                JSONObject ms = sources.getJSONObject(i);
                collectExternalSubs(ms, itemId, ms.optString("Id", itemId), self, out);
            }
        }
    }

    /** External (sidecar) subtitle streams of a Jellyfin media source, as Stremio subtitle objects. */
    private static void collectExternalSubs(JSONObject ms, String itemId, String msId, String self,
                                            JSONArray subs) throws Exception {
        JSONArray streamsArr = ms.optJSONArray("MediaStreams");
        if (streamsArr == null) return;
        for (int k = 0; k < streamsArr.length(); k++) {
            JSONObject st = streamsArr.getJSONObject(k);
            if (!"Subtitle".equals(st.optString("Type")) || !st.optBoolean("IsExternal")
                    || !st.optBoolean("SupportsExternalStream", true)) continue;
            int idx = st.optInt("Index");
            String codec = st.optString("Codec", "srt").toLowerCase(Locale.ROOT);
            String fmt = codec.contains("ass") || codec.contains("ssa") ? "ass" : codec.contains("vtt") ? "vtt" : "srt";
            subs.put(new JSONObject()
                    .put("id", "jellyfin-" + msId + "-" + idx)
                    .put("url", self + "/jf/sub/" + itemId + "/" + enc(msId) + "/" + idx + "." + fmt)
                    .put("lang", st.optString("Language", "und")));
        }
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

    private void safeAddLocal(Context c, JSONArray out, String itemId, String self) {
        try {
            addLocal(c, out, itemId, self);
        } catch (Throwable t) {
            Log.w(TAG, "local item " + itemId + ": " + t.getMessage());
        }
    }

    private void addLocal(Context c, JSONArray out, String itemId, String self) throws Exception {
        JSONObject it = new JSONObject(jfGet(c, "/Users/" + userId(c) + "/Items/" + enc(itemId)));
        JSONArray sources = it.optJSONArray("MediaSources");
        if (sources == null) return;
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
                }
            }
            String lan = LAN_SELF.get();
            List<String> bases = new ArrayList<>();
            if (lan != null) bases.add(lan);
            bases.add(self);
            for (String base : bases) {
                boolean lanVariant = lan != null && base.equals(lan);
                JSONArray vsubs = new JSONArray();
                collectExternalSubs(ms, itemId, msId, base, vsubs);
                JSONObject s = new JSONObject();
                s.put("name", "Jellyarr\n📁 " + (res.isEmpty() ? "Local" : res) + (lan == null ? "" : lanVariant ? " · 🏠 LAN" : " · 🌐"));
                String desc = "🏠 Jellyfin library\n📄 " + file + (size > 0 ? "\n💾 " + human(size) : "");
                s.put("title", desc);
                s.put("description", desc);
                s.put("url", base + "/jf/video/" + itemId + "/" + enc(msId));
                if (vsubs.length() > 0) s.put("subtitles", vsubs);
                JSONObject hints = new JSONObject();
                hints.put("bingeGroup", "jellyarr-local");
                hints.put("filename", lanVariant ? file + " [LAN]" : file);
                if (size > 0) hints.put("videoSize", size);
                s.put("behaviorHints", hints);
                out.put(s);
            }
        }
    }

    private void addTorrents(Context c, JSONArray out, String type, String imdb, int season, int episode,
                             String self) throws Exception {
        MediaServiceManager mgr = MediaServiceManager.getInstance(c);
        if (mgr == null || !mgr.isServiceRunning("prowlarr")) return;
        String key = mgr.getServiceApiKey("prowlarr");
        if (key == null || key.isEmpty()) return;
        boolean series = "series".equals(type) && season >= 0;

        // The searches run in parallel and are cached. Within the budget we return what has arrived; the slower
        // ones keep running and fill the cache, so the next request for this title is instant and complete.
        final long deadline = System.currentTimeMillis() + prowlarrBudgetMs;
        final String fkey = key;
        List<Future<List<JSONObject>>> searches = new ArrayList<>();
        if (series) {
            final String epQ = "{ImdbId:" + imdb + "}{Season:" + season + "}{Episode:" + episode + "}";
            final String seQ = "{ImdbId:" + imdb + "}{Season:" + season + "}";
            searches.add(fetchPool.submit(() -> prowlarrCached(fkey, "tvsearch", epQ, "5000")));
            searches.add(fetchPool.submit(() -> prowlarrCached(fkey, "tvsearch", seQ, "5000")));
        } else {
            final String mvQ = "{ImdbId:" + imdb + "}";
            searches.add(fetchPool.submit(() -> prowlarrCached(fkey, "movie", mvQ, "2000")));
        }
        List<JSONObject> results = new ArrayList<>();
        for (Future<List<JSONObject>> f : searches) results.addAll(await(f, deadline));
        if (results.isEmpty() && deadline - System.currentTimeMillis() > 2000) {
            JSONObject cm = cinemeta(series ? "series" : "movie", imdb);
            String name = cm == null ? "" : cm.optString("name", "");
            if (!name.isEmpty()) {
                final String q = series ? name + String.format(Locale.US, " S%02dE%02d", season, episode) : name + yearOf(cm);
                final String cats = series ? "5000" : "2000";
                results.addAll(await(fetchPool.submit(() -> prowlarrCached(fkey, "search", q, cats)), deadline));
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
            // Over the home network first (no loop out to Cloudflare and back), then the public address.
            String lan = i < LAN_TORRENTS ? LAN_SELF.get() : null;
            List<String> bases = new ArrayList<>();
            if (lan != null) bases.add(lan);
            bases.add(self);
            for (String base : bases) {
                boolean lanVariant = lan != null && base.equals(lan);
                JSONObject s = new JSONObject();
                s.put("name", "Jellyarr\n🧲 " + quality + (lanVariant ? " · 🏠 LAN" : ""));
                s.put("title", desc);
                s.put("description", desc);
                s.put("url", base + "/play/" + tokenId);
                JSONObject hints = new JSONObject();
                hints.put("bingeGroup", "jellyarr-torrent-" + quality);
                hints.put("notWebReady", true);
                if (size > 0) hints.put("videoSize", size);
                hints.put("filename", lanVariant ? ref.title + " [LAN]" : ref.title);
                s.put("behaviorHints", hints);
                out.put(s);
            }
        }
    }

    /** A finished search's result, or empty when it is still running at the deadline (it carries on in the background). */
    private static List<JSONObject> await(Future<List<JSONObject>> f, long deadline) {
        try {
            return f.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Log.w(TAG, "Prowlarr is still searching; answering without it");
            return new ArrayList<>();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private List<JSONObject> prowlarrCached(String key, String type, String query, String cats) {
        String ck = "prowlarr|" + type + "|" + query + "|" + cats;
        CachedJson hit = jsonCache.get(ck);
        if (hit != null && System.currentTimeMillis() - hit.at < PROWLARR_TTL_MS) return fromCache(hit.json);
        long t0 = System.currentTimeMillis();
        List<JSONObject> found = prowlarrSearch(key, type, query, cats);
        JSONArray slim = new JSONArray();
        for (JSONObject r : found) {
            JSONObject o = new JSONObject();
            for (String k : new String[]{"protocol", "title", "size", "seeders", "indexer", "infoHash", "magnetUrl", "downloadUrl"}) {
                if (r.has(k)) try { o.put(k, r.get(k)); } catch (Throwable ignored) {}
            }
            slim.put(o);
        }
        log(String.format(Locale.US, "Prowlarr %s  %d results (%.1fs)", type, found.size(), (System.currentTimeMillis() - t0) / 1000.0));
        if (slim.length() > 0) {
            try { jsonCache.put(ck, new CachedJson(System.currentTimeMillis(), new JSONObject().put("r", slim))); } catch (Throwable ignored) {}
        }
        try {
            return fromCache(new JSONObject().put("r", slim));
        } catch (Throwable t) {
            return found;
        }
    }

    private static List<JSONObject> fromCache(JSONObject o) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("r");
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null) out.add(x); }
        return out;
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
        JSONObject d = fetchJson(CINEMETA + "/meta/" + type + "/" + imdb + ".json", 12000, META_TTL_MS);
        return d == null ? null : d.optJSONObject("meta");
    }

    private static String redact(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?...";
    }

    /** GET a JSON document (redirects followed), cached for {@code ttlMs}; null when unreachable. */
    private JSONObject fetchJson(String url, int readTimeoutMs, long ttlMs) {
        long now = System.currentTimeMillis();
        CachedJson hit = jsonCache.get(url);
        if (hit != null && now - hit.at < ttlMs) return hit.json;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", "Jellyarr-Bridge/1.1");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + redact(url));
                conn.disconnect();
                return hit != null ? hit.json : null;
            }
            try (InputStream in = conn.getInputStream()) {
                JSONObject o = new JSONObject(new String(readAll(in, 8 * 1024 * 1024), StandardCharsets.UTF_8));
                jsonCache.put(url, new CachedJson(now, o));
                return o;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetch " + redact(url) + ": " + t.getMessage());
            return hit != null ? hit.json : null;
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

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 48 ? s.substring(0, 45) + "..." : s;
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

    private Resp play(Context c, String tokenId) throws Exception {
        TorrentRef ref = tokens.get(tokenId);
        if (ref == null) return Resp.text(404, "This link expired. Open the title again to refresh the list.");
        MediaServiceManager mgr = MediaServiceManager.getInstance(c);
        if (mgr == null || !mgr.isServiceRunning("torrserver")) {
            return Resp.text(503, "TorrServer is not running. Start it in Jellyarr.");
        }

        // Players open the same link several times at once (probe, play, seeks): resolve it once, the rest wait.
        synchronized (ref) {
        if (ref.resolvedHash == null) {
            final long tr = System.currentTimeMillis();
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
                if (path.toLowerCase(Locale.ROOT).endsWith(".srt") && len > 15000 && len < 3L * 1024 * 1024) {
                    ref.subFiles.add(new String[]{String.valueOf(f.optInt("id")), path});
                    continue;
                }
                if (!VIDEO_EXT.matcher(path).find()) continue;
                if (path.toLowerCase(Locale.ROOT).contains("sample") && len < 300L * 1024 * 1024) continue;
                if (len > bestLen) { bestLen = len; bestId = f.optInt("id"); bestPath = path; }
                if (ref.season >= 0 && fileMatchesEpisode(path, ref.season, ref.episode) && len > epLen) {
                    epLen = len; epId = f.optInt("id"); epPath = path;
                }
            }
            if (ref.season >= 0 && epId >= 0) { bestId = epId; bestPath = epPath; }
            if (bestId < 0) return Resp.text(502, "No video file in this torrent.");
            log(String.format(Locale.US, "torrent opened in TorrServer: %s  (%.1fs to get the file list)%s", abbreviate(ref.title),
                    (System.currentTimeMillis() - tr) / 1000.0, peerLine(status)));
            ref.resolvedHash = hash;
            ref.resolvedIndex = bestId;
            ref.resolvedName = bestPath.substring(bestPath.lastIndexOf('/') + 1);
        }
        }
        String to = TORRSERVER + "/stream/" + encPath(ref.resolvedName)
                + "?link=" + ref.resolvedHash + "&index=" + ref.resolvedIndex + "&play";
        return Resp.proxy(to, null, 0);
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

    /** " · peers 3/20, seeders 2, 310 KB/s" from a TorrServer status; empty when it has no such fields. */
    private static String peerLine(JSONObject st) {
        if (st == null || !st.has("total_peers") && !st.has("active_peers")) return "";
        return String.format(Locale.US, "  · peers %d/%d, seeders %d, %s/s", st.optInt("active_peers"), st.optInt("total_peers"),
                st.optInt("connected_seeders"), human((long) st.optDouble("download_speed", 0)));
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
