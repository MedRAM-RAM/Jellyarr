package com.jellyarr.app.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import org.json.JSONObject;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings and helpers for the Cloudflare Tunnel that Jellyarr runs itself ({@code cloudflared}, built in CI with a
 * DNS patch for Android and shipped as an app asset). The tunnel connects the user's public hostname to the
 * addon bridge on this phone, so no other device or app is needed.
 */
public final class CloudflareTunnel {

    public static final String SERVICE_ID = "cloudflared";
    public static final String ASSET_NAME = "cloudflared-android-arm64";
    /** cloudflared's metrics server; {@code /ready} answers 200 only while connected to the Cloudflare edge. */
    public static final int METRICS_PORT = 20244;

    private static final String PREFS = "cloudflare_tunnel";
    // Tunnel tokens are base64 of {"a": account, "t": tunnel id, "s": secret}, and always start with "eyJ".
    private static final Pattern TOKEN = Pattern.compile("eyJ[A-Za-z0-9+/_=-]{30,}");

    private CloudflareTunnel() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getToken(Context c) { return prefs(c).getString("token", ""); }

    /**
     * Accepts the token itself or the whole command Cloudflare shows ("cloudflared service install eyJ...").
     * Returns false and saves nothing when no valid tunnel token is found; an empty input clears the token.
     */
    public static boolean setToken(Context c, String input) {
        String in = input == null ? "" : input.trim();
        if (in.isEmpty()) {
            prefs(c).edit().putString("token", "").apply();
            return true;
        }
        String token = extractToken(in);
        if (token == null) return false;
        prefs(c).edit().putString("token", token).apply();
        return true;
    }

    static String extractToken(String input) {
        Matcher m = TOKEN.matcher(input.replaceAll("\\s+", " "));
        while (m.find()) {
            String t = m.group();
            if (isValidToken(t)) return t;
        }
        return null;
    }

    static boolean isValidToken(String t) {
        try {
            String b64 = t.replace('-', '+').replace('_', '/');
            while (b64.length() % 4 != 0) b64 += "=";
            JSONObject o = new JSONObject(new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), StandardCharsets.UTF_8));
            return o.has("a") && o.has("t") && o.has("s");
        } catch (Throwable e) {
            return false;
        }
    }

    /** Whether this build ships cloudflared (CI builds do; a plain local build does not). */
    public static boolean isBundled(Context c) {
        try (java.io.InputStream in = c.getAssets().open(ASSET_NAME)) {
            return in != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * DNS servers for the patched cloudflared: the active network's own, then public ones as a fallback.
     * Android has no /etc/resolv.conf, so Go cannot find them by itself.
     */
    public static String dnsServers(Context c) {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    String h = a.getHostAddress();
                    if (h == null || h.isEmpty() || out.size() >= 2) continue;
                    if (a instanceof Inet6Address) {
                        int zone = h.indexOf('%');
                        if (zone > 0) h = h.substring(0, zone);
                        h = "[" + h + "]";
                    }
                    out.add(h + ":53");
                }
            }
        } catch (Throwable ignored) {}
        out.add("1.1.1.1:53");
        out.add("8.8.8.8:53");
        StringBuilder sb = new StringBuilder();
        for (String s : out) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }
}
