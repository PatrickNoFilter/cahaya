package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
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

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.patrisius.cahaya.CahayaApp;
import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.addon.SubtitleItem;
import com.patrisius.cahaya.torrent.TorrentStreamer;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v0.9 player: Media3 ExoPlayer on a SurfaceView, D-pad controller overlay
 * (play/pause, seek, subtitle menu). Launched with extras: url (required),
 * title, type, video_id (the latter two drive subtitle discovery).
 */
public class PlayerActivity extends Activity {

    private static final int SEEK_MS = 10_000;
    private static final int HIDE_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private SurfaceView surfaceView;
    private String url;
    private String title;
    private String type;
    private String videoId;

    private TextView loadingView;
    private LinearLayout controller;
    private Button playPauseBtn;
    private Button subBtn;
    private SeekBar seekBar;
    private TextView posView;
    private TextView durView;

    private final List<SubtitleItem> subtitles = new ArrayList<>();
    private int selectedSub = -1; // -1 = off
    private String testSubUrl;
    private long resumeMs = 0;

    private final Runnable hideController = () -> setControllerVisible(false);
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                try {
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    if (dur > 0) {
                        seekBar.setMax((int) dur);
                        seekBar.setProgress((int) pos);
                        posView.setText(fmt(pos));
                        durView.setText(fmt(dur));
                    }
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

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
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

        controller = buildController();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.BOTTOM;
        root.addView(controller, clp);
        setControllerVisible(false);

        setContentView(root);

        // Route volume keys to the music stream (what ExoPlayer uses).
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        // Build the player now (no need to wait for surface).
        initPlayer();
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
        seekBar.setFocusable(false);
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

    private void initPlayer() {
        try {
            // Custom renderers factory: force Stereo PCM with software downmix.
            // This fixes volume control (passthrough can't be volume-scaled)
            // and bad 5.1 downmixes on cheap boxes.
            androidx.media3.exoplayer.DefaultRenderersFactory rf =
                    new androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                @Override
                protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                        android.content.Context context,
                        boolean enableFloatOutput,
                        boolean enableAudioTrackPlaybackParams) {
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder b =
                            new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                    .setEnableFloatOutput(enableFloatOutput)
                                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams);
                    // PCM only, no passthrough: report default (stereo) capabilities
                    b.setAudioCapabilities(
                            androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES);
                    // Downmix multichannel to stereo in software
                    b.setAudioProcessorChain(
                            new androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                                    new androidx.media3.common.audio.AudioProcessor[]{
                                            createStereoDownmix()
                                    }));
                    return b.build();
                }
            };
            rf.setEnableDecoderFallback(true);

            player = new androidx.media3.exoplayer.ExoPlayer.Builder(this, rf).build();

            // Explicit audio attributes: media usage, movie content, handle focus
            androidx.media3.common.AudioAttributes aa =
                    new androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build();
            player.setAudioAttributes(aa, true);

            player.setVideoSurfaceView(surfaceView);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    main.post(() -> {
                        if (state == Player.STATE_READY) {
                            loadingView.setVisibility(View.GONE);
                            playPauseBtn.setText("Pause");
                            main.post(progressTick);
                        } else if (state == Player.STATE_BUFFERING) {
                            loadingView.setVisibility(View.VISIBLE);
                        } else if (state == Player.STATE_ENDED) {
                            finish();
                        }
                    });
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    main.post(() -> {
                        Toast.makeText(PlayerActivity.this,
                                "Playback error: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                        finish();
                    });
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    main.post(() -> playPauseBtn.setText(isPlaying ? "Pause" : "Play"));
                }
            });

            startPlayback(testSubUrl, 0);
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't play: " + t.toString(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startPlayback(String subUrl, long resume) {
        try {
            resumeMs = resume;
            MediaItem.Builder mib = new MediaItem.Builder()
                    .setUri(Uri.parse(url));
            if (subUrl != null) {
                MediaItem.SubtitleConfiguration sub =
                        new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                                .setMimeType("application/x-subrip")
                                .setLanguage("en")
                                .setSelectionFlags(0)
                                .build();
                mib.setSubtitleConfigurations(java.util.Collections.singletonList(sub));
            }
            player.setMediaItem(mib.build());
            player.prepare();
            if (resumeMs > 0) {
                player.seekTo(resumeMs);
            }
            player.play();
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't play: " + t.toString(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
        resetHideTimer();
    }

    private void seekBy(int deltaMs) {
        if (player == null) return;
        long pos = player.getCurrentPosition() + deltaMs;
        if (pos < 0) pos = 0;
        player.seekTo(pos);
        resetHideTimer();
    }

    // ---------------- subtitles ----------------

    private void fetchSubtitles() {
        if (testSubUrl != null) {
            subtitles.add(new SubtitleItem(testSubUrl, "en", "Test"));
            return;
        }
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
        if (subtitles.isEmpty()) {
            Toast.makeText(this, "No subtitles", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[subtitles.size() + 1];
        names[0] = "Off";
        for (int i = 0; i < subtitles.size(); i++) {
            names[i + 1] = subtitles.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle("Subtitles")
                .setSingleChoiceItems(names, selectedSub + 1, (d, which) -> {
                    d.dismiss();
                    selectedSub = which - 1;
                    long pos = player != null ? player.getCurrentPosition() : 0;
                    String subUrl = selectedSub >= 0 ? subtitles.get(selectedSub).url : null;
                    // Rebuild with new subtitle selection.
                    player.stop();
                    player.clearMediaItems();
                    startPlayback(subUrl, pos);
                })
                .show();
        resetHideTimer();
    }

    // ---------------- controller visibility ----------------

    private void setControllerVisible(boolean visible) {
        controller.setVisibility(visible ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hideController);
        if (visible) {
            main.postDelayed(hideController, HIDE_MS);
        }
    }

    private void resetHideTimer() {
        main.removeCallbacks(hideController);
        main.postDelayed(hideController, HIDE_MS);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (player == null) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // Let the system handle volume.
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (controller.getVisibility() == View.VISIBLE) {
                    // Let the focused button handle it.
                    return super.onKeyDown(keyCode, event);
                }
                setControllerVisible(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_MS);
                setControllerVisible(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_MS);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
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

    /**
     * Downmixes 5.1 audio to stereo in software using Media3's built-in
     * ITU-style mixing matrix. For non-5.1 input the processor passes
     * audio through untouched.
     */
    private static androidx.media3.common.audio.AudioProcessor createStereoDownmix() {
        androidx.media3.common.audio.ChannelMixingAudioProcessor p =
                new androidx.media3.common.audio.ChannelMixingAudioProcessor();
        p.putChannelMixingMatrix(
                androidx.media3.common.audio.ChannelMixingMatrix.create(6, 2));
        return p;
    }
}
