package com.jellyarr.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.CheckBox;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;

/** Theme-aware ON colour for switches and dirty-aware SAVE buttons. */
public final class UiStyle {
    private UiStyle() {}

    public interface StringSource { String get(); }

    private static ColorStateList states(int checked, int unchecked) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{checked, unchecked});
    }

    private static GradientDrawable pill(int fill, int stroke, int widthPx, int heightPx, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(heightPx);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(strokePx, stroke);
        g.setSize(widthPx, heightPx);
        return g;
    }

    private static GradientDrawable circle(int fill, int sizePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setSize(sizePx, sizePx);
        return g;
    }

    /**
     * Clear ON / OFF look that follows the theme accent (explicit drawables, so it does not depend on tint support):
     *  ON  = accent track, high-contrast thumb;  OFF = dim outlined track, small muted thumb;
     *  disabled = same shapes at low contrast.
     */
    public static void styleSwitch(Context c, Switch s) {
        if (s instanceof ToggleSwitch) {
            s.invalidate();
            return;
        }
        try {
            float d = c.getResources().getDisplayMetrics().density;
            int trackW = (int) (46 * d), trackH = (int) (26 * d), stroke = Math.max(1, (int) (1.5f * d));
            int thumbOn = (int) (20 * d), thumbOff = (int) (14 * d);

            int accent = AppTheme.accent();
            int onThumb = AppTheme.contrastOn(accent);
            int offTrack = AppTheme.inner();
            int offStroke = AppTheme.outlineStrong();
            int offThumb = AppTheme.textMuted();

            StateListDrawable track = new StateListDrawable();
            track.addState(new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked},
                    pill(AppTheme.withAlpha(accent, 0x55), 0, trackW, trackH, stroke));
            track.addState(new int[]{-android.R.attr.state_enabled},
                    pill(offTrack, AppTheme.withAlpha(offStroke, 0x55), trackW, trackH, stroke));
            track.addState(new int[]{android.R.attr.state_checked}, pill(accent, accent, trackW, trackH, stroke));
            track.addState(new int[]{}, pill(offTrack, offStroke, trackW, trackH, stroke));

            StateListDrawable thumb = new StateListDrawable();
            thumb.addState(new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked},
                    circle(AppTheme.withAlpha(onThumb, 0x99), thumbOn));
            thumb.addState(new int[]{-android.R.attr.state_enabled}, circle(AppTheme.withAlpha(offThumb, 0x66), thumbOff));
            thumb.addState(new int[]{android.R.attr.state_checked}, circle(onThumb, thumbOn));
            thumb.addState(new int[]{}, circle(offThumb, thumbOff));

            s.setTrackDrawable(track);
            s.setThumbDrawable(thumb);
            s.setSwitchMinWidth(trackW);
            s.setThumbTextPadding(0);
            s.setShowText(false);
        } catch (Throwable ignored) {
            // keep the platform look if anything is unsupported
        }
    }

    /** A Switch that already has the themed look. */
    public static Switch newSwitch(Context c) {
        return new ToggleSwitch(c);
    }

    /** Check box with the accent colour when checked. */
    public static CheckBox newCheckBox(Context c) {
        CheckBox cb = new CheckBox(c);
        try {
            cb.setButtonTintList(states(AppTheme.accent(), AppTheme.textMuted()));
        } catch (Throwable ignored) {}
        return cb;
    }

    public static void styleAllSwitches(Context c, View root) {
        if (root instanceof Switch) { styleSwitch(c, (Switch) root); return; }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) styleAllSwitches(c, g.getChildAt(i));
        }
    }

    /** SAVE is dimmed/disabled while the field equals the stored value. Returns a re-evaluate Runnable. */
    public static Runnable bindDirty(final Button save, final EditText input, final StringSource saved) {
        final Runnable update = new Runnable() {
            @Override public void run() {
                boolean dirty;
                try { dirty = !input.getText().toString().trim().equals(String.valueOf(saved.get()).trim()); }
                catch (Throwable t) { dirty = true; }
                save.setEnabled(dirty);
                save.setAlpha(dirty ? 1f : 0.38f);
            }
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { update.run(); }
        });
        update.run();
        return update;
    }
}
