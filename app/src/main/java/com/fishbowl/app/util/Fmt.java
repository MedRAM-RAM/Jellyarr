package com.fishbowl.app.util;

import java.util.Locale;

/** Number formatting shared by every screen (one place instead of a private copy per class). */
public final class Fmt {
    private Fmt() {}

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    /** 0 B, 512 B, 1.50 KB, 12.3 MB, 123 GB. */
    public static String bytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < UNITS.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, UNITS[i]);
    }
}
