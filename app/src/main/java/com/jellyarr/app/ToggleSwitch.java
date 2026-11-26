package com.jellyarr.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Switch;

/**
 * The one on/off control used everywhere in the app.
 *
 * The framework Switch stretches a custom thumb drawable to the full track height and pins it to the track edges,
 * which is what made the old switches look squashed and off-centre. This one draws itself: a 52 x 32 dp pill track
 * and a round thumb that grows when on, with a small check mark, following the theme accent. It keeps the Switch API
 * (setChecked, listeners, accessibility role), so every call site stays the same.
 */
public class ToggleSwitch extends Switch {

    private static final float TRACK_W = 52f, TRACK_H = 32f, THUMB_ON = 24f, THUMB_OFF = 16f, THUMB_PRESSED = 28f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final RectF inner = new RectF();
    private final Path check = new Path();
    private final float d;
    private float pos;
    private ValueAnimator anim;
    private boolean ready;

    public ToggleSwitch(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setShowText(false);
        setText(null);
        setTextOn(null);
        setTextOff(null);
        setBackground(null);
        setPadding(0, 0, 0, 0);
        setClickable(true);
        setFocusable(true);
        pos = isChecked() ? 1f : 0f;
        ready = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 48 dp tall touch target around the 32 dp track
        int w = (int) Math.ceil(TRACK_W * d) + getPaddingLeft() + getPaddingRight();
        int h = (int) Math.ceil(48f * d) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (!ready) return;
        float target = checked ? 1f : 0f;
        if (anim != null) anim.cancel();
        if (!changed || !isLaidOut() || getWindowToken() == null) {
            pos = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // Tap to toggle. The framework drag logic is skipped: it moves a thumb this view does not draw.
        if (!isEnabled()) return isClickable();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                return true;
            case MotionEvent.ACTION_UP:
                boolean inside = e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight();
                setPressed(false);
                if (inside) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float tw = TRACK_W * d, th = TRACK_H * d;
        float cx = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f;
        float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        track.set(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);

        boolean enabled = isEnabled();
        int accent = AppTheme.accent();
        int offTrack = AppTheme.inner();
        int offStroke = AppTheme.outlineStrong();
        int offThumb = AppTheme.textMuted();
        int onThumb = AppTheme.contrastOn(accent);
        float p = pos;

        int trackFill = AppTheme.mix(offTrack, accent, p);
        int thumbFill = AppTheme.mix(offThumb, onThumb, p);
        int alpha = enabled ? 255 : 97;

        // track
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackFill);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(track, th / 2f, th / 2f, paint);
        if (p < 1f) {
            float sw = 2f * d;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw);
            paint.setColor(AppTheme.mix(offStroke, accent, p));
            paint.setAlpha(alpha);
            inner.set(track.left + sw / 2f, track.top + sw / 2f, track.right - sw / 2f, track.bottom - sw / 2f);
            canvas.drawRoundRect(inner, inner.height() / 2f, inner.height() / 2f, paint);
        }

        // thumb
        float size = isPressed() && enabled ? THUMB_PRESSED : THUMB_OFF + (THUMB_ON - THUMB_OFF) * p;
        float r = size * d / 2f;
        float startX = track.left + th / 2f, endX = track.right - th / 2f;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float x = rtl ? endX - (endX - startX) * p : startX + (endX - startX) * p;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(thumbFill);
        paint.setAlpha(alpha);
        canvas.drawCircle(x, cy, r, paint);

        // check mark on the "on" thumb
        if (p > 0.6f) {
            float a = (p - 0.6f) / 0.4f;
            float s = 5.5f * d;
            check.reset();
            check.moveTo(x - s, cy);
            check.lineTo(x - s * 0.3f, cy + s * 0.7f);
            check.lineTo(x + s, cy - s * 0.65f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(accent);
            paint.setAlpha((int) (alpha * a));
            canvas.drawPath(check, paint);
        }
    }
}
