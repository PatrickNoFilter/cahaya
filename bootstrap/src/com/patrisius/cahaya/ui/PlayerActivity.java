package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
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

import com.patrisius.cahaya.CahayaApp;
import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.addon.SubtitleItem;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v0.3 native player: framework android.media.MediaPlayer on a SurfaceView,
 * D-pad controller overlay (play/pause, seek, subtitle menu), and timed-text
 * subtitle rendering. Launched with extras: url (required), title, type,
 * video_id (the latter two drive subtitle discovery).
 */
public class PlayerActivity extends Activity
        implements SurfaceHolder.Callback {

    private static final int SEEK_MS = 10_000;
    private static final int HIDE_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer mp;
    private SurfaceHolder holder;
    private String url;
    private String title;
    private String type;
    private String videoId;

    private TextView loadingView;
    private TextView subView;
    private LinearLayout controller;
    private Button playPauseBtn;
    private Button subBtn;
    private SeekBar seekBar;
    private TextView posView;
    private TextView durView;

    private final List<SubtitleItem> subtitles = new ArrayList<>();
    private int selectedSub = -1; // -1 = off
    private boolean prepared = false;
    private String testSubUrl; // automated-test hook: extras "sub_url" plays with this SRT

    private final Runnable hideController = () -> setControllerVisible(false);
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (mp != null && prepared) {
                try {
                    int pos = mp.getCurrentPosition();
                    int dur = mp.getDuration();
                    seekBar.setMax(dur);
                    seekBar.setProgress(pos);
                    posView.setText(fmt(pos));
                    durView.setText(fmt(dur));
                } catch (Exception ignored) {
                }
            }
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        url = getIntent().getStringExtra("url");
        title = getIntent().getStringExtra("title");
        type = getIntent().getStringExtra("type");
        videoId = getIntent().getStringExtra("video_id");
        testSubUrl = getIntent().getStringExtra("sub_url");
        if (url == null) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);

        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        loadingView = new TextView(this);
        loadingView.setText("Loading…");
        loadingView.setTextColor(Color.GRAY);
        loadingView.setTextSize(20);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.gravity = android.view.Gravity.CENTER;
        root.addView(loadingView, llp);

        subView = new TextView(this);
        subView.setTextColor(Color.WHITE);
        subView.setTextSize(22);
        subView.setGravity(android.view.Gravity.CENTER);
        subView.setShadowLayer(6, 0, 0, Color.BLACK);
        subView.setPadding(dp(48), 0, dp(48), dp(90));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        root.addView(subView, slp);

        controller = buildController();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.BOTTOM;
        root.addView(controller, clp);
        setControllerVisible(false);

        setContentView(root);
        fetchSubtitles();
    }

    private LinearLayout buildController() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#CC14161A"));
        bar.setPadding(dp(24), dp(12), dp(24), dp(16));

        TextView t = new TextView(this);
        t.setText(title != null ? title : "");
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setPadding(0, 0, 0, dp(8));
        bar.addView(t);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        playPauseBtn = new Button(this);
        playPauseBtn.setText("Pause");
        playPauseBtn.setFocusable(true);
        playPauseBtn.setOnClickListener(v -> togglePlay());
        row.addView(playPauseBtn);

        LinearLayout seekCol = new LinearLayout(this);
        seekCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scp.setMargins(dp(16), 0, dp(16), 0);
        row.addView(seekCol, scp);

        seekBar = new SeekBar(this);
        seekBar.setFocusable(false); // D-pad left/right always = ±10s seek
        seekCol.addView(seekBar);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        posView = new TextView(this);
        posView.setText("0:00");
        posView.setTextColor(Color.GRAY);
        times.addView(posView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        durView = new TextView(this);
        durView.setText("0:00");
        durView.setTextColor(Color.GRAY);
        times.addView(durView);
        seekCol.addView(times);

        subBtn = new Button(this);
        subBtn.setText("Subtitles");
        subBtn.setFocusable(true);
        subBtn.setOnClickListener(v -> showSubtitleMenu());
        row.addView(subBtn);

        bar.addView(row);
        return bar;
    }

    // ---------------- playback ----------------

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        startPlayback(testSubUrl, 0);
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
    }

    /** (Re)starts playback; subUrl null = no subtitles. resumeMs for re-prepare. */
    private void startPlayback(final String subUrl, final int resumeMs) {
        releasePlayer();
        prepared = false;
        loadingView.setVisibility(View.VISIBLE);
        try {
            mp = new MediaPlayer();
            mp.setDisplay(holder);
            mp.setDataSource(this, Uri.parse(url));
            if (subUrl != null) {
                mp.addTimedTextSource(this, Uri.parse(subUrl),
                        MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP);
            }
            mp.setOnPreparedListener(p -> {
                prepared = true;
                loadingView.setVisibility(View.GONE);
                if (subUrl != null) {
                    selectTimedTextTrack();
                }
                if (resumeMs > 0) {
                    p.seekTo(resumeMs);
                }
                p.start();
                playPauseBtn.setText("Pause");
                main.post(progressTick);
            });
            mp.setOnTimedTextListener((p, text) -> main.post(() -> {
                if (text != null && text.getText() != null) {
                    subView.setText(text.getText());
                } else {
                    subView.setText("");
                }
            }));
            mp.setOnCompletionListener(p -> finish());
            mp.setOnErrorListener((p, what, extra) -> {
                main.post(() -> {
                    Toast.makeText(PlayerActivity.this,
                            "Playback error (" + what + ")", Toast.LENGTH_LONG).show();
                    finish();
                });
                return true;
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    main.post(() -> loadingView.setVisibility(View.VISIBLE));
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    main.post(() -> loadingView.setVisibility(View.GONE));
                }
                return false;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't play: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void selectTimedTextTrack() {
        try {
            MediaPlayer.TrackInfo[] tracks = mp.getTrackInfo();
            for (int i = 0; i < tracks.length; i++) {
                int tt = tracks[i].getTrackType();
                if (tt == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                        || tt == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                    mp.selectTrack(i);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void togglePlay() {
        if (mp == null || !prepared) {
            return;
        }
        if (mp.isPlaying()) {
            mp.pause();
            playPauseBtn.setText("Play");
        } else {
            mp.start();
            playPauseBtn.setText("Pause");
        }
        pokeController();
    }

    private void seekBy(int delta) {
        if (mp == null || !prepared) {
            return;
        }
        try {
            int pos = Math.max(0, Math.min(mp.getDuration(),
                    mp.getCurrentPosition() + delta));
            mp.seekTo(pos);
            subView.setText("");
        } catch (Exception ignored) {
        }
        pokeController();
    }

    // ---------------- subtitles ----------------

    private void fetchSubtitles() {
        if (type == null || videoId == null) {
            return;
        }
        AddonManager mgr = ((CahayaApp) getApplication()).addons();
        PluginManager plugins = new PluginManager(this, mgr);
        List<String> bases = new ArrayList<>(mgr.addonUrls());
        List<Plugin> enabled = new ArrayList<>();
        for (Plugin p : plugins.plugins()) {
            if (p.enabled) {
                enabled.add(p);
            }
        }
        AtomicInteger pending = new AtomicInteger(bases.size() + enabled.size());
        if (pending.get() == 0) {
            return;
        }
        AddonManager.Callback<List<SubtitleItem>> collect =
                new AddonManager.Callback<List<SubtitleItem>>() {
                    @Override
                    public void onResult(List<SubtitleItem> items) {
                        synchronized (subtitles) {
                            subtitles.addAll(items);
                        }
                        pending.decrementAndGet();
                    }

                    @Override
                    public void onError(Exception e) {
                        pending.decrementAndGet();
                    }
                };
        for (String base : bases) {
            mgr.subtitles(base, type, videoId, collect);
        }
        for (Plugin p : enabled) {
            plugins.pluginSubtitles(p.baseUrl, type, videoId,
                    new PluginManager.Callback<List<SubtitleItem>>() {
                        @Override
                        public void onResult(List<SubtitleItem> items) {
                            collect.onResult(items);
                        }

                        @Override
                        public void onError(Exception e) {
                            collect.onError(e);
                        }
                    });
        }
    }

    private void showSubtitleMenu() {
        synchronized (subtitles) {
            if (subtitles.isEmpty()) {
                Toast.makeText(this, "No subtitles found for this title",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            String[] labels = new String[subtitles.size() + 1];
            labels[0] = (selectedSub == -1 ? "● " : "○ ") + "Off";
            for (int i = 0; i < subtitles.size(); i++) {
                labels[i + 1] = (selectedSub == i ? "● " : "○ ")
                        + subtitles.get(i).label;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Subtitles")
                    .setItems(labels, (d, which) -> {
                        if (which == 0) {
                            selectedSub = -1;
                            int pos = safePos();
                            startPlayback(null, pos);
                        } else {
                            selectedSub = which - 1;
                            SubtitleItem s = subtitles.get(selectedSub);
                            int pos = safePos();
                            try {
                                startPlayback(s.url, pos);
                            } catch (Exception e) {
                                Toast.makeText(PlayerActivity.this,
                                        "Subtitle failed to load", Toast.LENGTH_SHORT).show();
                                selectedSub = -1;
                            }
                        }
                        pokeController();
                    })
                    .show();
        }
    }

    private int safePos() {
        try {
            return (mp != null && prepared) ? mp.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- controller / keys ----------------

    private void setControllerVisible(boolean visible) {
        controller.setVisibility(visible ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hideController);
        if (visible) {
            main.postDelayed(hideController, HIDE_MS);
            playPauseBtn.requestFocus();
        }
    }

    private void pokeController() {
        setControllerVisible(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (controller.getVisibility() != View.VISIBLE) {
                    pokeController();
                } else {
                    togglePlay();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                pokeController();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (controller.getVisibility() == View.VISIBLE) {
                    setControllerVisible(false);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        s = s % 60;
        if (h > 0) {
            return h + ":" + String.format("%02d", m) + ":" + String.format("%02d", s);
        }
        return m + ":" + String.format("%02d", s);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void releasePlayer() {
        main.removeCallbacks(progressTick);
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mp != null && prepared && mp.isPlaying()) {
            mp.pause();
            playPauseBtn.setText("Play");
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }
}
