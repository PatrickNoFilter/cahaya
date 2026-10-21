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

    private final Runnable hideController = () -> setControllerVisible(false);
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
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
                MPVLib.attachSurface(holder.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                MPVLib.detachSurface();
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

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        controller.addView(titleView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        playPauseBtn = new Button(this);
        playPauseBtn.setText("Pause");
        playPauseBtn.setOnClickListener(v -> togglePlay());
        row.addView(playPauseBtn);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(seekBar, sbp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) {
                    double dur = MPVLib.getPropertyDouble("duration");
                    if (dur > 0) MPVLib.command(new String[]{"seek", String.valueOf(dur * p / 1000), "absolute"});
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        timeView = new TextView(this);
        timeView.setTextColor(Color.WHITE);
        timeView.setText("0:00 / 0:00");
        row.addView(timeView);

        controller.addView(row);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = android.view.Gravity.BOTTOM;
        root.addView(controller, cp);

        setContentView(root);

        initPlayer();
    }

    private void initPlayer() {
        try {
            MPVLib.create(this);
            MPVLib.setEventObserver(this);

            // Hardware decoding, good defaults for TV
            MPVLib.setOptionString("hwdec", "mediacodec");
            MPVLib.setOptionString("vo", "gpu");
            MPVLib.setOptionString("ao", "audiotrack");
            MPVLib.setOptionString("cache", "yes");
            MPVLib.setOptionString("cache-secs", "10");

            MPVLib.init();

            // Load the stream
            MPVLib.command(new String[]{"loadfile", streamUrl});

            // Observe properties
            MPVLib.observeProperty("time-pos", 0);
            MPVLib.observeProperty("duration", 0);
            MPVLib.observeProperty("pause", 0);

            main.post(progressTick);
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't play: " + t.toString(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void togglePlay() {
        boolean paused = MPVLib.getPropertyBoolean("pause");
        MPVLib.setPropertyBoolean("pause", !paused);
        playPauseBtn.setText(paused ? "Pause" : "Play");
    }

    private void updateProgress() {
        try {
            double pos = MPVLib.getPropertyDouble("time-pos");
            double dur = MPVLib.getPropertyDouble("duration");
            if (dur > 0) {
                seekBar.setProgress((int) (pos * 1000 / dur));
                timeView.setText(fmt((long)(pos*1000)) + " / " + fmt((long)(dur*1000)));
            }
            // Hide loading once we have video
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
                setControllerVisible(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                MPVLib.command(new String[]{"seek", String.valueOf(SEEK_S)});
                setControllerVisible(true);
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
    @Override public void eventProperty(String property, double value) {}
    @Override public void eventProperty(String property, boolean value) {
        if ("pause".equals(property)) {
            main.post(() -> playPauseBtn.setText(value ? "Play" : "Pause"));
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
        try { MPVLib.setPropertyBoolean("pause", true); } catch (Exception ignored) {}
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
