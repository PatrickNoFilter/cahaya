package com.patrisius.cahaya.ui.player;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import is.xyz.mpv.MPVLib;

/**
 * Renders subtitles as an Android TextView overlay on top of the video surface.
 *
 * Why: vo=mediacodec_embed (required for Mali-G31) cannot render subtitles via mpv.
 * Instead, we observe mpv's "sub-text" property and display it ourselves.
 * This is the same approach used by ExoPlayer-based players (like NuvioTV).
 */
public class SubtitleOverlay {
    private final Activity activity;
    private final TextView textView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private String lastText = "";

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                String text = MPVLib.getPropertyString("sub-text");
                if (text == null) text = "";
                if (!text.equals(lastText)) {
                    lastText = text;
                    final String display = text;
                    activity.runOnUiThread(() -> {
                        if (display.isEmpty()) {
                            textView.setVisibility(android.view.View.GONE);
                        } else {
                            textView.setText(display);
                            textView.setVisibility(android.view.View.VISIBLE);
                        }
                    });
                }
            } catch (Throwable ignored) {}
            handler.postDelayed(this, 200); // Poll every 200ms
        }
    };

    public SubtitleOverlay(Activity activity, FrameLayout root) {
        this.activity = activity;
        textView = new TextView(activity);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(20);
        textView.setGravity(Gravity.CENTER);
        textView.setShadowLayer(4, 0, 2, Color.BLACK); // Outline for readability
        textView.setPadding(32, 16, 32, 16);
        textView.setVisibility(android.view.View.GONE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = 120; // Above the control bar
        root.addView(textView, lp);
    }

    public void start() {
        if (running) return;
        running = true;
        handler.post(pollTask);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollTask);
        activity.runOnUiThread(() -> textView.setVisibility(android.view.View.GONE));
    }

    public void setVisible(boolean visible) {
        activity.runOnUiThread(() -> {
            if (!visible) textView.setVisibility(android.view.View.GONE);
            // If visible, the poll task will show it when there's text
        });
    }
}
