package com.patrisius.cahaya.ui.player;

import android.app.Activity;
import android.graphics.Point;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

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
    private final FrameLayout.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int screenW, screenH;

    private volatile String text = "";
    /** Non-null = CUE mode. */
    private volatile java.util.List<SubtitleCue> cues;
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
                if (cues != null) {
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
        applyStyle();
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

    /** Switch to CUE mode with these cues, or back to MPV mode with null. */
    public void setCues(java.util.List<SubtitleCue> list) {
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
