package com.jellyarr.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.jellyarr.app.services.JellyfinLibraryClient;
import com.jellyarr.app.services.MediaServiceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One username / password for every service: Jellyfin (user), Sonarr, Radarr, Prowlarr (Forms login)
 * and Transmission (RPC authentication). The account is stored locally and pushed to each service
 * when it is running; services that are offline stay "pending" until the next apply.
 */
public final class UnifiedAccount {

    public static final String PREFS = "fishbowl_unified_account";
    private static final String K_USER = "username";
    private static final String K_PASS = "password";
    private static final String K_SETUP_DONE = "first_run_done";

    public static final String[] SERVICES = {"jellyfin", "sonarr", "radarr", "prowlarr", "transmission"};

    public interface Progress {
        void onStatus(String serviceId, String status, String message);
    }

    private UnifiedAccount() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ storage

    public static boolean isConfigured(Context c) {
        String u = getUsername(c);
        String p = getPassword(c);
        return u != null && !u.isEmpty() && p != null && !p.isEmpty();
    }

    public static String getUsername(Context c) {
        return sp(c).getString(K_USER, "");
    }

    public static String getPassword(Context c) {
        return sp(c).getString(K_PASS, "");
    }

    public static boolean isFirstRunDone(Context c) {
        return sp(c).getBoolean(K_SETUP_DONE, false);
    }

    public static void setFirstRunDone(Context c) {
        sp(c).edit().putBoolean(K_SETUP_DONE, true).apply();
    }

    public static void save(Context c, String user, String pass) {
        SharedPreferences.Editor e = sp(c).edit().putString(K_USER, user).putString(K_PASS, pass);
        for (String s : SERVICES) e.putString("status_" + s, "pending").remove("msg_" + s).remove("sig_" + s);
        e.apply();
    }

    static final String RESTORE_MARKER = "unified_account_recheck";

    /** Called at start-up: a restore leaves a marker, then all services go back to "pending" and are re-checked. */
    public static void afterRestoreIfNeeded(Context c) {
        java.io.File marker = new java.io.File(c.getFilesDir(), RESTORE_MARKER);
        if (!marker.exists()) return;
        SharedPreferences.Editor e = sp(c).edit();
        for (String s : SERVICES) e.putString("status_" + s, "pending").remove("msg_" + s).remove("sig_" + s);
        e.commit();
        marker.delete();
    }

    /** Forgets the stored account (services keep whatever login they already have). */
    public static void clear(Context c) {
        SharedPreferences.Editor e = sp(c).edit().remove(K_USER).remove(K_PASS);
        for (String s : SERVICES) e.remove("status_" + s).remove("msg_" + s).remove("sig_" + s);
        e.apply();
    }

    /** ok / pending / failed / none */
    public static String getStatus(Context c, String id) {
        return sp(c).getString("status_" + id, isConfigured(c) ? "pending" : "none");
    }

    public static String getMessage(Context c, String id) {
        return sp(c).getString("msg_" + id, "");
    }

    public static boolean hasPending(Context c) {
        if (!isConfigured(c)) return false;
        for (String s : SERVICES) {
            if (!"ok".equals(getStatus(c, s))) return true;
        }
        return false;
    }

    private static void setStatus(Context c, String id, String status, String msg, Progress cb) {
        sp(c).edit().putString("status_" + id, status).putString("msg_" + id, msg == null ? "" : msg)
                .putLong("time_" + id, System.currentTimeMillis()).apply();
        if (cb != null) cb.onStatus(id, status, msg);
    }

    /** Returns null when the password is acceptable. */
    public static String validate(String user, String pass) {
        if (user == null || user.trim().length() < 3) return "Username must be at least 3 characters";
        if (!user.trim().matches("[A-Za-z0-9._-]+")) return "Username may use letters, digits, . _ - only";
        if (pass == null || pass.length() < 6) return "Password must be at least 6 characters";
        if (pass.startsWith("{")) return "Password must not start with {";
        return null;
    }

    // ------------------------------------------------------------------ Transmission helper

    /** Adds the Basic header to a Transmission RPC connection when an account exists. */
    public static void authorize(HttpURLConnection conn, Context c) {
        try {
            if (conn == null || c == null || !isConfigured(c)) return;
            String raw = getUsername(c) + ":" + getPassword(c);
            conn.setRequestProperty("Authorization",
                    "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ apply

    /** Blocking: call from a background thread. */
    public static void applyAll(Context ctx, boolean force, Progress cb) {
        final Context c = ctx.getApplicationContext();
        if (!isConfigured(c)) return;
        // One pass at a time (the screen, SYNC STACK and service starts can all ask). An automatic pass simply skips
        // when another is running; a forced one (new password, APPLY NOW) waits for it and then runs.
        if (force) APPLY_LOCK.lock();
        else if (!APPLY_LOCK.tryLock()) return;
        try {
            applyAllLocked(c, force, cb);
        } finally {
            APPLY_LOCK.unlock();
        }
    }

    private static final java.util.concurrent.locks.ReentrantLock APPLY_LOCK = new java.util.concurrent.locks.ReentrantLock();

    private static void applyAllLocked(Context c, boolean force, Progress cb) {
        final String user = getUsername(c).trim();
        final String pass = getPassword(c);

        if (force || due(c, "jellyfin")) applyJellyfin(c, user, pass, cb);
        if (force || due(c, "sonarr")) applyArr(c, "sonarr", 8989, "/api/v3", user, pass, cb);
        if (force || due(c, "radarr")) applyArr(c, "radarr", 7878, "/api/v3", user, pass, cb);
        if (force || due(c, "prowlarr")) applyArr(c, "prowlarr", 9696, "/api/v1", user, pass, cb);
        if (force || due(c, "transmission")) applyTransmission(c, cb);
    }

    /** Automatic passes retry "pending" services; a "failed" one is retried at most every 6 hours. */
    private static boolean due(Context c, String id) {
        String st = getStatus(c, id);
        if ("ok".equals(st)) return false;
        if (!"failed".equals(st)) return true;
        return System.currentTimeMillis() - sp(c).getLong("time_" + id, 0) > 6L * 3600 * 1000;
    }

    /** True when an automatic pass would do something (pending, or a failure old enough to retry). */
    public static boolean hasDue(Context c) {
        if (!isConfigured(c)) return false;
        for (String s : SERVICES) if (due(c, s)) return true;
        return false;
    }

    // ---- Sonarr / Radarr / Prowlarr

    private static String appName(String id) {
        if ("sonarr".equals(id)) return "Sonarr";
        if ("radarr".equals(id)) return "Radarr";
        return "Prowlarr";
    }

    /**
     * Makes a host-config body pass the *arr validators. The app binds the services to 0.0.0.0, which the
     * validator rejects ("use * instead"), so the body sends * (same meaning: listen on every interface).
     */
    private static void sanitizeHostConfig(JSONObject host, String id) throws Exception {
        String bind = host.optString("bindAddress", "*");
        if (bind.isEmpty() || "0.0.0.0".equals(bind)) host.put("bindAddress", "*");
        String name = appName(id);
        String inst = host.optString("instanceName", "");
        if (inst.isEmpty() || !inst.toLowerCase(Locale.US).contains(name.toLowerCase(Locale.US))) host.put("instanceName", name);
        if (host.optString("branch", "").isEmpty()) host.put("branch", "prowlarr".equals(id) ? "master" : "main");
        if (host.has("backupInterval")) {
            int v = host.optInt("backupInterval", 7);
            if (v < 1 || v > 7) host.put("backupInterval", 7);
        }
        if (host.has("backupRetention")) {
            int v = host.optInt("backupRetention", 28);
            if (v < 1 || v > 90) host.put("backupRetention", 28);
        }
        if (host.has("logSizeLimit")) {
            int v = host.optInt("logSizeLimit", 1);
            if (v < 1 || v > 10) host.put("logSizeLimit", 1);
        }
        if (host.optBoolean("enableSsl", false)) {
            int sp = host.optInt("sslPort", 0);
            if (sp <= 0 || sp > 65535) host.put("enableSsl", false);
        }
        if (host.optBoolean("proxyEnabled", false) && host.optString("proxyHostname", "").isEmpty()) {
            host.put("proxyEnabled", false);
        }
        int port = host.optInt("port", 0);
        if (port <= 0 || port > 65535) host.put("port", "sonarr".equals(id) ? 8989 : "radarr".equals(id) ? 7878 : 9696);
    }

    private static void applyArr(Context c, String id, int port, String api, String user, String pass, Progress cb) {
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(c);
            if (!portOpen(port)) {
                setStatus(c, id, "pending", "Not running", cb);
                return;
            }
            String key = mgr.getServiceApiKey(id);
            if (key == null || key.isEmpty()) {
                setStatus(c, id, "pending", "API key not found yet", cb);
                return;
            }
            String base = "http://127.0.0.1:" + port + api;
            JSONObject host = new JSONObject(http("GET", base + "/config/host", key, null, null));

            // Already signed in with this account? Then leave the service alone: re-sending the login re-hashes the
            // password, drops every open web session and can make the service restart in the middle of a sync.
            if (arrAlreadySignedIn(host, port, user, pass)) {
                if (!signature(user, pass).equals(sp(c).getString("sig_" + id, ""))) {
                    if (!"prowlarr".equals(id)) syncTransmissionClient(base, key, user, pass);
                    sp(c).edit().putString("sig_" + id, signature(user, pass)).apply();
                }
                setStatus(c, id, "ok", "", cb);
                return;
            }

            sanitizeHostConfig(host, id);
            host.put("authenticationMethod", "forms");
            // Same rule the app enforces in config.xml at every start: with an account set, the web UI always asks
            // for it (on the phone and on the network), so signing out really signs out. The in-app tabs talk to the
            // service with its API key and are not affected.
            host.put("authenticationRequired", "enabled");
            host.put("username", user);
            host.put("password", pass);
            host.put("passwordConfirmation", pass);
            http("PUT", base + "/config/host/" + host.optInt("id", 1), key, host.toString(), null);

            // Keep the Transmission download client in Radarr / Sonarr in sync with the new credentials.
            if (!"prowlarr".equals(id)) syncTransmissionClient(base, key, user, pass);

            // Verify the new login really works on the web UI (the service may need a moment after the change).
            int verdict = verifyFormsLogin(port, user, pass);
            for (int i = 0; verdict == 0 && i < 3; i++) {
                Thread.sleep(1500);
                verdict = verifyFormsLogin(port, user, pass);
            }
            if (verdict < 0) {
                setStatus(c, id, "failed", "Saved, but the new password was refused at login", cb);
                return;
            }
            sp(c).edit().putString("sig_" + id, signature(user, pass)).apply();
            setStatus(c, id, "ok", "", cb);
        } catch (Throwable t) {
            setStatus(c, id, "failed", String.valueOf(t.getMessage()), cb);
        }
    }

    /**
     * True when the service already uses Forms login and accepts exactly this username and password. The check is a
     * real login attempt against the web UI, so it is right even when the host config hides the stored password.
     */
    static boolean arrAlreadySignedIn(JSONObject host, int port, String user, String pass) {
        if (host == null) return false;
        if (!"forms".equalsIgnoreCase(host.optString("authenticationMethod", ""))) return false;
        String current = host.optString("username", "");
        if (!current.isEmpty() && !current.equalsIgnoreCase(user)) return false;
        return verifyFormsLogin(port, user, pass) > 0;
    }

    /** Non-reversible fingerprint of the account, used to tell whether a service already got this exact account. */
    private static String signature(String user, String pass) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((user.toLowerCase(Locale.US) + "\n" + pass).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void syncTransmissionClient(String base, String key, String user, String pass) {
        try {
            JSONArray arr = new JSONArray(http("GET", base + "/downloadclient", key, null, null));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject cl = arr.optJSONObject(i);
                if (cl == null || !"Transmission".equalsIgnoreCase(cl.optString("implementation"))) continue;
                JSONArray fields = cl.optJSONArray("fields");
                if (fields == null) continue;
                boolean sawUser = false, sawPass = false;
                for (int j = 0; j < fields.length(); j++) {
                    JSONObject f = fields.optJSONObject(j);
                    if (f == null) continue;
                    if ("username".equalsIgnoreCase(f.optString("name"))) {
                        f.put("value", user);
                        sawUser = true;
                    } else if ("password".equalsIgnoreCase(f.optString("name"))) {
                        f.put("value", pass);
                        sawPass = true;
                    }
                }
                if (!sawUser) fields.put(new JSONObject().put("name", "username").put("value", user));
                if (!sawPass) fields.put(new JSONObject().put("name", "password").put("value", pass));
                http("PUT", base + "/downloadclient/" + cl.optInt("id") + "?forceSave=true", key, cl.toString(), null);
            }
        } catch (Throwable ignored) {
            // non fatal: wiring is repaired on the next SYNC STACK
        }
    }

    /** 1 = accepted, -1 = refused, 0 = could not tell. Forms login answers with a redirect either way. */
    static int verifyFormsLogin(int port, String user, String pass) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/login?returnUrl=/").openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String form = "username=" + java.net.URLEncoder.encode(user, "UTF-8")
                    + "&password=" + java.net.URLEncoder.encode(pass, "UTF-8") + "&rememberMe=on";
            OutputStream os = conn.getOutputStream();
            os.write(form.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) return loc.contains("loginFailed") ? -1 : 1;
            if (code == 401 || code == 403) return -1;
            return 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- Transmission

    private static void applyTransmission(Context c, Progress cb) {
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(c);
            com.jellyarr.app.model.ManagedService svc = mgr.getService("transmission");
            if (svc == null || !svc.isInstalled()) {
                setStatus(c, "transmission", "pending", "Not installed", cb);
                return;
            }
            if (!portOpen(9091)) {
                // Not running: the credentials are written into settings.json the next time it starts.
                setStatus(c, "transmission", "pending", "Will apply at next start", cb);
                return;
            }
            // Already protected by this account (anonymous call refused, authenticated call accepted)? Keep it running.
            int anon = rpcProbe(c, false);
            int authed = rpcProbe(c, true);
            if (anon == 401 && (authed == 200 || authed == 409)) {
                setStatus(c, "transmission", "ok", "", cb);
                return;
            }
            // Transmission only reads rpc-username / rpc-password from settings.json at start-up.
            mgr.stopService("transmission");
            long end = System.currentTimeMillis() + 20000;
            while (portOpen(9091) && System.currentTimeMillis() < end) Thread.sleep(500);
            Thread.sleep(800);
            mgr.startService("transmission");
            end = System.currentTimeMillis() + 40000;
            while (!portOpen(9091) && System.currentTimeMillis() < end) Thread.sleep(800);
            if (!portOpen(9091)) {
                setStatus(c, "transmission", "failed", "Did not come back after restart", cb);
                return;
            }
            // Verify: an authenticated call must be accepted (200 or the 409 session challenge).
            int code = rpcProbe(c, true);
            if (code == 200 || code == 409) setStatus(c, "transmission", "ok", "", cb);
            else setStatus(c, "transmission", "failed", "RPC answered HTTP " + code, cb);
        } catch (Throwable t) {
            setStatus(c, "transmission", "failed", String.valueOf(t.getMessage()), cb);
        }
    }

    private static int rpcProbe(Context c, boolean withAccount) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:9091/transmission/rpc").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            if (withAccount) authorize(conn, c);
            OutputStream os = conn.getOutputStream();
            os.write("{\"method\":\"session-get\"}".getBytes(StandardCharsets.UTF_8));
            os.close();
            return conn.getResponseCode();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- Jellyfin

    private static void applyJellyfin(Context c, String user, String pass, Progress cb) {
        try {
            if (!portOpen(8096)) {
                setStatus(c, "jellyfin", "pending", "Not running", cb);
                return;
            }
            String pub = http("GET", "http://127.0.0.1:8096/System/Info/Public", null, null, null);
            boolean wizardDone = new JSONObject(pub).optBoolean("StartupWizardCompleted", true);
            if (!wizardDone) {
                // Brand new server: finish the first-run wizard with this account.
                http("POST", "http://127.0.0.1:8096/Startup/Configuration", null,
                        "{\"UICulture\":\"en-US\",\"MetadataCountryCode\":\"US\",\"PreferredMetadataLanguage\":\"en\"}", null);
                try { http("GET", "http://127.0.0.1:8096/Startup/User", null, null, null); } catch (Throwable ignored) {}
                http("POST", "http://127.0.0.1:8096/Startup/User", null,
                        new JSONObject().put("Name", user).put("Password", pass).toString(), null);
                http("POST", "http://127.0.0.1:8096/Startup/Complete", null, "", null);
                JellyfinAccountManager.storeKnownPassword(c, user, pass);
                setStatus(c, "jellyfin", "ok", "", cb);
                return;
            }

            // Existing server: reuse the user if it exists, otherwise create an administrator.
            boolean exists = false;
            for (JellyfinAccountManager.JellyfinUser u : JellyfinAccountManager.getRegisteredUsers(c)) {
                if (u.username != null && u.username.equalsIgnoreCase(user)) {
                    exists = true;
                    user = u.username;
                    break;
                }
            }
            if (exists) {
                if (jellyfinLoginWorks(user, pass)) {
                    JellyfinAccountManager.storeKnownPassword(c, user, pass);
                    setStatus(c, "jellyfin", "ok", "", cb);
                    return;
                }
                boolean changed = false;
                try {
                    String tok = JellyfinLibraryClient.getToken(c);
                    if (tok != null) {
                        JSONArray users = new JSONArray(http("GET", "http://127.0.0.1:8096/Users", null, null, tok));
                        for (int i = 0; i < users.length(); i++) {
                            JSONObject u = users.optJSONObject(i);
                            if (u == null || !user.equalsIgnoreCase(u.optString("Name"))) continue;
                            JSONObject body = new JSONObject().put("CurrentPw", "").put("NewPw", pass).put("ResetPassword", false);
                            http("POST", "http://127.0.0.1:8096/Users/" + u.optString("Id") + "/Password", null, body.toString(), tok);
                            changed = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
                if (!changed) changed = JellyfinAccountManager.setPassword(c, user, pass);
                if (!changed) {
                    setStatus(c, "jellyfin", "failed", "Could not update the existing user's password", cb);
                    return;
                }
                JellyfinAccountManager.storeKnownPassword(c, user, pass);
                if (!jellyfinLoginWorks(user, pass)) {
                    setStatus(c, "jellyfin", "failed", "Password saved, but Jellyfin still refuses it (restart Jellyfin)", cb);
                    return;
                }
                setStatus(c, "jellyfin", "ok", "", cb);
                return;
            }
            String token = JellyfinLibraryClient.getToken(c);
            if (token == null) {
                setStatus(c, "jellyfin", "failed", "No administrator login available", cb);
                return;
            }
            String created = http("POST", "http://127.0.0.1:8096/Users/New", null,
                    new JSONObject().put("Name", user).put("Password", pass).toString(), token);
            String uid = new JSONObject(created).optString("Id", "");
            if (!uid.isEmpty()) {
                try {
                    JSONObject u = new JSONObject(http("GET", "http://127.0.0.1:8096/Users/" + uid, null, null, token));
                    JSONObject pol = u.optJSONObject("Policy");
                    if (pol != null) {
                        pol.put("IsAdministrator", true);
                        http("POST", "http://127.0.0.1:8096/Users/" + uid + "/Policy", null, pol.toString(), token);
                    }
                } catch (Throwable ignored) {}
            }
            JellyfinAccountManager.storeKnownPassword(c, user, pass);
            setStatus(c, "jellyfin", "ok", "", cb);
        } catch (Throwable t) {
            setStatus(c, "jellyfin", "failed", String.valueOf(t.getMessage()), cb);
        }
    }

    private static boolean jellyfinLoginWorks(String user, String pass) {
        try {
            String auth = "MediaBrowser Client=\"Jellyarr\", Device=\"Android\", DeviceId=\"jellyarr-account\", Version=\"1.0\"";
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:8096/Users/AuthenticateByName").openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Emby-Authorization", auth);
            conn.setRequestProperty("Authorization", auth);
            OutputStream os = conn.getOutputStream();
            os.write(new JSONObject().put("Username", user).put("Pw", pass).toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the entered credentials belong to an existing Jellyfin user (used by "Sign in"). */
    public static boolean verifyAgainstJellyfin(Context c, String user, String pass) {
        try {
            for (JellyfinAccountManager.JellyfinUser u : JellyfinAccountManager.getRegisteredUsers(c)) {
                if (u.username != null && u.username.equalsIgnoreCase(user.trim())) {
                    return u.hasPassword && JellyfinAccountManager.verifyPasswordHash(pass, u.passwordHash);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean jellyfinHasUser(Context c, String user) {
        try {
            for (JellyfinAccountManager.JellyfinUser u : JellyfinAccountManager.getRegisteredUsers(c)) {
                if (u.username != null && u.username.equalsIgnoreCase(user.trim())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ------------------------------------------------------------------ http

    /** Turns an *arr validation array or a JSON error object into one short line. */
    private static String readableError(String body) {
        try {
            String t = body == null ? "" : body.trim();
            if (t.startsWith("[")) {
                JSONArray a = new JSONArray(t);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length() && i < 3; i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    if (sb.length() > 0) sb.append("; ");
                    String prop = o.optString("propertyName", "");
                    if (!prop.isEmpty()) sb.append(prop).append(": ");
                    sb.append(o.optString("errorMessage", ""));
                }
                return sb.toString();
            }
            if (t.startsWith("{")) {
                JSONObject o = new JSONObject(t);
                return o.optString("message", o.optString("errorMessage", o.optString("title", "")));
            }
            return t.length() > 160 ? t.substring(0, 160) : t;
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String http(String method, String url, String apiKey, String body, String jellyfinToken) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod(method);
            if (apiKey != null) conn.setRequestProperty("X-Api-Key", apiKey);
            if (jellyfinToken != null) {
                conn.setRequestProperty("X-Emby-Token", jellyfinToken);
                conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + jellyfinToken + "\"");
            }
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            if (code >= 400) {
                String detail = readableError(sb.toString());
                String where = url.replaceAll("\\?.*", "").replaceAll("^http://127\\.0\\.0\\.1:\\d+", "");
                throw new Exception(detail.isEmpty()
                        ? String.format(Locale.US, "HTTP %d at %s", code, where)
                        : String.format(Locale.US, "HTTP %d: %s", code, detail));
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
