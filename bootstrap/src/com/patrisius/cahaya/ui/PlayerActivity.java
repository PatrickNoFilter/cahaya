package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import is.xyz.mpv.MPVLib;

import com.patrisius.cahaya.CahayaApp;
import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.addon.SubtitleItem;
import com.patrisius.cahaya.torrent.TorrentStreamer;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;

/**
 * v0.10 player: libmpv on a SurfaceView, D-pad controller overlay.
 * mpv handles all codecs via FFmpeg, subtitles via libass.
 * Launched with extras: url (required), title, type, video_id.
 */
public class PlayerActivity extends Activity implements MPVLib.EventObserver {

    private static final int SEEK_S = 10;
    private static final int HIDE_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private SurfaceView surfaceView;
    private View loadingView;
    private LinearLayout controller;
    private Button playPauseBtn;
    private SeekBar seekBar;
    private TextView timeView;
    private TextView titleView;

    private String streamUrl;
    private String title;
    // Manual position tracking (seconds) — mpv's time-pos property is unavailable
    // for our HTTP stream, so we track it ourselves. Incremented by progressTick,
    // adjusted on seek, reset on loadfile.
    private double manualPos = 0;
    private boolean isPlaying = false;
    // Values from mpv eventProperty callbacks — more reliable than getPropertyDouble
    // polling for streams. -1 means unknown.
    private volatile double mpvTimePos = -1;
    private volatile double mpvDuration = -1;

    private final Runnable hideController = () -> setControllerVisible(false);
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            // Increment manual position if playing (mpv time-pos unavailable)
            if (isPlaying) {
                manualPos += 1.0;
            }
            updateProgress();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        streamUrl = getIntent().getStringExtra("url");
        title = getIntent().getStringExtra("title");
        if (title == null) title = "Video";

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Video surface
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                try {
                    MPVLib.attachSurface(holder.getSurface());
                    MPVLib.setOptionString("force-window", "yes");
                    // Use mediacodec_embed instead of gpu to avoid Mali GPU faults.
                    // mediacodec_embed renders MediaCodec output directly to the Surface.
                    MPVLib.setOptionString("vo", "mediacodec_embed");
                } catch (Throwable ignored) {}
                surfaceReady = true;
                maybeStartPlayback();
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                try {
                    MPVLib.setPropertyString("vo", "null");
                    MPVLib.setOptionString("force-window", "no");
                    MPVLib.detachSurface();
                } catch (Throwable ignored) {}
            }
        });
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Loading indicator
        TextView loading = new TextView(this);
        loading.setText("Loading…");
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(20);
        loading.setGravity(android.view.Gravity.CENTER);
        loadingView = loading;
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Controller overlay
        controller = new LinearLayout(this);
        controller.setOrientation(LinearLayout.VERTICAL);
        controller.setBackgroundColor(0xCC000000);
        controller.setPadding(dp(16), dp(12), dp(16), dp(12));
        controller.setVisibility(View.GONE);

        // Top bar: Back + Title
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button backBtn = new Button(this);
        backBtn.setText("◀ Back");
        backBtn.setFocusable(true);
        backBtn.setOnClickListener(v -> finish());
        topBar.addView(backBtn);
        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(dp(16), 0, 0, 0);
        topBar.addView(titleView, tlp);
        controller.addView(topBar);

        // Spacer
        View spacer = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        controller.addView(spacer, slp);

        // Bottom controls: Rewind | Play/Pause | Forward
        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(android.view.Gravity.CENTER);
        controlsRow.setPadding(0, dp(8), 0, dp(8));

        Button rewindBtn = new Button(this);
        rewindBtn.setText("⏪ -10s");
        rewindBtn.setFocusable(true);
        rewindBtn.setOnClickListener(v -> {
            MPVLib.command(new String[]{"seek", String.valueOf(-SEEK_S)});
            manualPos = Math.max(0, manualPos - SEEK_S);
            main.post(() -> updateProgress());
            setControllerVisible(true);
        });
        controlsRow.addView(rewindBtn);

        playPauseBtn = new Button(this);
        playPauseBtn.setText("⏸ Pause");
        playPauseBtn.setFocusable(true);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pblp.setMargins(dp(24), 0, dp(24), 0);
        playPauseBtn.setOnClickListener(v -> togglePlay());
        controlsRow.addView(playPauseBtn, pblp);

        Button forwardBtn = new Button(this);
        forwardBtn.setText("+10s ⏩");
        forwardBtn.setFocusable(true);
        forwardBtn.setOnClickListener(v -> {
            MPVLib.command(new String[]{"seek", String.valueOf(SEEK_S)});
            manualPos += SEEK_S;
            main.post(() -> updateProgress());
            setControllerVisible(true);
        });
        controlsRow.addView(forwardBtn);
        controller.addView(controlsRow);

        // Seekbar row: Time | SeekBar (seeking disabled - duration unknown)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        timeView = new TextView(this);
        timeView.setTextColor(Color.WHITE);
        timeView.setText("0:00 / --:--");
        row.addView(timeView);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setEnabled(false); // Duration unknown, can't seek by fraction
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbp.setMargins(dp(12), 0, dp(12), 0);
        row.addView(seekBar, sbp);

        controller.addView(row);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = android.view.Gravity.BOTTOM;
        root.addView(controller, cp);

        setContentView(root);

        initPlayer();
    }

    private boolean mpvReady, surfaceReady, fileStarted;
    private boolean wasPlayingOnPause;

    private void initPlayer() {
        try {
            MPVLib.create(this);
            MPVLib.setEventObserver(this);

            // No VO yet: the surface arrives later via surfaceCreated().
            // Starting with vo=gpu before a surface exists races VO init.
            MPVLib.setOptionString("vo", "null");
            MPVLib.setOptionString("force-window", "no");
            MPVLib.setOptionString("gpu-context", "android");
            MPVLib.setOptionString("opengl-es", "yes");
            // Use mediacodec (zero-copy) with mediacodec_embed VO. Avoid mediacodec-copy
            // which goes through the GPU and causes Mali faults.
            MPVLib.setOptionString("hwdec", "mediacodec");
            MPVLib.setOptionString("ao", "audiotrack,opensles");
            MPVLib.setOptionString("audio-channels", "stereo");
            MPVLib.setOptionString("cache", "yes");
            MPVLib.setOptionString("cache-secs", "10");
            MPVLib.setOptionString("idle", "yes");

            MPVLib.init();
            mpvReady = true;

            // Observe properties (3=FLAG for pause, 5=DOUBLE for time-pos/duration)
            MPVLib.observeProperty("time-pos", 5);
            MPVLib.observeProperty("duration", 5);
            MPVLib.observeProperty("pause", 3);

            main.post(progressTick);
            maybeStartPlayback();
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't play: " + t.toString(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** Load the file only once both mpv and the surface are ready. */
    private void maybeStartPlayback() {
        if (mpvReady && surfaceReady && !fileStarted) {
            fileStarted = true;
            // Reset manual position tracking for new file
            manualPos = 0;
            isPlaying = true;
            mpvTimePos = -1;
            mpvDuration = -1;
            try {
                MPVLib.command(new String[]{"loadfile", streamUrl});
            } catch (Throwable t) {
                Toast.makeText(this, "Couldn't play: " + t.toString(),
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void togglePlay() {
        boolean paused = MPVLib.getPropertyBoolean("pause");
        MPVLib.setPropertyBoolean("pause", !paused);
        isPlaying = paused; // paused was true -> now playing
        playPauseBtn.setText(paused ? "⏸ Pause" : "▶ Play");
    }

    private void updateProgress() {
        try {
            // Prefer mpv's reported values (via eventProperty callbacks).
            // Fallback to manual tracking if mpv hasn't reported yet.
            double pos = (mpvTimePos >= 0) ? mpvTimePos : manualPos;
            double dur = mpvDuration;
            if (pos >= 0) {
                if (dur > 1.0) {
                    // We know duration: show "1:23 / 45:67" and enable seekbar
                    timeView.setText(fmt((long)(pos*1000)) + " / " + fmt((long)(dur*1000)));
                    int prog = (int) (pos * 1000 / dur);
                    if (prog < 0) prog = 0;
                    if (prog > 1000) prog = 1000;
                    seekBar.setProgress(prog);
                    seekBar.setEnabled(true);
                } else {
                    // Duration unknown: show "1:23 / --:--", disable seekbar
                    timeView.setText(fmt((long)(pos*1000)) + " / --:--");
                    seekBar.setEnabled(false);
                }
            }
            // Hide loading once we have video (pos > 0 means playback started)
            if (pos > 0 && loadingView.getVisibility() == View.VISIBLE) {
                loadingView.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {}
    }

    private void setControllerVisible(boolean visible) {
        controller.setVisibility(visible ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hideController);
        if (visible) main.postDelayed(hideController, HIDE_MS);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (controller.getVisibility() == View.VISIBLE) {
                    return super.onKeyDown(keyCode, event);
                }
                setControllerVisible(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                MPVLib.command(new String[]{"seek", String.valueOf(-SEEK_S)});
                manualPos = Math.max(0, manualPos - SEEK_S);
                setControllerVisible(true);
                main.post(() -> updateProgress());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                MPVLib.command(new String[]{"seek", String.valueOf(SEEK_S)});
                manualPos += SEEK_S;
                setControllerVisible(true);
                main.post(() -> updateProgress());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                setControllerVisible(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (controller.getVisibility() == View.VISIBLE) {
                    setControllerVisible(false);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // MPVLib.EventObserver
    @Override public void eventProperty(String property) {}
    @Override public void eventProperty(String property, long value) {}
    @Override public void eventProperty(String property, double value) {
        // Store the value from mpv's callback — this is more reliable than
        // polling getPropertyDouble() for streams.
        // This is how Nuvio/mpv-android do it.
        if ("time-pos".equals(property)) {
            mpvTimePos = value;
            main.post(() -> updateProgress());
        } else if ("duration".equals(property)) {
            mpvDuration = value;
            main.post(() -> updateProgress());
        }
    }
    @Override public void eventProperty(String property, boolean value) {
        if ("pause".equals(property)) {
            isPlaying = !value;
            main.post(() -> playPauseBtn.setText(value ? "▶ Play" : "⏸ Pause"));
        }
    }
    @Override public void eventProperty(String property, String value) {}
    @Override public void event(int eventId) {
        // MPV_EVENT_END_FILE = 7, MPV_EVENT_SHUTDOWN = ...
        if (eventId == 7) { // end of file
            main.post(this::finish);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            wasPlayingOnPause = !MPVLib.getPropertyBoolean("pause");
            MPVLib.setPropertyBoolean("pause", true);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Surface callbacks restore vo; resume playback only if it was playing.
        if (wasPlayingOnPause) {
            wasPlayingOnPause = false;
            try { MPVLib.setPropertyBoolean("pause", false); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        try {
            MPVLib.command(new String[]{"stop"});
            MPVLib.destroy();
        } catch (Exception ignored) {}
        TorrentStreamer.stopActive();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        s %= 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }
}
