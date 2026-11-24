package com.fishbowl.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Shared design system for every screen (Material 3 roles from {@link AppTheme}).
 *
 * Scale: page gutter 16dp, card padding 16dp, gap 12dp; radii 16 (cards / dialogs), 12 (inner boxes,
 * inputs, buttons), full (chips / pills). Type: display 22sp, title 17sp, subtitle 14sp, body 13.5sp,
 * caption 12sp, overline 11sp. Monospace only for paths, keys and URLs.
 */
public final class UiKit {

    private UiKit() {}

    public static final int R_CARD = 16;
    public static final int R_INNER = 12;
    public static final int R_PILL = 999;

    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ backgrounds

    public static GradientDrawable shape(Context c, int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp >= R_PILL ? dp(c, 200) : dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    /** Shape with a touch ripple (API 21+). */
    public static Drawable pressable(Context c, int fill, int stroke, int radiusDp) {
        GradientDrawable base = shape(c, fill, stroke, radiusDp);
        if (Build.VERSION.SDK_INT < 21) return base;
        GradientDrawable mask = shape(c, 0xFFFFFFFF, 0, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(AppTheme.withAlpha(AppTheme.text(), 0x22)), base, mask);
    }

    /** Transparent ripple for list rows. */
    public static Drawable rowRipple(Context c) {
        if (Build.VERSION.SDK_INT < 21) return null;
        GradientDrawable mask = shape(c, 0xFFFFFFFF, 0, R_INNER);
        return new RippleDrawable(ColorStateList.valueOf(AppTheme.withAlpha(AppTheme.text(), 0x18)), null, mask);
    }

    // ------------------------------------------------------------------ containers

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Elevated surface card with 16dp padding and a 12dp gap below. */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        l.setBackground(shape(c, AppTheme.card(), AppTheme.outline(), R_CARD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    /** Recessed inner box (stats, code, file info). */
    public static LinearLayout inset(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        l.setBackground(shape(c, AppTheme.inner(), 0, R_INNER));
        return l;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(AppTheme.outline());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    public static View space(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    // ------------------------------------------------------------------ text

    public static TextView text(Context c, CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setIncludeFontPadding(true);
        return t;
    }

    public static TextView display(Context c, CharSequence s) {
        TextView t = text(c, s, 22f, AppTheme.text(), BOLD);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView title(Context c, CharSequence s) {
        return text(c, s, 17f, AppTheme.text(), BOLD);
    }

    public static TextView subtitle(Context c, CharSequence s) {
        return text(c, s, 14f, AppTheme.text(), MEDIUM);
    }

    public static TextView body(Context c, CharSequence s) {
        TextView t = text(c, s, 13.5f, AppTheme.textSecondary(), null);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public static TextView caption(Context c, CharSequence s) {
        return text(c, s, 12f, AppTheme.textMuted(), null);
    }

    /** Small uppercase section header. */
    public static TextView overline(Context c, CharSequence s) {
        TextView t = text(c, s, 11f, AppTheme.textMuted(), BOLD);
        t.setLetterSpacing(0.08f);
        t.setAllCaps(true);
        t.setPadding(0, dp(c, 4), 0, dp(c, 8));
        return t;
    }

    public static TextView mono(Context c, CharSequence s) {
        TextView t = text(c, s, 12f, AppTheme.text(), Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    public static TextView singleLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ------------------------------------------------------------------ controls

    /** Filled primary button (accent). */
    public static Button primary(Context c, CharSequence label, View.OnClickListener l) {
        return button(c, label, AppTheme.accent(), AppTheme.onAccent(), 0, l);
    }

    /** Tonal button (accent container). */
    public static Button tonal(Context c, CharSequence label, View.OnClickListener l) {
        return button(c, label, AppTheme.accentContainer(), AppTheme.onAccentContainer(), 0, l);
    }

    /** Outlined secondary button. */
    public static Button outline(Context c, CharSequence label, View.OnClickListener l) {
        return button(c, label, AppTheme.card(), AppTheme.text(), AppTheme.outlineStrong(), l);
    }

    /** Destructive outlined button. */
    public static Button danger(Context c, CharSequence label, View.OnClickListener l) {
        return button(c, label, AppTheme.card(), AppTheme.danger(), AppTheme.withAlpha(AppTheme.danger(), 0x99), l);
    }

    public static Button button(Context c, CharSequence label, int fill, int fg, int stroke, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTypeface(BOLD);
        b.setTextColor(fg);
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setBackground(pressable(c, fill, stroke, R_INNER));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    /** Equal-width button row; pass buttons in reading order. */
    public static LinearLayout buttonRow(Context c, View... buttons) {
        LinearLayout r = row(c);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 42), 1f);
            if (i > 0) lp.setMarginStart(dp(c, 8));
            r.addView(buttons[i], lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(c, 10);
        r.setLayoutParams(rlp);
        return r;
    }

    /** Chip / segmented pill. */
    public static TextView chip(Context c, CharSequence label, boolean selected) {
        TextView t = text(c, label, 12.5f, 0, MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        styleChip(c, t, selected);
        return t;
    }

    public static void styleChip(Context c, TextView t, boolean selected) {
        t.setTextColor(selected ? AppTheme.onAccentContainer() : AppTheme.textSecondary());
        t.setTypeface(selected ? BOLD : MEDIUM);
        t.setBackground(pressable(c, selected ? AppTheme.accentContainer() : AppTheme.card(),
                selected ? 0 : AppTheme.outline(), R_PILL));
    }

    /** Horizontally scrolling row of chips (tag the scroller so swipe gestures leave it alone). */
    public static HorizontalScrollView chipScroller(Context c, LinearLayout chips) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        h.setTag("h-scroll");
        h.addView(chips);
        return h;
    }

    public static LinearLayout.LayoutParams chipLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(c, 8));
        return lp;
    }

    /** Small status badge (dot + label). */
    public static TextView badge(Context c, CharSequence label, int color) {
        TextView t = text(c, "●  " + label, 11f, color, BOLD);
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setBackground(shape(c, AppTheme.withAlpha(color, 0x22), 0, R_PILL));
        return t;
    }

    public static EditText input(Context c, CharSequence hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(14f);
        e.setTextColor(AppTheme.text());
        e.setHintTextColor(AppTheme.textMuted());
        e.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        e.setBackground(shape(c, AppTheme.inner(), AppTheme.outline(), R_INNER));
        return e;
    }

    /** Thin progress bar (0..100). */
    public static View progress(Context c, int percent, int color) {
        LinearLayout track = row(c);
        track.setBackground(shape(c, AppTheme.inner(), 0, R_PILL));
        int p = Math.max(0, Math.min(100, percent));
        View fill = new View(c);
        fill.setBackground(shape(c, color, 0, R_PILL));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, Math.max(p, 0.001f)));
        View rest = new View(c);
        track.addView(rest, new LinearLayout.LayoutParams(0, -1, Math.max(100 - p, 0.001f)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 5));
        lp.topMargin = dp(c, 6);
        track.setLayoutParams(lp);
        return track;
    }

    // ------------------------------------------------------------------ states

    public static View loading(Context c, CharSequence message) {
        LinearLayout l = column(c);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(c, 40), 0, dp(c, 40));
        ProgressBar pb = new ProgressBar(c);
        if (Build.VERSION.SDK_INT >= 21) pb.setIndeterminateTintList(ColorStateList.valueOf(AppTheme.accent()));
        l.addView(pb, new LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)));
        TextView t = caption(c, message);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 12), 0, 0);
        l.addView(t);
        return l;
    }

    public static View empty(Context c, CharSequence titleText, CharSequence message) {
        LinearLayout l = column(c);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(c, 24), dp(c, 40), dp(c, 24), dp(c, 40));
        TextView t = subtitle(c, titleText);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        if (message != null && message.length() > 0) {
            TextView m = caption(c, message);
            m.setGravity(Gravity.CENTER);
            m.setPadding(0, dp(c, 6), 0, 0);
            l.addView(m);
        }
        return l;
    }

    /** Error / service-offline card with an optional retry action. */
    public static View error(Context c, CharSequence titleText, CharSequence message, CharSequence retryLabel, View.OnClickListener retry) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        l.setBackground(shape(c, AppTheme.dangerContainer(), 0, R_CARD));
        TextView t = text(c, titleText, 14.5f, AppTheme.text(), BOLD);
        l.addView(t);
        if (message != null && message.length() > 0) {
            TextView m = body(c, message);
            m.setPadding(0, dp(c, 4), 0, 0);
            l.addView(m);
        }
        if (retry != null) {
            Button b = outline(c, retryLabel, retry);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, 40));
            lp.topMargin = dp(c, 12);
            l.addView(b, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    /** Label / value row for detail screens. */
    public static View kv(Context c, CharSequence key, CharSequence value) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(c, 5), 0, dp(c, 5));
        TextView k = caption(c, key);
        r.addView(k, new LinearLayout.LayoutParams(0, -2, 0.42f));
        TextView v = text(c, value, 13f, AppTheme.text(), null);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 0.58f));
        return r;
    }

    /** Standard page header: big title, one-line subtitle, optional trailing view. */
    public static LinearLayout header(Context c, CharSequence titleText, CharSequence sub, View trailing) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 4), 0, dp(c, 12));
        LinearLayout col = column(c);
        col.addView(display(c, titleText));
        if (sub != null && sub.length() > 0) {
            TextView s = caption(c, sub);
            s.setPadding(0, dp(c, 2), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) r.addView(trailing);
        return r;
    }

    /** Page padding (16dp gutter) applied to a root column. */
    public static void pagePadding(View v) {
        Context c = v.getContext();
        v.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 24));
    }

    public static ViewGroup.LayoutParams matchWrap() {
        return new ViewGroup.LayoutParams(-1, -2);
    }
}
