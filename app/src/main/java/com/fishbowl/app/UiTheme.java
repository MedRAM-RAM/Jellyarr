package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Shared Material-style color tokens and persisted accent themes for native Jellyarr screens. */
public final class UiTheme {
    public static final String OCEAN = "ocean";
    public static final String VIOLET = "violet";
    public static final String EMERALD = "emerald";
    public static final String AMBER = "amber";
    public static final String ANDROID_DYNAMIC = "android_dynamic";

    private static final String PREFS = "fishbowl_settings";
    private static final String PREF_THEME = "ui_accent_theme";
    private static final int LEGACY_ACCENT = 0xFF38BDF8;

    private UiTheme() {}

    public static final class Palette {
        public final int background;
        public final int surface;
        public final int card;
        public final int elevated;
        public final int inner;
        public final int border;
        public final int textPrimary;
        public final int textSecondary;
        public final int textMuted;
        public final int accent;
        public final int onAccent;
        public final int success;
        public final int warning;
        public final int danger;

        private Palette(int accent) {
            this.background = 0xFF080B11;
            this.surface = 0xFF0D131D;
            this.card = 0xFF111A27;
            this.elevated = 0xFF182332;
            this.inner = 0xFF0F1722;
            this.border = blend(0xFF29374A, accent, 0.12f);
            this.textPrimary = 0xFFF4F7FB;
            this.textSecondary = 0xFFB9C4D2;
            this.textMuted = 0xFF8491A3;
            this.accent = accent;
            this.onAccent = luminance(accent) > 0.52 ? 0xFF0B1220 : Color.WHITE;
            this.success = 0xFF70D7A6;
            this.warning = 0xFFF4C96B;
            this.danger = 0xFFFF7B89;
        }
    }

    public static String[] themeKeys() {
        return new String[]{OCEAN, VIOLET, EMERALD, AMBER, ANDROID_DYNAMIC};
    }

    public static String selectedTheme(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = preferences.getString(PREF_THEME, OCEAN);
        for (String key : themeKeys()) if (key.equals(value)) return value;
        return OCEAN;
    }

    public static void saveTheme(Context context, String theme) {
        boolean valid = false;
        for (String key : themeKeys()) if (key.equals(theme)) valid = true;
        if (valid) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_THEME, theme).apply();
    }

    public static String themeName(String theme) {
        if (VIOLET.equals(theme)) return "Violet";
        if (EMERALD.equals(theme)) return "Emerald";
        if (AMBER.equals(theme)) return "Amber";
        if (ANDROID_DYNAMIC.equals(theme)) return "Android Dynamic";
        return "Ocean";
    }

    public static String themeDescription(String theme) {
        if (VIOLET.equals(theme)) return "Soft violet accent";
        if (EMERALD.equals(theme)) return "Fresh emerald accent";
        if (AMBER.equals(theme)) return "Warm amber accent";
        if (ANDROID_DYNAMIC.equals(theme)) return "Uses Android wallpaper colors on Android 12+";
        return "Balanced ocean blue accent";
    }

    public static Palette palette(Context context) {
        return paletteFor(context, selectedTheme(context));
    }

    public static Palette paletteFor(Context context, String theme) {
        int accent;
        if (VIOLET.equals(theme)) {
            accent = 0xFFC4B5FD;
        } else if (EMERALD.equals(theme)) {
            accent = 0xFF6EE7B7;
        } else if (AMBER.equals(theme)) {
            accent = 0xFFFCD34D;
        } else if (ANDROID_DYNAMIC.equals(theme)) {
            accent = getAndroidDynamicAccent(context);
        } else {
            accent = 0xFF7DD3FC;
        }
        return new Palette(accent);
    }

    private static int getAndroidDynamicAccent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0xFF7DD3FC;
        try {
            int id = context.getResources().getIdentifier("system_accent1_200", "color", "android");
            if (id == 0) id = context.getResources().getIdentifier("system_accent1_500", "color", "android");
            if (id != 0) return context.getResources().getColor(id);
        } catch (Throwable ignored) {}
        return 0xFF7DD3FC;
    }

    public static final int CARD = 0xFF0C0C0E;
    public static final int BORDER = 0xFF27272A;
    public static final int INNER = 0xFF121215;

    private static final int[] CARD_FILLS = {
            0xFF0C0D10, 0xFF0B0D12, 0xFF08090C, 0xFF09090B, 0xFF121316, 0xFF060608, 0xFF18191E, 0xFF0C0C0E};
    private static final int[] BORDERS = {
            0xFF1C1C20, 0xFF1B222C, 0xFF18181B, 0xFF3F3F46, 0xFF27272A};

    public static int normalizeFill(Context context, int c) {
        Palette palette = palette(context);
        if (c == LEGACY_ACCENT) return palette.accent;
        if (c == Color.BLACK || c == 0xFF000000 || c == 0xFF060608 || c == 0xFF08090C
                || c == 0xFF09090B || c == 0xFF090A0F) return palette.background;
        for (int f : CARD_FILLS) if (c == f) return palette.card;
        if (c == 0xFF121215 || c == 0xFF14151A || c == 0xFF0E1015) return palette.inner;
        if (c == 0xFF15161A || c == 0xFF16171B || c == 0xFF18181B
                || c == 0xFF18191E || c == 0xFF27272A || c == 0xFF3F3F46) return palette.elevated;
        return c;
    }

    public static int normalizeBorder(Context context, int c) {
        Palette palette = palette(context);
        for (int b : BORDERS) if (c == b) return palette.border;
        // faint white-alpha hairlines
        if (Color.alpha(c) > 0 && Color.alpha(c) < 60 && Color.red(c) > 240 && Color.green(c) > 240) return palette.border;
        return c;
    }

    public static int normalizeAccentColor(Context context, int color) {
        return color == LEGACY_ACCENT ? palette(context).accent : color;
    }

    public static boolean isAccentColor(Context context, int color) {
        return color == LEGACY_ACCENT || color == palette(context).accent;
    }

    public static int normalizeTextColor(Context context, int color) {
        Palette palette = palette(context);
        if (color == LEGACY_ACCENT) return palette.accent;
        if (color == Color.WHITE || color == 0xFFF4F4F5 || color == 0xFFE4E4E7) return palette.textPrimary;
        if (color == 0xFFD4D4D8 || color == 0xFFA1A1AA) return palette.textSecondary;
        if (color == 0xFF71717A || color == 0xFF52525B) return palette.textMuted;
        return color;
    }

    public static void applyTextPalette(Context context, View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            int current = textView.getCurrentTextColor();
            int themed = normalizeTextColor(context, current);
            if (current != themed) textView.setTextColor(themed);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTextPalette(context, group.getChildAt(i));
            }
        }
    }

    private static double luminance(int color) {
        return (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
    }

    private static int blend(int base, int overlay, float ratio) {
        float inverse = 1f - ratio;
        return Color.rgb(
                Math.round(Color.red(base) * inverse + Color.red(overlay) * ratio),
                Math.round(Color.green(base) * inverse + Color.green(overlay) * ratio),
                Math.round(Color.blue(base) * inverse + Color.blue(overlay) * ratio));
    }

    /** Material-like shape scale: cards 16dp, inner panels 12dp, chips 8dp. */
    public static float normalizeRadius(int px, float density) {
        float dp = px / density;
        if (dp >= 13) return 16f * density;
        if (dp >= 9) return 12f * density;
        if (dp >= 5) return 8f * density;
        return px;
    }
}
