package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import is.xyz.mpv.MPVLib;

import com.patrisius.cahaya.CahayaApp;
import com.patrisius.cahaya.R;
import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.addon.SubtitleItem;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;
import com.patrisius.cahaya.torrent.TorrentStreamer;
import com.patrisius.cahaya.ui.player.LangUtil;
import com.patrisius.cahaya.ui.player.MpvTrack;
import com.patrisius.cahaya.ui.player.PlayerButton;
import com.patrisius.cahaya.ui.player.PlayerTheme;
import com.patrisius.cahaya.ui.player.ScrubRates;
import com.patrisius.cahaya.ui.player.SeekTrack;
import com.patrisius.cahaya.ui.player.SidePanel;
import com.patrisius.cahaya.ui.player.SubtitleLoader;
import com.patrisius.cahaya.ui.player.SubtitleOverlay;
import com.patrisius.cahaya.ui.player.SubtitlePrefs;
import com.patrisius.cahaya.ui.player.SubtitleRanker;
import com.patrisius.cahaya.ui.player.TimeFmt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.9 player: libmpv on a SurfaceView with a modern TV control layer.
 *
 * Layout (all programmatic, no XML): top scrim with back + title, bottom scrim
 * with seek bar, times and an icon row (play/pause, -10s, +10s ... subtitles,
 * audio, speed, aspect). Pickers open in a right-hand side panel.
 *
 * Subtitles: embedded + external tracks from mpv's track-list, online results
 * from every installed addon/plugin (fetched in the background, so the player
 * opens immediately), preferred-language auto-select, charset-fixed local
 * download, delay, and a persisted appearance profile.
 *
 * Launched with extras: url (required), title, type, video_id, optional
 * sub_url (legacy single subtitle), start_pos (seconds).
 */
public class PlayerActivity extends Activity implements MPVLib.EventObserver {

    private static final int HIDE_MS = 4500;
    private static final int ONLINE_LIMIT = 30;
    private static final double[] SPEEDS = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0};
    private static final String[] ASPECT_NAMES = {"Fit", "Zoom", "Stretch"};
    private static final String[] LANG_CHOICES = {"off", "eng", "ind", "msa", "spa", "fra", "deu", "por",
            "ita", "nld", "rus", "ara", "tur", "hin", "tha", "vie", "jpn", "kor", "zho", "pol"};

    private static final int P_NONE = 0, P_SUBS = 1, P_AUDIO = 2, P_SPEED = 3, P_STYLE = 4;

    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- views
    private SurfaceView surfaceView;
    private ProgressBar spinner;
    private TextView hintView;
    private FrameLayout controller;
    private PlayerButton backBtn, playBtn, rewBtn, fwdBtn, subsBtn, audioBtn, speedBtn, aspectBtn;
    private SeekTrack seek;
    private TextView timeCur, timeDur, titleView;
    private SidePanel panel;
    private SubtitleOverlay subtitleOverlay;
    private View panelOpener;

    // ---- intent
    private String streamUrl, title, type, videoId;
    private double startPos;

    // ---- state
    private SubtitlePrefs prefs;
    private int panelKind = P_NONE;
    private boolean mpvReady, surfaceReady, fileStarted, fileLoaded, destroyed;
    private boolean paused, buffering, wasPlayingOnPause;
    private double manualPos = 0;
    private volatile double mpvTimePos = -1, mpvDuration = -1;
    private boolean uiUpdateQueued;

    private final List<SubtitleItem> onlineRaw = new ArrayList<>();
    private List<SubtitleItem> onlineRanked = new ArrayList<>();
    private final Set<String> addedUrls = new HashSet<>();
    private boolean subsLoading, onlineExpanded, autoSubDone;
    private int subDelayMs, aspectMode, speedIdx = 2;

    private final Runnable hideController = this::hideControllerNow;
    private final Runnable hideHint = () -> hintView.setVisibility(View.GONE);
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!paused && mpvTimePos < 0) manualPos += 1.0;   // fallback if mpv never reports time-pos
            updateProgress();
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable refreshPanel = this::rebuildPanel;

    // =====================================================================
    // Lifecycle
    // =====================================================================

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
        type = getIntent().getStringExtra("type");
        videoId = getIntent().getStringExtra("video_id");
        startPos = getIntent().getDoubleExtra("start_pos", 0);
        prefs = new SubtitlePrefs(this);

        String legacy = getIntent().getStringExtra("sub_url");
        if (legacy != null && !legacy.isEmpty()) {
            onlineRaw.add(new SubtitleItem(legacy, prefs.preferredLang, prefs.preferredLang + " · provided"));
            onlineRanked = SubtitleRanker.rank(onlineRaw, prefs.preferredLang);
        }

        buildUi();
        initPlayer();
        fetchOnlineSubtitles();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            wasPlayingOnPause = !MPVLib.getPropertyBoolean("pause");
            MPVLib.setPropertyBoolean("pause", true);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wasPlayingOnPause) {
            wasPlayingOnPause = false;
            try { MPVLib.setPropertyBoolean("pause", false); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (subtitleOverlay != null) subtitleOverlay.stop();
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        try {
            MPVLib.setEventObserver(null);
            MPVLib.command(new String[]{"stop"});
            MPVLib.destroy();
        } catch (Throwable ignored) {}
        TorrentStreamer.stopActive();
    }

    // =====================================================================
    // UI construction
    // =====================================================================

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                try {
                    MPVLib.attachSurface(holder.getSurface());
                    MPVLib.setOptionString("force-window", "yes");
                    MPVLib.setPropertyString("vo", prefs.gpuRenderer ? "gpu" : "mediacodec_embed");
                } catch (Throwable ignored) {}
                surfaceReady = true;
                maybeStartPlayback();
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
                if (!prefs.gpuRenderer) return;
                try { MPVLib.setPropertyString("android-surface-size", w + "x" + ht); } catch (Throwable ignored) {}
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                try {
                    MPVLib.setPropertyString("vo", "null");
                    MPVLib.setOptionString("force-window", "no");
                    MPVLib.detachSurface();
                } catch (Throwable ignored) {}
            }
        });
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(PlayerTheme.ACCENT));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        // ---- controller overlay
        controller = new FrameLayout(this);
        controller.setVisibility(View.GONE);

        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PlayerTheme.SCRIM, 0x00000000}));
        controller.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(150), Gravity.TOP));
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{PlayerTheme.SCRIM, 0x00000000}));
        controller.addView(bottomScrim, new FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM));

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(32), dp(24), dp(32), 0);
        backBtn = new PlayerButton(this, 52, 28, R.drawable.ic_player_back, "Back");
        backBtn.setId(View.generateViewId());
        backBtn.setOnClickListener(v -> finish());
        top.addView(backBtn);
        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(PlayerTheme.TEXT);
        titleView.setTextSize(22);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(6, 0, 2, 0x99000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(16);
        top.addView(titleView, tlp);
        controller.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom block
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(40), 0, dp(40), dp(28));

        seek = new SeekTrack(this);
        seek.setId(View.generateViewId());
        seek.setListener(new SeekTrack.Listener() {
            @Override public void onScrub(double s) {
                timeCur.setText(TimeFmt.clock(s));
                main.removeCallbacks(hideController);
            }
            @Override public void onCommit(double s) { seekAbs(s); scheduleHide(); }
        });
        bottom.addView(seek, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setPadding(dp(2), 0, dp(2), dp(6));
        timeCur = timeText("0:00");
        timeDur = timeText("--:--");
        times.addView(timeCur);
        times.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        times.addView(timeDur);
        bottom.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = new PlayerButton(this, 64, 38, R.drawable.ic_player_pause, "Play or pause");
        rewBtn = new PlayerButton(this, 56, 32, R.drawable.ic_player_replay10, "Back 10 seconds");
        fwdBtn = new PlayerButton(this, 56, 32, R.drawable.ic_player_forward10, "Forward 10 seconds");
        subsBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_subtitles, "Subtitles");
        audioBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_audio, "Audio");
        speedBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_speed, "Playback speed");
        aspectBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_aspect, "Aspect ratio");
        playBtn.setId(View.generateViewId());
        addGap(row, playBtn, 0);
        addGap(row, rewBtn, 8);
        addGap(row, fwdBtn, 8);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        addGap(row, subsBtn, 0);
        addGap(row, audioBtn, 8);
        addGap(row, speedBtn, 8);
        addGap(row, aspectBtn, 8);
        bottom.addView(row, new LinearLayout.LayoutParams(-1, -2));
        controller.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        playBtn.setOnClickListener(v -> { togglePlay(); });
        rewBtn.setOnClickListener(v -> { seekRel(-10); scheduleHide(); });
        fwdBtn.setOnClickListener(v -> { seekRel(10); scheduleHide(); });
        subsBtn.setOnClickListener(v -> openPanel(P_SUBS, v));
        audioBtn.setOnClickListener(v -> openPanel(P_AUDIO, v));
        speedBtn.setOnClickListener(v -> openPanel(P_SPEED, v));
        aspectBtn.setOnClickListener(v -> cycleAspect());

        // vertical focus chain: back <-> seek <-> buttons
        seek.setNextFocusUpId(backBtn.getId());
        seek.setNextFocusDownId(playBtn.getId());
        backBtn.setNextFocusDownId(seek.getId());
        for (View b : new View[]{playBtn, rewBtn, fwdBtn, subsBtn, audioBtn, speedBtn, aspectBtn})
            b.setNextFocusUpId(seek.getId());

        root.addView(controller, new FrameLayout.LayoutParams(-1, -1));

        // transient hint pill (aspect changed, subtitle selected...)
        hintView = new TextView(this);
        hintView.setTextColor(PlayerTheme.TEXT);
        hintView.setTextSize(16);
        hintView.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xE6121418);
        pill.setCornerRadius(dp(22));
        hintView.setBackground(pill);
        hintView.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(36);
        root.addView(hintView, hlp);

        panel = new SidePanel(this);
        root.addView(panel, new FrameLayout.LayoutParams(dp(420), -1, Gravity.END));

        // Subtitle overlay (Android TextView, works with mediacodec_embed)
        subtitleOverlay = new SubtitleOverlay(this, root);

        setContentView(root);
    }

    private TextView timeText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(PlayerTheme.TEXT);
        t.setTextSize(15);
        t.setShadowLayer(4, 0, 1, 0x99000000);
        return t;
    }

    private void addGap(LinearLayout row, View v, int leftDp) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.leftMargin = dp(leftDp);
        row.addView(v, lp);
    }

    // =====================================================================
    // mpv setup
    // =====================================================================

    private void initPlayer() {
        try {
            MPVLib.create(this);
            MPVLib.setEventObserver(this);

            // No VO yet: the surface arrives later via surfaceCreated().
            MPVLib.setOptionString("vo", "null");
            MPVLib.setOptionString("force-window", "no");
            MPVLib.setOptionString("gpu-context", "android");
            MPVLib.setOptionString("opengl-es", "yes");
            // mediacodec_embed (prefs.gpuRenderer == false) cannot draw subtitles; vo=gpu can.
            MPVLib.setOptionString("hwdec", prefs.gpuRenderer ? "mediacodec,mediacodec-copy" : "mediacodec");
            MPVLib.setOptionString("ao", "audiotrack,opensles");
            MPVLib.setOptionString("audio-channels", "stereo");
            MPVLib.setOptionString("cache", "yes");
            MPVLib.setOptionString("cache-secs", "10");
            MPVLib.setOptionString("idle", "yes");
            // Subtitle behaviour (same intent as NuvioTV's initOptions).
            MPVLib.setOptionString("sub-ass-override", "no");
            MPVLib.setOptionString("sub-codepage", "auto:utf-8");
            MPVLib.setOptionString("sub-font", "Roboto");
            MPVLib.setOptionString("sub-fonts-dir", "/system/fonts");
            MPVLib.setOptionString("sub-use-margins", "yes");
            MPVLib.setOptionString("sub-ass-force-margins", "yes");
            MPVLib.setOptionString("sid", "auto");
            if (!"off".equals(prefs.preferredLang)) MPVLib.setOptionString("slang", prefs.preferredLang);

            MPVLib.init();
            mpvReady = true;

            // formats: 0 = none (just "changed"), 3 = flag, 5 = double
            MPVLib.observeProperty("time-pos", 5);
            MPVLib.observeProperty("duration", 5);
            MPVLib.observeProperty("pause", 3);
            MPVLib.observeProperty("paused-for-cache", 3);
            MPVLib.observeProperty("track-list", 0);

            prefs.applyToMpv();
            main.post(progressTick);
            maybeStartPlayback();
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't play: " + t, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void maybeStartPlayback() {
        if (mpvReady && surfaceReady && !fileStarted) {
            fileStarted = true;
            manualPos = 0;
            mpvTimePos = -1;
            mpvDuration = -1;
            if (subtitleOverlay != null) subtitleOverlay.start();
            try {
                MPVLib.command(new String[]{"loadfile", streamUrl});
            } catch (Throwable t) {
                Toast.makeText(this, "Couldn't play: " + t, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /** mpv reports FILE_LOADED (event 8) once the demuxer is open and tracks are known. */
    private void onFileLoaded() {
        fileLoaded = true;
        prefs.applyToMpv();
        if (startPos > 5) seekAbs(startPos);
        if (subDelayMs != 0) try { MPVLib.setPropertyDouble("sub-delay", subDelayMs / 1000.0); } catch (Throwable ignored) {}
        showController();
        autoSelectSubtitle();
    }

    // =====================================================================
    // Transport
    // =====================================================================

    private void togglePlay() {
        try {
            boolean p = MPVLib.getPropertyBoolean("pause");
            MPVLib.setPropertyBoolean("pause", !p);
        } catch (Throwable ignored) {}
        showController();
    }

    private void seekRel(int seconds) {
        try { MPVLib.command(new String[]{"seek", String.valueOf(seconds)}); } catch (Throwable ignored) {}
        double base = mpvTimePos >= 0 ? mpvTimePos : manualPos;
        double np = Math.max(0, base + seconds);
        manualPos = np;
        if (mpvTimePos >= 0) mpvTimePos = np;     // optimistic; mpv corrects on its next report
        updateProgress();
    }

    private void seekAbs(double seconds) {
        try { MPVLib.command(new String[]{"seek", String.valueOf(seconds), "absolute"}); } catch (Throwable ignored) {}
        manualPos = seconds;
        if (mpvTimePos >= 0) mpvTimePos = seconds;
        updateProgress();
    }

    private void cycleAspect() {
        aspectMode = (aspectMode + 1) % ASPECT_NAMES.length;
        try {
            MPVLib.setPropertyDouble("panscan", aspectMode == 1 ? 1.0 : 0.0);
            MPVLib.setPropertyBoolean("keepaspect", aspectMode != 2);
        } catch (Throwable ignored) {}
        hint("Aspect: " + ASPECT_NAMES[aspectMode]);
        scheduleHide();
    }

    private void updateProgress() {
        double pos = mpvTimePos >= 0 ? mpvTimePos : manualPos;
        double dur = mpvDuration;
        if (!seek.isScrubbing()) timeCur.setText(TimeFmt.clock(pos));
        timeDur.setText(dur > 1 ? TimeFmt.clock(dur) : "--:--");
        seek.setProgress(pos, dur > 1 ? dur : 0);
        boolean showSpin = buffering || (pos <= 0 && !fileLoaded);
        spinner.setVisibility(showSpin ? View.VISIBLE : View.GONE);
    }

    // =====================================================================
    // Controller visibility
    // =====================================================================

    private void showController() {
        if (panel.isOpen()) return;
        main.removeCallbacks(hideController);
        if (controller.getVisibility() != View.VISIBLE) {
            controller.setAlpha(0f);
            controller.setVisibility(View.VISIBLE);
            controller.animate().alpha(1f).setDuration(160).start();
            playBtn.requestFocus();
        } else {
            controller.animate().cancel();
            controller.setAlpha(1f);
            if (getCurrentFocus() == null) playBtn.requestFocus();
        }
        scheduleHide();
    }

    private void scheduleHide() {
        main.removeCallbacks(hideController);
        if (!paused && !panel.isOpen() && !seek.isScrubbing()) main.postDelayed(hideController, HIDE_MS);
    }

    private void hideControllerNow() {
        if (panel.isOpen() || controller.getVisibility() != View.VISIBLE) return;
        if (paused) return;                       // stay visible while paused
        controller.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!paused) controller.setVisibility(View.GONE);
        }).start();
    }

    private void hint(String text) {
        hintView.setText(text);
        hintView.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHint);
        main.postDelayed(hideHint, 1800);
    }

    // =====================================================================
    // Keys
    // =====================================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_BACK:
                if (panel.isOpen()) { closePanel(); return true; }
                if (controller.getVisibility() == View.VISIBLE && !paused) {
                    main.removeCallbacks(hideController);
                    controller.setVisibility(View.GONE);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                try { MPVLib.setPropertyBoolean("pause", false); } catch (Throwable ignored) {}
                showController();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                try { MPVLib.setPropertyBoolean("pause", true); } catch (Throwable ignored) {}
                showController();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekRel((int) (ScrubRates.deltaMs(event.getRepeatCount(), true) / 1000));
                showController();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekRel((int) (ScrubRates.deltaMs(event.getRepeatCount(), false) / 1000));
                showController();
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_MENU:
                openPanel(P_SUBS, subsBtn);
                return true;
            default:
                break;
        }
        if (panel.isOpen()) return super.onKeyDown(keyCode, event);

        boolean controllerVisible = controller.getVisibility() == View.VISIBLE;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!controllerVisible) { togglePlay(); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!controllerVisible) {
                    // Controls hidden: arrows scrub directly, like a YouTube/Nuvio remote.
                    seekRel((int) (ScrubRates.deltaMs(event.getRepeatCount(),
                            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) / 1000));
                    showController();
                    seek.requestFocus();
                    return true;
                }
                break;      // visible: let the focus system move between buttons
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!controllerVisible) { showController(); return true; }
                break;
            default:
                break;
        }
        if (controllerVisible) scheduleHide();
        return super.onKeyDown(keyCode, event);
    }

    // =====================================================================
    // Side panels
    // =====================================================================

    private void openPanel(int kind, View opener) {
        panelKind = kind;
        panelOpener = opener;
        main.removeCallbacks(hideController);
        controller.setVisibility(View.GONE);
        rebuildPanel();
    }

    private void closePanel() {
        main.removeCallbacks(refreshPanel);
        panel.hide();
        panelKind = P_NONE;
        showController();
        if (panelOpener != null) panelOpener.requestFocus();
    }

    private void rebuildPanel() {
        if (panelKind == P_NONE || destroyed) return;
        switch (panelKind) {
            case P_SUBS: buildSubtitlePanel(); break;
            case P_AUDIO: buildAudioPanel(); break;
            case P_SPEED: buildSpeedPanel(); break;
            case P_STYLE: buildStylePanel(); break;
            default: return;
        }
        panel.show();
    }

    /** mpv's track-list can change several times in a burst (sub-add); coalesce rebuilds. */
    private void schedulePanelRefresh() {
        if (panelKind != P_SUBS && panelKind != P_AUDIO) return;
        main.removeCallbacks(refreshPanel);
        main.postDelayed(refreshPanel, 200);
    }

    // ---- subtitles ------------------------------------------------------

    private void buildSubtitlePanel() {
        int restore = panel.isOpen() ? panel.focusedIndex() : -1;
        panel.begin("Subtitles");
        List<MpvTrack> tracks = MpvTrack.read("sub");
        boolean anySelected = false;
        for (MpvTrack t : tracks) if (t.selected) anySelected = true;

        if (!prefs.gpuRenderer) {
            panel.note("The direct video renderer can't draw subtitles.");
            panel.row("Switch to GPU renderer", "Required for subtitles", false, () -> setRenderer(true));
        }
        final boolean off = !anySelected;
        panel.row("Off", null, off, () -> { disableSubtitles(); autoSubDone = true; rebuildPanel(); });

        if (!tracks.isEmpty()) {
            panel.section("In this video");
            for (final MpvTrack t : tracks)
                panel.row(t.label(), t.detail(), t.selected, () -> { selectSubtitle(t); autoSubDone = true; rebuildPanel(); });
        }

        panel.section("Online subtitles");
        if (subsLoading) panel.note("Searching your addons…");
        List<SubtitleItem> shown = new ArrayList<>();
        for (SubtitleItem s : onlineRanked) if (!addedUrls.contains(s.url)) shown.add(s);
        if (shown.isEmpty() && !subsLoading) {
            panel.note(videoId == null || videoId.isEmpty()
                    ? "Online search isn't available for this stream."
                    : "None found. Install a subtitle addon from Cahaya's addon screen.");
        }
        int limit = onlineExpanded ? shown.size() : Math.min(ONLINE_LIMIT, shown.size());
        Map<String, Integer> perLang = new HashMap<>();
        for (int i = 0; i < limit; i++) {
            final SubtitleItem s = shown.get(i);
            String lang = LangUtil.display(s.lang);
            int n = perLang.containsKey(lang) ? perLang.get(lang) + 1 : 1;
            perLang.put(lang, n);
            String detail = s.label == null ? "" : s.label;
            int cut = detail.indexOf("· ");
            if (cut >= 0) detail = detail.substring(cut + 2);
            panel.row(n > 1 ? lang + " " + n : lang, detail, false, () -> addOnlineSubtitle(s));
        }
        if (limit < shown.size()) {
            final int more = shown.size() - limit;
            panel.row("Show " + more + " more", null, false, () -> { onlineExpanded = true; rebuildPanel(); });
        }

        panel.section("Adjust");
        panel.stepper("Delay", () -> TimeFmt.delay(subDelayMs), dir -> changeDelay(dir * 100),
                () -> { subDelayMs = 0; applyDelay(); });
        panel.row("Appearance", "Size, colour, height, background", false, () -> { panelKind = P_STYLE; rebuildPanel(); });
        panel.end(restore);
    }

    private void selectSubtitle(MpvTrack t) {
        try {
            MPVLib.setPropertyBoolean("sub-visibility", true);
            MPVLib.setPropertyString("sid", String.valueOf(t.id));
        } catch (Throwable ignored) {}
        hint("Subtitles: " + t.label());
        updateSubsButton(true);
    }

    private void disableSubtitles() {
        try {
            MPVLib.setPropertyString("sid", "no");
            MPVLib.setPropertyBoolean("sub-visibility", false);
        } catch (Throwable ignored) {}
        hint("Subtitles off");
        updateSubsButton(false);
    }

    private void updateSubsButton(boolean on) { subsBtn.setActive(on); }

    /** Download (charset-fixed) on a worker thread, then hand the local file to mpv. */
    private void addOnlineSubtitle(final SubtitleItem s) {
        hint("Loading " + LangUtil.display(s.lang) + " subtitles…");
        addedUrls.add(s.url);
        new Thread(() -> {
            try {
                final File f = SubtitleLoader.fetch(getApplicationContext(), s.url, s.lang);
                main.post(() -> {
                    if (destroyed) return;
                    String l = LangUtil.canonical(s.lang);
                    try {
                        MPVLib.command(new String[]{"sub-add", f.getAbsolutePath(), "cached",
                                LangUtil.display(s.lang), l.isEmpty() ? "und" : l});
                        MPVLib.setPropertyBoolean("sub-visibility", true);
                    } catch (Throwable ignored) {}
                    autoSubDone = true;
                    hint("Subtitles: " + LangUtil.display(s.lang));
                    updateSubsButton(true);
                    schedulePanelRefresh();
                });
            } catch (Exception e) {
                main.post(() -> {
                    addedUrls.remove(s.url);
                    hint("Couldn't load that subtitle");
                });
            }
        }, "sub-load").start();
    }

    private void changeDelay(int deltaMs) {
        subDelayMs = Math.max(-180000, Math.min(180000, subDelayMs + deltaMs));
        applyDelay();
    }

    private void applyDelay() {
        try { MPVLib.setPropertyDouble("sub-delay", subDelayMs / 1000.0); } catch (Throwable ignored) {}
    }

    /** Pick a subtitle automatically: embedded in preferred language, else best online match. */
    private void autoSelectSubtitle() {
        if (autoSubDone || !fileLoaded) return;
        String pl = prefs.preferredLang;
        if ("off".equals(pl)) { autoSubDone = true; return; }
        List<MpvTrack> subs = MpvTrack.read("sub");
        for (MpvTrack t : subs) {
            if (t.selected) { autoSubDone = true; updateSubsButton(true); return; }   // mpv's own slang pick
        }
        MpvTrack pick = null;
        for (MpvTrack t : subs) if (LangUtil.matches(t.lang, pl) && !t.forced) { pick = t; break; }
        if (pick != null) { selectSubtitle(pick); autoSubDone = true; return; }
        SubtitleItem best = SubtitleRanker.bestFor(onlineRanked, pl);
        if (best != null && !addedUrls.contains(best.url)) { addOnlineSubtitle(best); autoSubDone = true; return; }
        if (!subsLoading) autoSubDone = true;     // nothing matched and nothing more is coming
    }

    private void fetchOnlineSubtitles() {
        if (type == null || videoId == null || videoId.isEmpty()) return;
        final AddonManager mgr = ((CahayaApp) getApplication()).addons();
        final PluginManager plugins = new PluginManager(this, mgr);
        final List<String> bases = new ArrayList<>(mgr.addonUrls());
        final List<Plugin> enabled = new ArrayList<>();
        for (Plugin p : plugins.plugins()) if (p.enabled) enabled.add(p);
        final int[] pending = {bases.size() + enabled.size()};
        if (pending[0] == 0) return;
        subsLoading = true;

        final Runnable oneDone = () -> {
            if (destroyed) return;
            if (--pending[0] <= 0) subsLoading = false;
            onlineRanked = SubtitleRanker.rank(onlineRaw, prefs.preferredLang);
            autoSelectSubtitle();
            if (panelKind == P_SUBS && (pending[0] <= 0 || onlineRanked.size() <= 3)) schedulePanelRefresh();
        };
        for (String base : bases) {
            mgr.subtitles(base, type, videoId, new AddonManager.Callback<List<SubtitleItem>>() {
                @Override public void onResult(List<SubtitleItem> items) { onlineRaw.addAll(items); oneDone.run(); }
                @Override public void onError(Exception e) { oneDone.run(); }
            });
        }
        for (Plugin p : enabled) {
            plugins.pluginSubtitles(p.baseUrl, type, videoId, new PluginManager.Callback<List<SubtitleItem>>() {
                @Override public void onResult(List<SubtitleItem> items) { onlineRaw.addAll(items); oneDone.run(); }
                @Override public void onError(Exception e) { oneDone.run(); }
            });
        }
    }

    // ---- appearance ------------------------------------------------------

    private void buildStylePanel() {
        int restore = panel.isOpen() ? panel.focusedIndex() : -1;
        panel.begin("Subtitle appearance");
        panel.stepper("Size", () -> prefs.size + "%", dir -> {
            prefs.size = Math.max(SubtitlePrefs.SIZE_MIN, Math.min(SubtitlePrefs.SIZE_MAX,
                    prefs.size + dir * SubtitlePrefs.SIZE_STEP));
            styleChanged();
        }, null);
        panel.stepper("Height", () -> prefs.lift == 0 ? "Bottom" : "+" + prefs.lift, dir -> {
            prefs.lift = Math.max(SubtitlePrefs.LIFT_MIN, Math.min(SubtitlePrefs.LIFT_MAX, prefs.lift + dir * 2));
            styleChanged();
        }, null);
        panel.stepper("Colour", () -> {
            SpannableString s = new SpannableString("●  " + SubtitlePrefs.COLOR_NAMES[prefs.colorIdx]);
            s.setSpan(new ForegroundColorSpan(SubtitlePrefs.COLORS[prefs.colorIdx]), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return s;
        }, dir -> {
            int n = SubtitlePrefs.COLORS.length;
            prefs.colorIdx = (prefs.colorIdx + dir + n) % n;
            styleChanged();
        }, null);
        toggle("Bold", () -> prefs.bold, v -> prefs.bold = v);
        toggle("Outline", () -> prefs.outline, v -> prefs.outline = v);
        toggle("Background box", () -> prefs.background, v -> prefs.background = v);
        toggle("Hide SDH text", () -> prefs.stripSdh, v -> prefs.stripSdh = v);

        panel.section("Behaviour");
        panel.stepper("Auto-select", () -> "off".equals(prefs.preferredLang) ? "Off" : LangUtil.display(prefs.preferredLang), dir -> {
            List<String> choices = new ArrayList<>(java.util.Arrays.asList(LANG_CHOICES));
            if (!choices.contains(prefs.preferredLang)) choices.add(1, prefs.preferredLang);
            int i = choices.indexOf(prefs.preferredLang);
            prefs.preferredLang = choices.get((i + dir + choices.size()) % choices.size());
            prefs.save();
        }, null);
        toggle("GPU renderer", () -> prefs.gpuRenderer, this::setRenderer);
        panel.row("Reset appearance", null, false, () -> { prefs.resetStyle(); styleChanged(); rebuildPanel(); });
        panel.end(restore);
    }

    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    private void toggle(String label, final BoolGet get, final BoolSet set) {
        final Runnable flip = () -> { set.set(!get.get()); styleChanged(); };
        panel.stepper(label, () -> get.get() ? "On" : "Off", dir -> flip.run(), flip);
    }

    private void styleChanged() {
        prefs.save();
        prefs.applyToMpv();
    }

    /** Switch between vo=gpu (draws subtitles) and mediacodec_embed (cannot), live. */
    private void setRenderer(boolean gpu) {
        if (prefs.gpuRenderer == gpu) return;
        prefs.gpuRenderer = gpu;
        prefs.save();
        try {
            MPVLib.setPropertyString("hwdec", gpu ? "mediacodec,mediacodec-copy" : "mediacodec");
            MPVLib.setPropertyString("vo", gpu ? "gpu" : "mediacodec_embed");
        } catch (Throwable ignored) {}
        hint(gpu ? "GPU renderer (subtitles on)" : "Direct renderer (no subtitles)");
        rebuildPanel();
    }

    // ---- audio / speed ---------------------------------------------------

    private void buildAudioPanel() {
        int restore = panel.isOpen() ? panel.focusedIndex() : -1;
        panel.begin("Audio");
        List<MpvTrack> tracks = MpvTrack.read("audio");
        if (tracks.isEmpty()) panel.note("No audio tracks reported yet.");
        for (final MpvTrack t : tracks) {
            panel.row(t.label(), t.detail(), t.selected, () -> {
                try { MPVLib.setPropertyString("aid", String.valueOf(t.id)); } catch (Throwable ignored) {}
                hint("Audio: " + t.label());
                schedulePanelRefresh();
            });
        }
        panel.end(restore);
    }

    private void buildSpeedPanel() {
        panel.begin("Playback speed");
        for (int i = 0; i < SPEEDS.length; i++) {
            final int idx = i;
            String label = SPEEDS[i] == 1.0 ? "Normal" : SPEEDS[i] + "×";
            panel.row(label, null, i == speedIdx, () -> {
                speedIdx = idx;
                try { MPVLib.setPropertyDouble("speed", SPEEDS[idx]); } catch (Throwable ignored) {}
                speedBtn.setActive(SPEEDS[idx] != 1.0);
                hint("Speed: " + (SPEEDS[idx] == 1.0 ? "Normal" : SPEEDS[idx] + "×"));
                rebuildPanel();
            });
        }
        panel.end();
    }

    // =====================================================================
    // MPVLib.EventObserver — called on mpv's thread, so always hop to main
    // =====================================================================

    @Override public void eventProperty(String property) {
        if ("track-list".equals(property)) main.post(this::schedulePanelRefresh);
    }
    @Override public void eventProperty(String property, long value) {}
    @Override public void eventProperty(String property, double value) {
        if ("time-pos".equals(property)) mpvTimePos = value;
        else if ("duration".equals(property)) mpvDuration = value;
        else return;
        if (!uiUpdateQueued) {              // throttle: mpv can report time-pos many times a second
            uiUpdateQueued = true;
            main.postDelayed(() -> { uiUpdateQueued = false; updateProgress(); }, 250);
        }
    }
    @Override public void eventProperty(String property, boolean value) {
        if ("pause".equals(property)) {
            main.post(() -> {
                paused = value;
                playBtn.setIcon(value ? R.drawable.ic_player_play : R.drawable.ic_player_pause);
                if (value) { if (!panel.isOpen()) showController(); } else scheduleHide();
            });
        } else if ("paused-for-cache".equals(property)) {
            main.post(() -> { buffering = value; updateProgress(); });
        }
    }
    @Override public void eventProperty(String property, String value) {}
    @Override public void event(int eventId) {
        if (eventId == 8) main.post(this::onFileLoaded);          // MPV_EVENT_FILE_LOADED
        else if (eventId == 7) main.post(this::finish);           // MPV_EVENT_END_FILE
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
