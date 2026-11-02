package com.patrisius.cahaya.ui.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import is.xyz.mpv.MPVLib;

/**
 * Subtitles for vo=mediacodec_embed. mpv still decodes the selected subtitle
 * track and exposes the current line as the "sub-text" property (documented
 * as independent of sub-visibility), so we draw it ourselves in a
 * {@link SubtitleView}. Text comes from two sources:
 *  - mpv property-change events (low latency; never blocks the UI thread), and
 *  - a slow background poll as a safety net (mpv only notifies on change).
 * All style settings from {@link SubtitlePrefs} are applied here.
 *
 * Two text sources, never mixed:
 *  - MPV mode (default): embedded tracks; text comes from mpv's "sub-text".
 *  - CUE mode ({@link #setCues}): online subtitles we parsed ourselves; text is
 *    looked up from the playback time, so it works the instant a seek lands and
 *    does not depend on mpv having the file selected at all.
 */
public class SubtitleOverlay {
    private static final String SAMPLE = "This is how your subtitles will look.\nSecond line.";
    private static final float BASE_TEXT_FRACTION = 0.050f;   // of screen height at 100 %

    private final Activity activity;
    private final SubtitlePrefs prefs;
    private final SubtitleView view;
    private final ImageView bitmapView;   // v1.19.0: bitmap subtitles (PGS/VobSub)
    private final FrameLayout.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int screenW, screenH;

    private volatile String text = "";
    /** Non-null = CUE mode. */
    private volatile java.util.List<SubtitleCue> cues;
    /** Non-null = BITMAP mode (v1.19.0). Takes precedence over CUE/MPV. */
    private volatile BitmapSubsDecoder bitmapDecoder;
    private volatile int videoW, videoH;   // video frame size, for bitmap scaling
    private SubtitleEvent shownBitmapEvent;  // last event drawn (poll thread only)
    private volatile int delayMs;
    private volatile double lastPos = -1;
    private boolean subtitlesOn = true, preview = false, lifted = false;
    private volatile boolean running;
    private HandlerThread pollThread;
    private Handler pollHandler;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                BitmapSubsDecoder bd = bitmapDecoder;
                if (bd != null) {
                    updateBitmap(bd);                           // BITMAP mode
                } else if (cues != null) {
                    lookup();                                   // paused / delay changed: re-evaluate
                } else {
                    String t = MPVLib.getPropertyString("sub-text");
                    setText(t == null ? "" : t);
                }
            } catch (Throwable ignored) {}
            if (pollHandler != null) pollHandler.postDelayed(this, 250);
        }
    };

    public SubtitleOverlay(Activity activity, FrameLayout root, SubtitlePrefs prefs) {
        this.activity = activity;
        this.prefs = prefs;
        Point p = new Point();
        activity.getWindowManager().getDefaultDisplay().getRealSize(p);
        screenW = Math.max(p.x, p.y);
        screenH = Math.min(p.x, p.y);

        view = new SubtitleView(activity);
        view.setMaxWidthPx((int) (screenW * 0.80f));
        view.setVisibility(View.GONE);
        lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(view, lp);
        // v1.19.0: bitmap subtitle layer (PGS/VobSub). Positioned per-event.
        bitmapView = new ImageView(activity);
        bitmapView.setVisibility(View.GONE);
        bitmapView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(bitmapView, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        applyStyle();
    }

    // ---- bitmap mode (v1.19.0) -------------------------------------------

    /** Video frame size, for scaling PGS coordinates to the screen. */
    public void setVideoSize(int w, int h) {
        videoW = w;
        videoH = h;
    }

    /**
     * Switch to BITMAP mode with this decoder, or back to text modes with null.
     * The decoder is owned by the caller; it is closed via {@link #clearBitmapDecoder}.
     */
    public void setBitmapDecoder(BitmapSubsDecoder d) {
        bitmapDecoder = d;
        shownBitmapEvent = null;
        ui.post(() -> {
            bitmapView.setImageDrawable(null);
            bitmapView.setVisibility(View.GONE);
            if (d == null) refresh();  // back to text: re-evaluate
            else view.setVisibility(View.GONE);
        });
        if (d != null) setText("");
    }

    public boolean isBitmapMode() { return bitmapDecoder != null; }

    public void clearBitmapDecoder() {
        BitmapSubsDecoder d = bitmapDecoder;
        bitmapDecoder = null;
        shownBitmapEvent = null;
        if (d != null) {
            new Thread(() -> {
                try { d.close(); } catch (Throwable ignored) {}
            }, "bitmap-subs-close").start();
        }
        ui.post(() -> {
            bitmapView.setImageDrawable(null);
            bitmapView.setVisibility(View.GONE);
        });
    }

    /** Poll thread only. */
    private void updateBitmap(BitmapSubsDecoder bd) {
        if (lastPos < 0) {
            hideBitmap();
            return;
        }
        long t = (long) (lastPos * 1000) - delayMs;
        // We need the event to position the view; ask the decoder for the
        // bitmap and fetch the event via a small accessor.
        Bitmap bmp = bd.getAt(t);
        final SubtitleEvent ev = bd.currentEvent();
        if (bmp == null || ev == null || !subtitlesOn) {
            if (shownBitmapEvent != null) hideBitmap();
            return;
        }
        if (ev == shownBitmapEvent) return;  // already showing
        shownBitmapEvent = ev;
        // Scale PGS (video-frame) coordinates to the screen (Fit).
        int vw = videoW > 0 ? videoW : 1920;
        int vh = videoH > 0 ? videoH : 1080;
        float scale = Math.min((float) screenW / vw, (float) screenH / vh);
        int ox = (int) ((screenW - vw * scale) / 2);
        int oy = (int) ((screenH - vh * scale) / 2);
        final int bx = ox + (int) (ev.x * scale);
        final int by = oy + (int) (ev.y * scale);
        final int bw = Math.max(1, (int) (ev.w * scale));
        final int bh = Math.max(1, (int) (ev.h * scale));
        final Bitmap fb = bmp;
        ui.post(() -> {
            FrameLayout.LayoutParams blp =
                    new FrameLayout.LayoutParams(bw, bh, Gravity.TOP | Gravity.START);
            blp.leftMargin = bx;
            blp.topMargin = by;
            bitmapView.setLayoutParams(blp);
            bitmapView.setImageBitmap(fb);
            bitmapView.setVisibility(View.VISIBLE);
            view.setVisibility(View.GONE);
        });
    }

    private void hideBitmap() {
        shownBitmapEvent = null;
        ui.post(() -> {
            bitmapView.setImageDrawable(null);
            bitmapView.setVisibility(View.GONE);
        });
    }

    // ---- text ------------------------------------------------------------

    /** mpv's sub-text value (any thread). Ignored in CUE mode. */
    public void onText(String t) {
        if (cues != null) return;
        setText(t);
    }

    private void setText(String t) {
        final String n = t == null ? "" : t.replace("\r", "").trim();
        if (n.equals(text)) return;
        text = n;
        ui.post(this::refresh);
    }

    /** Switch to CUE mode with these cues, or back to MPV mode with null. v1.19.0: also leaves BITMAP mode. */
    public void setCues(java.util.List<SubtitleCue> list) {
        if (bitmapDecoder != null) clearBitmapDecoder();
        cues = list;
        text = "";
        ui.post(this::refresh);
        if (list != null) lookup();
    }

    public boolean isCueMode() { return cues != null; }

    /** Subtitle delay in ms (positive = later), same sign as mpv's sub-delay. */
    public void setDelayMs(int ms) {
        delayMs = ms;
        if (cues != null) lookup();
    }

    /** Playback position in seconds (any thread). Only used in CUE mode. */
    public void onTime(double seconds) {
        lastPos = seconds;
        if (cues != null) lookup();
    }

    private void lookup() {
        java.util.List<SubtitleCue> c = cues;
        if (c == null) return;
        if (lastPos < 0) { setText(""); return; }
        setText(CueParser.textAt(c, (long) (lastPos * 1000) - delayMs, prefs.stripSdh));
    }

    /** Forget the current line (track switched / subtitles off). */
    public void clear() {
        text = "";
        ui.post(this::refresh);
    }

    /** False while the user has subtitles switched off. */
    public void setSubtitlesOn(boolean on) {
        subtitlesOn = on;
        if (!on) text = "";
        ui.post(this::refresh);
    }

    /** Show sample text (used by the appearance menu so changes are visible live). */
    public void setPreview(boolean on) {
        preview = on;
        ui.post(this::refresh);
    }

    /** Raise the subtitles above the control bar while it is on screen. */
    public void setLifted(boolean up) {
        if (lifted == up) return;
        lifted = up;
        view.animate().cancel();
        view.animate().translationY(up ? -screenH * 0.17f : 0f).setDuration(180).start();
    }

    private void refresh() {
        String shown = text;
        if (shown.isEmpty() && preview) shown = SAMPLE;
        if (shown.isEmpty() || (!subtitlesOn && !preview)) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(shown);
        view.setVisibility(View.VISIBLE);
    }

    // ---- style -----------------------------------------------------------

    /** Re-read every setting from {@link SubtitlePrefs}. Call after any change. */
    public void applyStyle() {
        float px = screenH * BASE_TEXT_FRACTION * (prefs.size / 100f);
        view.setStyle(px, SubtitlePrefs.COLORS[prefs.colorIdx], prefs.bold, prefs.edge,
                prefs.background, prefs.bgOpacity);
        lp.bottomMargin = (int) (screenH * (0.045f + prefs.lift / 100f));
        view.setLayoutParams(lp);
    }

    // ---- lifecycle -------------------------------------------------------

    public void start() {
        if (running) return;
        running = true;
        pollThread = new HandlerThread("sub-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        pollHandler.postDelayed(poll, 500);
    }

    public void stop() {
        running = false;
        if (pollHandler != null) pollHandler.removeCallbacksAndMessages(null);
        if (pollThread != null) { pollThread.quitSafely(); pollThread = null; }
        pollHandler = null;
        ui.removeCallbacksAndMessages(null);
    }
}
