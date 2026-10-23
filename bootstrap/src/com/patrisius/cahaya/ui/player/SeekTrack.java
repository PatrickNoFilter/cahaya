package com.patrisius.cahaya.ui.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Thin progress bar that grows when focused. D-pad left/right scrubs with
 * acceleration (see ScrubRates); the seek is committed after a short pause so
 * a burst of key presses becomes one seek. Touch drag also works.
 */
public class SeekTrack extends View {
    public interface Listener {
        /** Preview position while scrubbing (seconds). */
        void onScrub(double seconds);
        /** Final position to seek to (seconds). */
        void onCommit(double seconds);
    }

    private static final long COMMIT_DELAY_MS = 450;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float dp;
    private Listener listener;
    private double pos, dur;
    private boolean scrubbing;
    private double scrubPos;

    private final Runnable commit = new Runnable() {
        @Override public void run() {
            if (!scrubbing) return;
            scrubbing = false;
            pos = scrubPos;
            invalidate();
            if (listener != null) listener.onCommit(scrubPos);
        }
    };

    public SeekTrack(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setContentDescription("Seek bar");
    }

    public void setListener(Listener l) { listener = l; }

    /** Update from the player. Ignored while the user is mid-scrub. */
    public void setProgress(double position, double duration) {
        if (scrubbing) return;
        pos = position;
        dur = duration;
        invalidate();
    }

    public boolean isScrubbing() { return scrubbing; }

    @Override
    protected void onMeasure(int w, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (28 * dp));
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean f = isFocused() || scrubbing;
        float th = (f ? 7 : 4) * dp;
        float cy = getHeight() / 2f;
        float left = 2 * dp, right = getWidth() - 2 * dp;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x40FFFFFF);
        r.set(left, cy - th / 2, right, cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, paint);
        double p = scrubbing ? scrubPos : pos;
        float frac = dur > 0 ? (float) Math.max(0, Math.min(1, p / dur)) : 0f;
        float x = left + (right - left) * frac;
        paint.setColor(PlayerTheme.ACCENT);
        r.set(left, cy - th / 2, Math.max(left + th, x), cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, paint);
        if (f && dur > 0) {
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(x, cy, 9 * dp, paint);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (dur > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            boolean fwd = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            double base = scrubbing ? scrubPos : pos;
            scrubPos = Math.max(0, Math.min(dur, base + ScrubRates.deltaMs(e.getRepeatCount(), fwd) / 1000.0));
            scrubbing = true;
            invalidate();
            if (listener != null) listener.onScrub(scrubPos);
            h.removeCallbacks(commit);
            h.postDelayed(commit, COMMIT_DELAY_MS);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (dur <= 0) return super.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            float left = 2 * dp, right = getWidth() - 2 * dp;
            scrubPos = dur * Math.max(0, Math.min(1, (e.getX() - left) / (right - left)));
            scrubbing = true;
            invalidate();
            if (listener != null) listener.onScrub(scrubPos);
            h.removeCallbacks(commit);
            return true;
        }
        if (a == MotionEvent.ACTION_UP) { h.removeCallbacks(commit); commit.run(); return true; }
        if (a == MotionEvent.ACTION_CANCEL) { scrubbing = false; invalidate(); return true; }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
