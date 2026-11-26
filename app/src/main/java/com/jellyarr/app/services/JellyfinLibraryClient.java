package com.jellyarr.app.services;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.jellyarr.app.JellyfinAccountManager;
import com.jellyarr.app.UnifiedAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Minimal Jellyfin REST client used by the Library tab and the auto-delete engine.
 *
 * <p>Authentication, in order: the cached token (re-validated once per process), the app's unified account
 * ({@link UnifiedAccount}), an API key created in jellyfin.db, then AuthenticateByName with passwords the
 * app already knows. A request answered with HTTP 401 drops the token and signs in again once.
 * Credentials that Jellyfin refused are not retried automatically, so the account is never locked out by
 * repeated failed logins.
 */
public final class JellyfinLibraryClient {

    private static final String TAG = "JellyfinLibraryClient";
    private static final String BASE = "http://127.0.0.1:8096";
    private static final String KEY_NAME = "FishbowlLibrary";
    private static final String PREFS = "jellyfin_library_client";
    private static final String CLIENT = "Client=\"Jellyarr\", Device=\"Android\", DeviceId=\"fishbowl-library\", Version=\"1.0\"";
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;

    public static final class LibItem {
        public String id;
        public String type;       // Movie, Series, Season, Episode
        public String name;
        public String seriesName;
        public String seriesId;
        public int seasonNumber = -1;
        public int episodeNumber = -1;
        public int year;
        public int childCount;
        public boolean played;
        public int playCount;
        public long lastPlayedMs;
        public String playedBy;
        /** ImageTags.Primary of the item (null when it has no primary image). */
        public String imageTag;
        /** SeriesPrimaryImageTag for episodes / seasons. */
        public String seriesImageTag;

        public String displayTitle() {
            if ("Episode".equals(type)) {
                String s = seriesName != null ? seriesName : "";
                String code = "";
                if (seasonNumber >= 0 && episodeNumber >= 0) {
                    code = String.format(Locale.US, " S%02dE%02d", seasonNumber, episodeNumber);
                }
                return (s + code + " - " + name).trim();
            }
            if ("Movie".equals(type) && year > 0) return name + " (" + year + ")";
            return name;
        }
    }

    public static class ApiException extends Exception {
        /** HTTP status of the failed request, 0 when not an HTTP error. */
        public final int status;

        public ApiException(String m) {
            this(m, 0);
        }

        public ApiException(String m, int status) {
            super(m);
            this.status = status;
        }
    }

    /** No usable sign-in: the UI should offer a sign-in form. */
    public static final class AuthException extends ApiException {
        public AuthException(String m) { super(m, 401); }
    }

    /** Jellyfin is not listening on port 8096. */
    public static final class UnreachableException extends ApiException {
        public UnreachableException(String m) { super(m, 0); }
    }

    private JellyfinLibraryClient() {}

    private static final class Login {
        final String token;
        final String userId;
        final String userName;

        Login(String token, String userId, String userName) {
            this.token = token;
            this.userId = userId;
            this.userName = userName;
        }
    }

    private static volatile String token;
    private static volatile boolean tokenTrusted;
    private static volatile String tokenUserId;
    private static volatile String tokenUserName;
    private static volatile String failedUnifiedFp;
    private static volatile int lastAuthCode;

    // ------------------------------------------------------------------ authentication

    private static final StringBuilder diag = new StringBuilder();

    private static void d(String line) {
        Log.w(TAG, line);
        synchronized (diag) {
            if (diag.length() < 1200) diag.append("\n- ").append(line);
        }
    }

    private static UnreachableException unreachable() {
        return new UnreachableException("Jellyfin is not running");
    }

    /**
     * Returns a working token, signing in when needed. Throws {@link UnreachableException} when Jellyfin is
     * not running and {@link AuthException} when no automatic sign-in worked.
     */
    public static synchronized String getToken(Context ctx) throws ApiException {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (token == null) {
            token = LibraryPrefs.getToken(app);
            tokenTrusted = false;
            restoreIdentity(app, token);
        }
        if (token != null && tokenTrusted) return token;
        synchronized (diag) { diag.setLength(0); }
        if (token != null) {
            int code = check(token);
            if (code == 200) {
                tokenTrusted = true;
                return token;
            }
            if (code < 0) throw unreachable();
            d("Saved sign-in was rejected by Jellyfin (HTTP " + code + ")");
            forgetToken(app);
        } else if (!isReachable()) {
            throw unreachable();
        }

        Login l = tryUnified(app);
        if (l != null) {
            remember(app, l);
            return l.token;
        }
        String t = createApiKeyInDb(app);
        if (t != null) {
            int code = check(t);
            if (code == 200) {
                remember(app, new Login(t, null, null));
                return t;
            }
            d("API key in jellyfin.db was not accepted (HTTP " + code + ")");
        }
        l = authenticateWithStoredPassword(app);
        if (l != null) {
            remember(app, l);
            return l.token;
        }
        String detail;
        synchronized (diag) { detail = diag.toString(); }
        throw new AuthException("Could not sign in to Jellyfin automatically." + detail);
    }

    /**
     * Signs in with the given credentials, remembers the password and caches the token.
     * Returns null on success, otherwise a readable error.
     */
    public static synchronized String loginAndStore(Context ctx, String username, String password) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        synchronized (diag) { diag.setLength(0); }
        String user = username == null ? "" : username.trim();
        if (user.isEmpty()) return "Enter a username.";
        if (!isReachable()) return "Jellyfin is not running. Start it from the Services tab first.";
        boolean unified = isUnifiedCredential(app, user, password);
        Login l = authenticate(user, password);
        if (l == null) {
            if (unified) failedUnifiedFp = fingerprint(user, password);
            if (lastAuthCode == 401) return "Jellyfin did not accept this username or password.";
            if (lastAuthCode > 0) return "Sign-in failed (HTTP " + lastAuthCode + ").";
            return "Sign-in failed. Is Jellyfin running?";
        }
        if (unified) failedUnifiedFp = null;
        remember(app, l);
        if (password != null && !password.isEmpty()) {
            try { JellyfinAccountManager.storeKnownPassword(app, user, password); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** True when the app account (unified username / password) was already refused by Jellyfin. */
    public static boolean hasUnifiedLoginFailed(Context ctx) {
        try {
            if (!UnifiedAccount.isConfigured(ctx)) return false;
            String fp = fingerprint(UnifiedAccount.getUsername(ctx).trim(), UnifiedAccount.getPassword(ctx));
            return fp.equals(failedUnifiedFp);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Name of the Jellyfin user the current token belongs to (null for API keys / unknown). */
    public static String currentUserName() {
        return tokenUserName;
    }

    public static String suggestedUsername(Context ctx) {
        try {
            List<JellyfinAccountManager.JellyfinUser> us = users(ctx);
            for (JellyfinAccountManager.JellyfinUser u : us) if (u.isAdmin) return u.username;
            if (!us.isEmpty()) return us.get(0).username;
        } catch (Throwable ignored) {}
        return "";
    }

    /** True when Jellyfin answers /System/Info/Public (no sign-in needed). */
    public static boolean isReachable() {
        HttpURLConnection c = null;
        try {
            c = open("/System/Info/Public", "GET", null);
            c.setConnectTimeout(2000);
            c.setReadTimeout(5000);
            return c.getResponseCode() < 500;
        } catch (Throwable e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** HTTP status of GET /System/Info with the token (any signed-in user or API key), -1 when unreachable. */
    private static int check(String t) {
        HttpURLConnection c = null;
        try {
            c = open("/System/Info", "GET", t);
            return c.getResponseCode();
        } catch (Throwable e) {
            d("Jellyfin check failed: " + e.getClass().getSimpleName());
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String fingerprint(String user, String pass) {
        return (user == null ? "" : user.toLowerCase(Locale.ROOT)) + ":" + Integer.toHexString(pass == null ? 0 : pass.hashCode());
    }

    private static boolean isUnifiedCredential(Context app, String user, String pass) {
        try {
            return UnifiedAccount.isConfigured(app)
                    && UnifiedAccount.getUsername(app).trim().equalsIgnoreCase(user)
                    && UnifiedAccount.getPassword(app).equals(pass == null ? "" : pass);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Login tryUnified(Context app) {
        try {
            if (!UnifiedAccount.isConfigured(app)) return null;
            String u = UnifiedAccount.getUsername(app).trim();
            String p = UnifiedAccount.getPassword(app);
            String fp = fingerprint(u, p);
            if (fp.equals(failedUnifiedFp)) {
                d("App account '" + u + "' was refused before; not retrying automatically");
                return null;
            }
            Login l = authenticate(u, p);
            if (l == null) {
                failedUnifiedFp = fp;
                return null;
            }
            failedUnifiedFp = null;
            try { JellyfinAccountManager.storeKnownPassword(app, u, p); } catch (Throwable ignored) {}
            return l;
        } catch (Throwable t) {
            d("App account sign-in: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void remember(Context app, Login l) {
        token = l.token;
        tokenTrusted = true;
        tokenUserId = l.userId;
        tokenUserName = l.userName;
        LibraryPrefs.setToken(app, l.token);
        SharedPreferences.Editor e = prefs(app).edit().putString("token_fp", Integer.toHexString(l.token.hashCode()));
        if (l.userId != null) e.putString("user_id", l.userId); else e.remove("user_id");
        if (l.userName != null) e.putString("user_name", l.userName); else e.remove("user_name");
        e.apply();
    }

    private static void restoreIdentity(Context app, String t) {
        tokenUserId = null;
        tokenUserName = null;
        if (t == null) return;
        SharedPreferences sp = prefs(app);
        if (!Integer.toHexString(t.hashCode()).equals(sp.getString("token_fp", ""))) return;
        tokenUserId = sp.getString("user_id", null);
        tokenUserName = sp.getString("user_name", null);
    }

    private static void forgetToken(Context app) {
        token = null;
        tokenTrusted = false;
        tokenUserId = null;
        tokenUserName = null;
        LibraryPrefs.setToken(app, null);
        prefs(app).edit().clear().apply();
    }

    /** Drops {@code used} after a 401, unless another thread already replaced it. */
    private static synchronized void invalidate(Context ctx, String used) {
        if (used != null && used.equals(token)) {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            forgetToken(app);
        }
    }

    private static String createApiKeyInDb(Context ctx) {
        SQLiteDatabase db = null;
        try {
            File dbFile = JellyfinAccountManager.getJellyfinDatabaseFile();
            if (!dbFile.exists()) {
                d("jellyfin.db not found at " + dbFile.getAbsolutePath());
                return null;
            }
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            String existing = null;
            try (android.database.Cursor cur = db.rawQuery("SELECT AccessToken FROM ApiKeys WHERE Name = ?", new String[]{KEY_NAME})) {
                if (cur != null && cur.moveToFirst()) existing = cur.getString(0);
            }
            if (existing != null && !existing.isEmpty()) return existing;

            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format(Locale.US, "%02x", b));
            String key = sb.toString();

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.0000000", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String now = fmt.format(new Date());
            ContentValues cv = new ContentValues();
            cv.put("DateCreated", now);
            cv.put("DateLastActivity", now);
            cv.put("Name", KEY_NAME);
            cv.put("AccessToken", key);
            long row = db.insert("ApiKeys", null, cv);
            if (row < 0) d("ApiKeys insert returned " + row);
            return row >= 0 ? key : null;
        } catch (Throwable t) {
            d("API key in jellyfin.db: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static Login authenticateWithStoredPassword(Context ctx) {
        try {
            List<JellyfinAccountManager.JellyfinUser> users = JellyfinAccountManager.getRegisteredUsers(ctx);
            if (users.isEmpty()) {
                d("No Jellyfin users could be read from jellyfin.db");
                return null;
            }
            // Administrators first, then everybody else (admin detection can fail on some schemas).
            for (int pass = 0; pass < 2; pass++) {
                for (JellyfinAccountManager.JellyfinUser u : users) {
                    if ((pass == 0) != u.isAdmin) continue;
                    String pw;
                    if (!u.hasPassword) {
                        pw = "";
                    } else if (u.knownPlaintextPassword != null && !u.knownPlaintextPassword.isEmpty()) {
                        // Already verified against the PBKDF2 hash in jellyfin.db, so this login will not fail.
                        pw = u.knownPlaintextPassword;
                    } else {
                        // Never guess: a failed login counts towards Jellyfin's lockout.
                        d("User '" + u.username + "' has a password the app does not know yet");
                        continue;
                    }
                    Login l = authenticate(u.username, pw);
                    if (l != null) return l;
                }
            }
        } catch (Throwable t) {
            d("Stored-password login: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        return null;
    }

    /** POST /Users/AuthenticateByName {Username, Pw}; returns token + user, or null (lastAuthCode set). */
    private static Login authenticate(String username, String pw) {
        lastAuthCode = 0;
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("Username", username);
            body.put("Pw", pw == null ? "" : pw);
            c = (HttpURLConnection) new URL(BASE + "/Users/AuthenticateByName").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            String authHeader = "MediaBrowser " + CLIENT;
            c.setRequestProperty("Authorization", authHeader);
            c.setRequestProperty("X-Emby-Authorization", authHeader);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            lastAuthCode = code;
            if (code == 200) {
                JSONObject res = new JSONObject(readAll(c.getInputStream()));
                String t = res.optString("AccessToken", "");
                if (t.isEmpty()) return null;
                JSONObject u = res.optJSONObject("User");
                return new Login(t, u == null ? null : u.optString("Id", null), u == null ? username : u.optString("Name", username));
            }
            d("Sign-in as '" + username + "': HTTP " + code);
        } catch (Throwable t) {
            d("Sign-in as '" + username + "': " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
    }

    // ------------------------------------------------------------------ HTTP helpers

    private static HttpURLConnection open(String pathAndQuery, String method, String t) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + pathAndQuery).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(30000);
        if (t != null) {
            c.setRequestProperty("Authorization", "MediaBrowser " + CLIENT + ", Token=\"" + t + "\"");
            c.setRequestProperty("X-Emby-Token", t);
        }
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (Reader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = rd.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static ApiException httpError(int code, String method) {
        if (code == 403) {
            return new ApiException("DELETE".equals(method)
                    ? "This Jellyfin account is not allowed to delete media (HTTP 403)."
                    : "Jellyfin refused access (HTTP 403). Sign in with an administrator account.", code);
        }
        if (code == 404) return new ApiException("Not found in Jellyfin (HTTP 404). Refresh the list.", code);
        if (code >= 500) return new ApiException("Jellyfin server error (HTTP " + code + ").", code);
        return new ApiException("Jellyfin returned HTTP " + code, code);
    }

    /** Authenticated request; on 401 the token is dropped and the request retried once with a fresh sign-in. */
    private static String request(Context ctx, String method, String pathAndQuery) throws ApiException {
        return request(ctx, method, pathAndQuery, null);
    }

    /** Same as {@link #request(Context, String, String)} with an optional JSON body. */
    private static String request(Context ctx, String method, String pathAndQuery, String jsonBody) throws ApiException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String t = getToken(ctx);
            HttpURLConnection c = null;
            try {
                c = open(pathAndQuery, method, t);
                if (jsonBody != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (java.io.OutputStream os = c.getOutputStream()) {
                        os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                if (code == 401) {
                    invalidate(ctx, t);
                    if (attempt == 0) continue;
                    throw new AuthException("Jellyfin rejected the sign-in (HTTP 401). Sign in again.");
                }
                if (code >= 400) throw httpError(code, method);
                return code == 204 ? "" : readAll(c.getInputStream());
            } catch (ApiException e) {
                throw e;
            } catch (java.net.ConnectException e) {
                throw unreachable();
            } catch (java.net.SocketTimeoutException e) {
                throw new ApiException("Jellyfin did not answer in time. Try again.");
            } catch (Throwable e) {
                throw new ApiException("Jellyfin request failed (" + e.getClass().getSimpleName() + ").");
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw new AuthException("Jellyfin rejected the sign-in. Sign in again.");
    }

    private static String get(Context ctx, String pathAndQuery) throws ApiException {
        return request(ctx, "GET", pathAndQuery);
    }

    /**
     * Downloads an item's primary image scaled by Jellyfin to {@code maxWidthPx} and decodes it (RGB_565).
     * Returns null when there is no image or on any error. Blocking.
     */
    public static Bitmap loadPrimaryImage(Context ctx, String itemId, String tag, int maxWidthPx) {
        if (itemId == null || itemId.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            String t = getToken(ctx);
            String q = "/Items/" + enc(itemId) + "/Images/Primary?maxWidth=" + Math.max(32, maxWidthPx) + "&quality=85"
                    + (tag != null && !tag.isEmpty() ? "&tag=" + enc(tag) : "");
            c = open(q, "GET", t);
            c.setRequestProperty("Accept", "image/*");
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_IMAGE_BYTES) return null;
                }
            }
            byte[] data = bos.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= Math.max(1, maxWidthPx)) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ users & parsing

    public static List<JellyfinAccountManager.JellyfinUser> users(Context ctx) {
        return JellyfinAccountManager.getRegisteredUsers(ctx);
    }

    /** User whose library is listed: the signed-in user, else the first administrator. */
    private static String queryUserId(Context ctx) throws ApiException {
        getToken(ctx);
        String uid = tokenUserId;
        if (uid != null && !uid.isEmpty()) return uid;
        return primaryUserId(ctx);
    }

    private static String primaryUserId(Context ctx) throws ApiException {
        List<JellyfinAccountManager.JellyfinUser> us = users(ctx);
        for (JellyfinAccountManager.JellyfinUser u : us) if (u.isAdmin && !u.id.isEmpty()) return u.id;
        if (!us.isEmpty() && !us.get(0).id.isEmpty()) return us.get(0).id;
        // jellyfin.db not readable: ask the server (works with administrator tokens and API keys).
        List<String[]> all = serverUsers(ctx);
        for (String[] u : all) {
            if ("1".equals(u[2])) return u[0];
        }
        if (!all.isEmpty()) return all.get(0)[0];
        throw new ApiException("No Jellyfin users found.");
    }

    /** [id, name, isAdmin "1"/"0"] from GET /Users (administrator only); empty on failure. */
    private static List<String[]> serverUsers(Context ctx) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(get(ctx, "/Users"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optString("Id", "").isEmpty()) continue;
                JSONObject pol = o.optJSONObject("Policy");
                boolean admin = pol != null && pol.optBoolean("IsAdministrator", false);
                out.add(new String[]{o.optString("Id"), o.optString("Name", ""), admin ? "1" : "0"});
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static long parseDate(String s) {
        if (s == null || s.length() < 19) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date dt = f.parse(s.substring(0, 19));
            return dt == null ? 0L : dt.getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String optStr(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }

    private static List<LibItem> parseItems(String body, String userName) throws Exception {
        List<LibItem> out = new ArrayList<LibItem>();
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            LibItem it = new LibItem();
            it.id = o.optString("Id");
            it.type = o.optString("Type");
            it.name = o.optString("Name");
            it.seriesName = optStr(o, "SeriesName");
            it.seriesId = optStr(o, "SeriesId");
            if (o.has("ParentIndexNumber") && !o.isNull("ParentIndexNumber")) it.seasonNumber = o.optInt("ParentIndexNumber", -1);
            if (o.has("IndexNumber") && !o.isNull("IndexNumber")) {
                if ("Season".equals(it.type)) it.seasonNumber = o.optInt("IndexNumber", -1);
                else it.episodeNumber = o.optInt("IndexNumber", -1);
            }
            it.year = o.optInt("ProductionYear", 0);
            it.childCount = o.optInt("ChildCount", 0);
            JSONObject tags = o.optJSONObject("ImageTags");
            it.imageTag = tags == null ? null : optStr(tags, "Primary");
            it.seriesImageTag = optStr(o, "SeriesPrimaryImageTag");
            JSONObject ud = o.optJSONObject("UserData");
            if (ud != null) {
                it.played = ud.optBoolean("Played", false);
                it.playCount = ud.optInt("PlayCount", 0);
                it.lastPlayedMs = parseDate(optStr(ud, "LastPlayedDate"));
            }
            it.playedBy = userName;
            out.add(it);
        }
        return out;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Throwable t) { return s; }
    }

    // ------------------------------------------------------------------ queries

    private static ApiException unexpected(Throwable t) {
        return new ApiException("Unexpected answer from Jellyfin: " + t.getMessage());
    }

    public static List<LibItem> movies(Context ctx) throws ApiException {
        try {
            String uid = queryUserId(ctx);
            return parseItems(get(ctx, "/Items?userId=" + enc(uid)
                    + "&IncludeItemTypes=Movie&Recursive=true&SortBy=SortName&SortOrder=Ascending"
                    + "&EnableUserData=true&EnableImageTypes=Primary&ImageTypeLimit=1&EnableTotalRecordCount=false"), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static List<LibItem> series(Context ctx) throws ApiException {
        try {
            String uid = queryUserId(ctx);
            return parseItems(get(ctx, "/Items?userId=" + enc(uid)
                    + "&IncludeItemTypes=Series&Recursive=true&SortBy=SortName&SortOrder=Ascending"
                    + "&Fields=ChildCount&EnableUserData=true&EnableImageTypes=Primary&ImageTypeLimit=1"
                    + "&EnableTotalRecordCount=false"), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Real seasons of a series (isMissing=false hides Jellyfin's virtual "missing" placeholders). */
    public static List<LibItem> seasons(Context ctx, String seriesId) throws ApiException {
        try {
            String uid = queryUserId(ctx);
            return parseItems(get(ctx, "/Shows/" + enc(seriesId) + "/Seasons?userId=" + enc(uid)
                    + "&Fields=ChildCount&isMissing=false&enableUserData=true"), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static List<LibItem> episodes(Context ctx, String seriesId, String seasonId) throws ApiException {
        try {
            String uid = queryUserId(ctx);
            return parseItems(get(ctx, "/Shows/" + enc(seriesId) + "/Episodes?userId=" + enc(uid)
                    + (seasonId != null ? "&seasonId=" + enc(seasonId) : "")
                    + "&isMissing=false&enableUserData=true"), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Played movies and episodes across all users, most recently watched first. When several users
     * watched the same item, the latest watch wins. Users the signed-in account may not read are skipped.
     */
    public static List<LibItem> recentlyWatched(Context ctx, int limitPerUser) throws ApiException {
        try {
            Map<String, LibItem> best = new HashMap<String, LibItem>();
            List<String[]> who = new ArrayList<>();
            for (JellyfinAccountManager.JellyfinUser u : users(ctx)) {
                if (!u.id.isEmpty()) who.add(new String[]{u.id, u.username});
            }
            if (who.isEmpty()) {
                for (String[] u : serverUsers(ctx)) who.add(new String[]{u[0], u[1]});
            }
            if (who.isEmpty()) {
                String uid = queryUserId(ctx);
                who.add(new String[]{uid, tokenUserName});
            }
            ApiException firstError = null;
            int ok = 0;
            for (String[] u : who) {
                String body;
                try {
                    body = get(ctx, "/Items?userId=" + enc(u[0])
                            + "&Filters=IsPlayed&Recursive=true&IncludeItemTypes=Movie,Episode"
                            + "&SortBy=DatePlayed&SortOrder=Descending&Limit=" + limitPerUser
                            + "&EnableUserData=true&EnableImageTypes=Primary&ImageTypeLimit=1&EnableTotalRecordCount=false");
                } catch (ApiException e) {
                    if (e instanceof AuthException || e instanceof UnreachableException) throw e;
                    if (firstError == null) firstError = e;
                    continue;
                }
                ok++;
                for (LibItem it : parseItems(body, u[1])) {
                    if (it.lastPlayedMs <= 0) continue;
                    LibItem cur = best.get(it.id);
                    if (cur == null || it.lastPlayedMs > cur.lastPlayedMs) best.put(it.id, it);
                }
            }
            if (ok == 0 && firstError != null) throw firstError;
            List<LibItem> list = new ArrayList<LibItem>(best.values());
            Collections.sort(list, new Comparator<LibItem>() {
                @Override
                public int compare(LibItem a, LibItem b) {
                    return Long.compare(b.lastPlayedMs, a.lastPlayedMs);
                }
            });
            return list;
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Deletes an item (and its files on disk, as Jellyfin does for library deletion). Throws a readable error. */
    public static void deleteItemOrThrow(Context ctx, String itemId) throws ApiException {
        request(ctx, "DELETE", "/Items/" + enc(itemId));
    }

    /** Same as {@link #deleteItemOrThrow} but returns false instead of throwing. */
    public static boolean deleteItem(Context ctx, String itemId) {
        try {
            deleteItemOrThrow(ctx, itemId);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "deleteItem failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ libraries (virtual folders)

    /** Jellyfin libraries: [{Name, CollectionType, Locations[], ItemId}, ...]. */
    public static org.json.JSONArray virtualFolders(Context ctx) throws ApiException {
        String r = get(ctx, "/Library/VirtualFolders");
        try {
            return r == null || r.trim().isEmpty() ? new org.json.JSONArray() : new org.json.JSONArray(r);
        } catch (org.json.JSONException e) {
            throw new ApiException("Unexpected answer from Jellyfin.");
        }
    }

    private static String q(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    /** Adds a folder to an existing library (no scan; call {@link #refreshLibraries}). */
    public static void addLibraryPath(Context ctx, String library, String path) throws ApiException {
        String body;
        try {
            body = new org.json.JSONObject().put("Name", library)
                    .put("PathInfo", new org.json.JSONObject().put("Path", path)).toString();
        } catch (org.json.JSONException e) {
            throw new ApiException("Invalid library request.");
        }
        request(ctx, "POST", "/Library/VirtualFolders/Paths?refreshLibrary=false", body);
    }

    /** Removes a folder from a library. Files on disk are not touched. */
    public static void removeLibraryPath(Context ctx, String library, String path) throws ApiException {
        request(ctx, "DELETE", "/Library/VirtualFolders/Paths?name=" + q(library) + "&path=" + q(path)
                + "&refreshLibrary=false", null);
    }

    /** Creates a library ("movies" or "tvshows") with one folder and starts its scan. */
    public static void createLibrary(Context ctx, String name, String collectionType, String path) throws ApiException {
        request(ctx, "POST", "/Library/VirtualFolders?name=" + q(name) + "&collectionType=" + q(collectionType)
                + "&paths=" + q(path) + "&refreshLibrary=true", "{\"LibraryOptions\":{}}");
    }

    /** Starts a scan of every library. */
    public static void refreshLibraries(Context ctx) throws ApiException {
        request(ctx, "POST", "/Library/Refresh", null);
    }
}
