package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
import com.patrisius.cahaya.addon.MetaDetails;
import com.patrisius.cahaya.addon.StreamItem;
import com.patrisius.cahaya.addon.SubtitleItem;
import com.patrisius.cahaya.jsrunner.JsScraperRunner;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;
import com.patrisius.cahaya.scraper.FibWatchScraper;
import com.patrisius.cahaya.scraper.Scraper;
import com.patrisius.cahaya.torrent.TorrentStreamer;
import com.patrisius.cahaya.ui.player.CueParser;
import com.patrisius.cahaya.ui.player.LangUtil;
import com.patrisius.cahaya.ui.player.MpvTrack;
import com.patrisius.cahaya.ui.player.PlayerButton;
import com.patrisius.cahaya.ui.player.PlayerTheme;
import com.patrisius.cahaya.ui.player.ScrubRates;
import com.patrisius.cahaya.ui.player.SeekTrack;
import com.patrisius.cahaya.ui.player.SidePanel;
import com.patrisius.cahaya.ui.player.SubtitleCue;
import com.patrisius.cahaya.ui.player.SubtitleLoader;
import com.patrisius.cahaya.ui.player.SubtitleOverlay;
import com.patrisius.cahaya.ui.player.SubtitlePrefs;
import com.patrisius.cahaya.ui.player.SubtitleRanker;
import com.patrisius.cahaya.ui.player.SubtitleSelection;
import com.patrisius.cahaya.ui.player.TimeFmt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    private PlayerButton backBtn, playBtn, nextBtn, rewBtn, fwdBtn, subsBtn, audioBtn, speedBtn, aspectBtn;
    private String poster;
    /** v1.1.0 live-save throttle: last wall time / position written to history. */
    private long lastLiveSaveMs = 0;
    private double lastLiveSavePos = -1;
    private SeekTrack seek;
    private TextView timeCur, timeDur, titleView;
    private SidePanel panel;
    private SubtitleOverlay subtitleOverlay;
    private View panelOpener;

    // ---- intent
    private String streamUrl, title, type, videoId;
    private String streamHeaders; // "Key: Value" lines, or null
    private double startPos;

    // ---- state
    private SubtitlePrefs prefs;
    private int panelKind = P_NONE;
    private boolean mpvReady, surfaceReady, fileStarted, fileLoaded, destroyed;
    // v1.5.3: Next-switch guard — loadfile (replace) makes mpv emit
    // END_FILE for the outgoing file, which must not finish the
    // activity while the new file is loading (see event() below).
    private boolean switchingStream = false;
    private int switchGeneration = 0;
    private boolean paused, buffering, wasPlayingOnPause;
    private double manualPos = 0;
    private volatile double mpvTimePos = -1, mpvDuration = -1;
    private boolean uiUpdateQueued;

    private final List<SubtitleItem> onlineRaw = new ArrayList<>();
    private List<SubtitleItem> onlineRanked = new ArrayList<>();
    /** Online subtitles currently downloading (guards double clicks). */
    private final Set<String> loadingUrls = new HashSet<>();
    /** Parsed cues per online subtitle URL, so switching back is instant. */
    private final Map<String, List<SubtitleCue>> cueCache = new HashMap<>();
    /** mpv track ids created by the sub-add fallback (unparseable online files); hidden from "In this video". */
    private final Set<Integer> fallbackTrackIds = new HashSet<>();
    /**
     * THE source of truth for the picker's tick: what the user (or auto-select) chose.
     * Never derived from mpv's sid / track ids, so the tick can't disagree with the UI.
     */
    private SubtitleSelection sel = SubtitleSelection.OFF;
    /** Language of an image-based (PGS) track the user picked while the online list was still loading. */
    private String wantLang;
    private volatile boolean loggedMpvText;
    private boolean subsLoading, onlineExpanded, autoSubDone;
    private int selectedAudioId = -2; // -2 = unknown (read from mpv), else track id
    private String previousAid = null; // aid playing before the current pick (restore target)
    private int audioVerifyPass = 0;
    private int subDelayMs, aspectMode, speedIdx = 2;
    private double videoAspect = 0; // from video-params; 0 = unknown

    private final Runnable hideController = this::hideControllerNow;
    private final Runnable hideHint = () -> hintView.setVisibility(View.GONE);
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!paused && mpvTimePos < 0) { manualPos += 1.0; subtitleOverlay.onTime(manualPos); }   // fallback if mpv never reports time-pos
            updateProgress();
            // v1.1.0 live history: save while watching (throttled to
            // 30s / 5s of movement), not only on exit — so a crash
            // keeps the minutes already watched.
            if (fileLoaded && !destroyed) {
                double pos = mpvTimePos > 0 ? mpvTimePos : manualPos;
                long now = System.currentTimeMillis();
                if (pos >= 30 && now - lastLiveSaveMs >= 30000
                        && Math.abs(pos - lastLiveSavePos) >= 5) {
                    saveCurrentProgress();
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    /** Write the current position to Playing History right now. */
    private void saveCurrentProgress() {
        try {
            double pos = mpvTimePos > 0 ? mpvTimePos : manualPos;
            double dur = mpvDuration > 0 ? mpvDuration : 0;
            if (videoId == null || pos < 30) return;
            ContinueWatching.save(this, videoId, title, type, poster, pos, dur);
            lastLiveSaveMs = System.currentTimeMillis();
            lastLiveSavePos = pos;
        } catch (Throwable ignored) {}
    }
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
        streamHeaders = getIntent().getStringExtra("headers");
        // mpv log file for diagnosis (pullable via root adb, unlike logcat)
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir != null) MPVLib.setLogFile(new java.io.File(dir, "mpv.log"));
        } catch (Exception ignored) { }
        title = getIntent().getStringExtra("title");
        if (title == null) title = "Video";
        type = getIntent().getStringExtra("type");
        videoId = getIntent().getStringExtra("video_id");
        poster = getIntent().getStringExtra("poster");
        startPos = getIntent().getDoubleExtra("start_pos", 0);
        prefs = new SubtitlePrefs(this);

        String[] subUrls = getIntent().getStringArrayExtra("sub_urls");
        String[] subLangs = getIntent().getStringArrayExtra("sub_langs");
        String[] subLabels = getIntent().getStringArrayExtra("sub_labels");
        if (subUrls != null) {
            for (int i = 0; i < subUrls.length; i++) {
                String l = subLangs != null && i < subLangs.length ? subLangs[i] : "?";
                String lab = subLabels != null && i < subLabels.length ? subLabels[i] : l;
                onlineRaw.add(new SubtitleItem(subUrls[i], l, lab));
            }
        } else {
            String legacy = getIntent().getStringExtra("sub_url");
            if (legacy != null && !legacy.isEmpty())
                onlineRaw.add(new SubtitleItem(legacy, prefs.preferredLang, prefs.preferredLang + " · provided"));
        }
        onlineRanked = SubtitleRanker.rank(onlineRaw, prefs.preferredLang);

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
        // Save resume position for Continue Watching
        try {
            double pos = mpvTimePos > 0 ? mpvTimePos : manualPos;
            double dur = mpvDuration > 0 ? mpvDuration : 0;
            if (videoId != null && pos > 0) {
                ContinueWatching.save(this, videoId, title, type, poster, pos, dur);
            }
        } catch (Throwable ignored) {}
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
                    MPVLib.setPropertyString("vo", "mediacodec_embed");
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
        nextBtn = new PlayerButton(this, 56, 32, R.drawable.ic_player_next, "Next episode");
        rewBtn = new PlayerButton(this, 56, 32, R.drawable.ic_player_replay10, "Back 10 seconds");
        fwdBtn = new PlayerButton(this, 56, 32, R.drawable.ic_player_forward10, "Forward 10 seconds");
        subsBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_subtitles, "Subtitles");
        audioBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_audio, "Audio");
        speedBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_speed, "Playback speed");
        aspectBtn = new PlayerButton(this, 56, 30, R.drawable.ic_player_aspect, "Aspect ratio");
        playBtn.setId(View.generateViewId());
        nextBtn.setId(View.generateViewId());
        addGap(row, playBtn, 0);
        addGap(row, nextBtn, 8);
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
        nextBtn.setOnClickListener(v -> { openNextEpisodePicker(); });
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
        for (View b : new View[]{playBtn, nextBtn, rewBtn, fwdBtn, subsBtn, audioBtn, speedBtn, aspectBtn})
            b.setNextFocusUpId(seek.getId());
        // v1.1.0: Next only makes sense for series — hide it for movies.
        if (!"series".equals(type)) nextBtn.setVisibility(View.GONE);

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

        // Subtitle overlay (drawn by us; mediacodec_embed cannot draw subtitles)
        subtitleOverlay = new SubtitleOverlay(this, root, prefs);

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
            // Direct MediaCodec -> Surface output. vo=gpu faults on the target Mali boxes.
            // mpv cannot draw subtitles on this path; SubtitleOverlay does it from "sub-text".
            MPVLib.setOptionString("hwdec", "mediacodec");
            MPVLib.setOptionString("ao", "audiotrack,opensles");
            MPVLib.setOptionString("audio-channels", "stereo");
            MPVLib.setOptionString("cache", "yes");
            MPVLib.setOptionString("cache-secs", "10");
            MPVLib.setOptionString("idle", "yes");
            // mpv's default UA gets bot-blocked by several file hosts
            // (Cloudflare 403 on R2 links); a browser UA is the
            // standard workaround and harms nothing for torrents.
            MPVLib.setOptionString("user-agent",
                    "Mozilla/5.0 (Linux; Android 11; B866F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            // mpv's bundled CA store fails TLS verification for some
            // file hosts (playimdb's host, 2026-10-24: "TLS certificate
            // verification failed" in mpv.log, while the same cert
            // verifies against a modern bundle). Playback streams carry
            // no sensitive data, so skip TLS verification for mpv
            // only; Java fetches (addons, scrapers) still verify.
            MPVLib.setOptionString("tls-verify", "no");
            // Provider-required headers at init time, not just before
            // loadfile: late option changes may be ignored by mpv.
            if (streamHeaders != null && !streamHeaders.isEmpty()) {
                MPVLib.setOptionString("http-header-fields",
                        streamHeaders.replace("\n", ","));
            }
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
            MPVLib.observeProperty("sub-text", 1);     // 1 = string; drives SubtitleOverlay

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
        switchingStream = false; // v1.5.3: the new file is in, end-file is real again
        prefs.applyToMpv();
        StringBuilder dump = new StringBuilder();
        for (MpvTrack t : MpvTrack.read("sub"))
            dump.append("\n  sub id=").append(t.id).append(" lang=").append(t.lang).append(" codec=").append(t.codec)
                    .append(t.isImage() ? " IMAGE" : " text").append(t.forced ? " forced" : "")
                    .append(t.external ? " external" : "").append(t.selected ? " <selected by mpv>" : "");
        diag("file loaded; preferred=" + prefs.preferredLang + dump);
        readVideoAspect();
        applyAspect();
        if (startPos > 5) seekAbs(startPos);
        if (subDelayMs != 0) try { MPVLib.setPropertyDouble("sub-delay", subDelayMs / 1000.0); } catch (Throwable ignored) {}
        showController();
        autoSelectSubtitle();
    }

    // =====================================================================
    // Transport
    // =====================================================================

    private void togglePlay() {
        // v1.0.2: atomic "cycle pause" instead of read-then-write.
        // The old read could go stale (or throw, silently swallowed)
        // and then no write ever happened — video kept playing.
        // "cycle pause" is mpv's own toggle: one call, no read needed.
        try {
            MPVLib.command(new String[]{"cycle", "pause"});
        } catch (Throwable t) {
            try {
                boolean p = MPVLib.getPropertyBoolean("pause");
                MPVLib.setPropertyBoolean("pause", !p);
            } catch (Throwable ignored) {}
        }
        showController();
    }

    /** True when v is inside parent (walking up the view tree). */
    private static boolean isIn(View v, ViewGroup parent) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p == parent) return true;
            p = p.getParent();
        }
        return false;
    }

    // =====================================================================
    // Next episode (v1.1.0)
    // =====================================================================

    /** Next button: find the episode after this one in Cinemeta's
     *  video list, gather its streams from every source (same set as
     *  Details), and let the user pick one — nothing auto-plays. */
    private void openNextEpisodePicker() {
        if (!"series".equals(type) || videoId == null) { hint("No next episode"); return; }
        // v1.5.0: parse from the right — a tmdb: id ("tmdb:293607:1:2")
        // has one more segment than a tt id ("tt123:1:2"), so the old
        // parts[0]/[1]/[2] split read the wrong season and series.
        String[] parts = videoId.split(":");
        if (parts.length < 3) { hint("No next episode for this title"); return; }
        final String seriesId;
        final int curSeason, curEpisode;
        try {
            curSeason = Integer.parseInt(parts[parts.length - 2]);
            curEpisode = Integer.parseInt(parts[parts.length - 1]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length - 2; i++) {
                if (i > 0) sb.append(":");
                sb.append(parts[i]);
            }
            seriesId = sb.toString();
        } catch (Exception e) { hint("No next episode for this title"); return; }

        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Finding next episode…")
                .setCancelable(false)
                .create();
        progress.show();

        final AddonManager mgr = ((CahayaApp) getApplication()).addons();
        // v1.5.0: TMDB-sourced series fetch meta from the TMDB addon.
        mgr.meta(seriesId.startsWith("tmdb:") ? AddonManager.TMDB_ADDON
                : AddonManager.DEFAULT_ADDON, "series", seriesId,
                new AddonManager.Callback<MetaDetails>() {
                    @Override public void onResult(MetaDetails meta) {
                        if (destroyed) return;
                        int idx = -1;
                        for (int i = 0; i < meta.videos.size(); i++) {
                            MetaDetails.VideoInfo vi = meta.videos.get(i);
                            if (vi.id.equals(videoId)
                                    || (vi.season == curSeason && vi.episode == curEpisode)) {
                                idx = i;
                                break;
                            }
                        }
                        if (idx < 0 || idx + 1 >= meta.videos.size()) {
                            main.post(() -> {
                                progress.dismiss();
                                hint("This is the last episode");
                            });
                            return;
                        }
                        collectNextStreams(meta, meta.videos.get(idx + 1), progress);
                    }

                    @Override public void onError(Exception e) {
                        main.post(() -> {
                            progress.dismiss();
                            Toast.makeText(PlayerActivity.this,
                                    "Couldn't load series info", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    /** Same stream sources as Details.playVideo, for the next episode. */
    private void collectNextStreams(final MetaDetails meta, final MetaDetails.VideoInfo nv,
                                    final AlertDialog progress) {
        final String nextTitle = meta.name + " — " + nv.title;
        final String nextVideoId = nv.id;
        final String nextPoster = nv.thumbnail != null && !nv.thumbnail.isEmpty()
                ? nv.thumbnail : poster;
        int year = 0;
        if (meta.releaseInfo != null) {
            java.util.regex.Matcher ym = java.util.regex.Pattern
                    .compile("\\b(19|20)\\d{2}\\b").matcher(meta.releaseInfo);
            if (ym.find()) {
                try { year = Integer.parseInt(ym.group()); } catch (Exception ignored) {}
            }
        }
        final int scraperYear = year;
        final AddonManager mgr = ((CahayaApp) getApplication()).addons();
        final PluginManager plugins = new PluginManager(this, mgr);
        final List<StreamItem> all = new ArrayList<>();
        final List<String> addonUrls = new ArrayList<>(mgr.addonUrls());
        final List<Plugin> enabled = new ArrayList<>();
        for (Plugin p : plugins.plugins()) if (p.enabled) enabled.add(p);
        final AtomicInteger pending =
                new AtomicInteger(addonUrls.size() + enabled.size() + 3);
        final Runnable done = () -> {
            if (pending.decrementAndGet() > 0) return;
            if (destroyed) return;
            progress.dismiss();
            if (all.isEmpty()) {
                Toast.makeText(this, "No streams found for next episode",
                        Toast.LENGTH_LONG).show();
                return;
            }
            // v1.5.1: StreamPicker sorts seeders-desc within each column.
            showNextPicker(nextTitle, nextVideoId, nextPoster, all);
        };
        AddonManager.Callback<List<StreamItem>> collect =
                new AddonManager.Callback<List<StreamItem>>() {
                    @Override public void onResult(List<StreamItem> items) {
                        synchronized (all) { all.addAll(items); }
                        main.post(done);
                    }

                    @Override public void onError(Exception e) { main.post(done); }
                };
        for (String base : addonUrls) {
            mgr.streams(base, "series", nextVideoId, collect);
        }
        for (Plugin p : enabled) {
            plugins.pluginStreams(p.baseUrl, "series", nextVideoId,
                    new PluginManager.Callback<List<StreamItem>>() {
                        @Override public void onResult(List<StreamItem> items) {
                            collect.onResult(items);
                        }

                        @Override public void onError(Exception e) {
                            collect.onError(e);
                        }
                    });
        }
        new FibWatchScraper().getStreams(meta.name, scraperYear, "series",
                nv.season, nv.episode,
                new Scraper.Callback() {
                    @Override public void onResult(List<StreamItem> items) {
                        collect.onResult(items);
                    }

                    @Override public void onError(Exception e) {
                        collect.onError(e);
                    }
                });
        // v1.5.0: TMDB addon meta carries no moviedbId — derive it
        // from the tmdb: id so the JS scrapers still run.
        int jsMovieDbId = meta.moviedbId;
        if (jsMovieDbId == 0 && meta.id != null
                && meta.id.startsWith("tmdb:")) {
            try {
                jsMovieDbId = Integer.parseInt(meta.id.substring(5));
            } catch (Exception ignored) {
            }
        }
        for (String pid : new String[]{JsScraperRunner.PROVIDER_4KHDHUB,
                JsScraperRunner.PROVIDER_PLAYIMDB}) {
            final String providerId = pid;
            new JsScraperRunner(this, providerId).run(jsMovieDbId, "series",
                    nv.season, nv.episode,
                    new JsScraperRunner.Callback() {
                        @Override public void onResult(List<StreamItem> items) {
                            collect.onResult(items);
                        }

                        @Override public void onError(String message) {
                            collect.onError(new Exception(message));
                        }
                    });
        }
    }

    private void showNextPicker(final String nextTitle, final String nextVideoId,
                                final String nextPoster, final List<StreamItem> streams) {
        // v1.5.1: same grouped column picker as Details Play.
        StreamPicker.show(this, nextTitle, streams, s -> {
            if (s.isTorrent()) {
                playNextTorrent(nextTitle, nextVideoId, nextPoster, s);
                return;
            }
            switchToStream(s.url, s.headers, nextTitle, nextVideoId, nextPoster);
        });
    }

    private void playNextTorrent(final String nextTitle, final String nextVideoId,
                                 final String nextPoster, final StreamItem s) {
        android.widget.TextView msg = new android.widget.TextView(this);
        msg.setPadding(48, 32, 48, 32);
        msg.setTextSize(18);
        msg.setText("Starting…");
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Torrent")
                .setView(msg)
                .setNegativeButton("Cancel", (d, w) -> TorrentStreamer.stopActive())
                .setCancelable(false)
                .create();
        dlg.show();
        new TorrentStreamer().start(this, s.url, s.fileIdx,
                new TorrentStreamer.Callback() {
                    @Override public void onProgress(String stage, int percent, String detail) {
                        main.post(() -> {
                            if (dlg.isShowing()) {
                                msg.setText(stage + "\n" + percent + "%"
                                        + (detail.isEmpty() ? "" : "\n" + detail));
                            }
                        });
                    }

                    @Override public void onReady(String httpUrl, String fileName) {
                        main.post(() -> {
                            if (dlg.isShowing()) dlg.dismiss();
                            switchToStream(httpUrl, null, nextTitle, nextVideoId, nextPoster);
                        });
                    }

                    @Override public void onError(String errMsg) {
                        main.post(() -> {
                            if (dlg.isShowing()) dlg.dismiss();
                            Toast.makeText(PlayerActivity.this, errMsg,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    /** Play the picked next-episode stream in THIS player (no new
     *  activity, so no torrent/activity-lifecycle race). The old
     *  episode's progress is saved first. */
    private void switchToStream(String url,
                                java.util.Map<String, String> headers,
                                String nTitle, String nVideoId, String nPoster) {
        saveCurrentProgress();
        title = nTitle;
        titleView.setText(nTitle);
        videoId = nVideoId;
        poster = nPoster;
        streamUrl = url;
        if (headers != null && !headers.isEmpty()) {
            StringBuilder hb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                if (hb.length() > 0) hb.append("\n");
                hb.append(e.getKey()).append(": ").append(e.getValue());
            }
            streamHeaders = hb.toString();
            try {
                MPVLib.setOptionString("http-header-fields",
                        streamHeaders.replace("\n", ","));
            } catch (Throwable ignored) {}
        } else {
            streamHeaders = null;
            try { MPVLib.setOptionString("http-header-fields", ""); } catch (Throwable ignored) {}
        }
        startPos = 0;
        fileLoaded = false;
        autoSubDone = false;
        sel = SubtitleSelection.OFF;
        wantLang = null;
        selectedAudioId = -2;
        try {
            subtitleOverlay.setCues(null);
            subtitleOverlay.setSubtitlesOn(false);
        } catch (Throwable ignored) {}
        onlineRaw.clear();
        onlineRanked.clear();
        subsLoading = false;
        mpvTimePos = -1;
        mpvDuration = -1;
        manualPos = 0;
        lastLiveSaveMs = 0;
        lastLiveSavePos = -1;
        // v1.5.3: arm the switch guard BEFORE loadfile — the outgoing
        // file's END_FILE arrives around the replacement.
        switchingStream = true;
        final int gen = ++switchGeneration;
        main.postDelayed(() -> {
            if (gen == switchGeneration) switchingStream = false;
        }, 10000);
        try {
            MPVLib.command(new String[]{"loadfile", url});
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't play next episode",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try { MPVLib.setPropertyBoolean("pause", false); } catch (Throwable ignored) {}
        fetchOnlineSubtitles();
        showController();
        hint("Next: " + nTitle);
        updateProgress();
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
            // v1.6.0: mpv props mirror the swapped layout modes
            // (ignored by mediacodec_embed, but kept consistent).
            MPVLib.setPropertyDouble("panscan", aspectMode == 1 ? 1.0 : 0.0);
            MPVLib.setPropertyBoolean("keepaspect", aspectMode != 0);
        } catch (Throwable ignored) {}
        applyAspect();
        hint("Aspect: " + ASPECT_NAMES[aspectMode]);
        scheduleHide();
    }

    /** Aspect modes that actually work with vo=mediacodec_embed.
     *  mpv accepts panscan/keepaspect (mpv.log shows "-> 1") but the
     *  MediaCodec path renders straight to the Surface, so the
     *  Android compositor scales each frame to the SurfaceView size
     *  and mpv's aspect properties have no visible effect. So the
     *  modes are implemented here, by resizing the SurfaceView:
     *  Fit = fill the screen, Zoom = fill the screen and crop
     *  the overflow, Stretch = whole video visible (v1.6.0: Fit
     *  and Stretch swapped per his correction). Resizing
     *  the view does not destroy the Surface (only surfaceChanged
     *  fires, which is a no-op), so playback continues untouched. */
    private void readVideoAspect() {
        // v1.6.0: the NUMERIC getters are unreliable for
        // video-params — the aspect double returned 1.95E-110
        // garbage, and the w/h ints gave 0.96 then 1.08 for a
        // 1920x1080 video (decoder-confirmed 1.78). The STRING
        // getter is the proven-reliable path (aid and the
        // track-list fields read correctly through it), so every
        // candidate is read as text and parsed, each validated
        // against the sane range before it is trusted.
        videoAspect = 0;
        String[] props = {"video-out-params/aspect", "video-params/aspect"};
        for (String p : props) {
            try {
                double v = Double.parseDouble(MPVLib.getPropertyString(p));
                if (v > 0.2 && v < 5.0) { videoAspect = v; return; }
            } catch (Throwable ignored) {}
        }
        String[][] wh = {{"video-out-params/w", "video-out-params/h"},
                         {"video-params/w", "video-params/h"}};
        for (String[] pair : wh) {
            try {
                int w = Integer.parseInt(MPVLib.getPropertyString(pair[0]));
                int h = Integer.parseInt(MPVLib.getPropertyString(pair[1]));
                if (w > 0 && h > 0) { videoAspect = (double) w / h; return; }
            } catch (Throwable ignored) {}
        }
    }

    private void applyAspect() {
        if (surfaceView == null) return;
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        // v1.6.0: Fit and Stretch swapped per his correction —
        // Fit now fills the screen (old Stretch), Stretch now
        // shows the whole video with bars (old Fit). Zoom
        // unchanged. A garbage/unknown aspect still falls back
        // to fill, so the surface can never again size to 0
        // wide (Fit went black) or MAXINT tall.
        if (aspectMode == 0 || videoAspect <= 0.2 || videoAspect >= 5.0) {
            // Fit, or unknown/invalid video size: fill the screen.
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            double screenAspect = (double) sw / sh;
            int w, h;
            if (aspectMode == 2) { // Stretch: whole video visible
                if (videoAspect > screenAspect) { w = sw; h = (int) (sw / videoAspect); }
                else { h = sh; w = (int) (sh * videoAspect); }
            } else { // Zoom: fill the screen, crop the overflow
                if (videoAspect > screenAspect) { h = sh; w = (int) (sh * videoAspect); }
                else { w = sw; h = (int) (sw / videoAspect); }
            }
            lp.width = w;
            lp.height = h;
        }
        lp.gravity = android.view.Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
        // v1.5.7 diagnostic: persists in mpv.log (logcat rotates).
        try { MPVLib.logMessage("CahayaAspect", 2, "mode=" + aspectMode + " videoAspect=" + videoAspect + " screen=" + sw + "x" + sh + " surface=" + lp.width + "x" + lp.height); } catch (Throwable ignored) {}
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
            // v1.0.2: focus play when focus is outside the controller
            // (e.g. cleared when it hid), so OK on a visible controller
            // always lands on pause — never on a dead-end view.
            View f = getCurrentFocus();
            if (f == null || !isIn(f, controller)) playBtn.requestFocus();
        }
        subtitleOverlay.setLifted(true);
        scheduleHide();
    }

    private void scheduleHide() {
        main.removeCallbacks(hideController);
        if (!paused && !panel.isOpen() && !seek.isScrubbing()) main.postDelayed(hideController, HIDE_MS);
    }

    private void hideControllerNow() {
        if (panel.isOpen() || controller.getVisibility() != View.VISIBLE) return;
        if (paused) return;                       // stay visible while paused
        subtitleOverlay.setLifted(false);
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

    /** v1.0.3: panel buttons (subtitles/audio/speed/…) used to need
     *  two presses to open: the OK press was routed through the
     *  button's key-up click, which could be swallowed when focus
     *  or the controller's hide animation shifted under it. Now the
     *  OK key-DOWN on a focused controller button acts immediately
     *  and consumes the event, so the later key-up click never
     *  double-fires. Touch clicks still go through the listeners.
     *  Panel rows are untouched: the panel is open only while the
     *  controller is hidden, so this branch never runs for them. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
                && controller != null && controller.getVisibility() == View.VISIBLE
                && panel != null && !panel.isOpen()) {
            View f = getCurrentFocus();
            if (f == playBtn) { togglePlay(); return true; }
            if (f == nextBtn) { openNextEpisodePicker(); return true; }
            if (f == rewBtn) { seekRel(-10); scheduleHide(); return true; }
            if (f == fwdBtn) { seekRel(10); scheduleHide(); return true; }
            if (f == subsBtn) { openPanel(P_SUBS, subsBtn); return true; }
            if (f == audioBtn) { openPanel(P_AUDIO, audioBtn); return true; }
            if (f == speedBtn) { openPanel(P_SPEED, speedBtn); return true; }
            if (f == aspectBtn) { cycleAspect(); return true; }
            if (f == backBtn) { finish(); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

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
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_P:
                // v1.0.2: remotes/keyboards that send Space/P for
                // play/pause now pause too — previously swallowed.
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
                // v1.0.2: visible controller, but focus on the seek
                // bar (or nowhere) — OK used to do nothing there,
                // because seek has no click action. Pause instead.
                // Focus on playBtn (or any other button) is left to
                // its own click, so there is no double toggle.
                View f = getCurrentFocus();
                if (f == null || f == seek) { togglePlay(); return true; }
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
        subtitleOverlay.setLifted(false);
        subtitleOverlay.setPreview(kind == P_STYLE);
        rebuildPanel();
    }

    private void closePanel() {
        main.removeCallbacks(refreshPanel);
        panel.hide();
        subtitleOverlay.setPreview(false);
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

    /** Goes to logcat under the "mpv" tag so it shows up in the same capture as mpv's own output. */
    private static void diag(String m) { android.util.Log.i("mpv", "CahayaSub: " + m); }
    private static void diagAudio(String m) { android.util.Log.i("mpv", "CahayaAudio: " + m); }
    private static String getAidSafe() {
        try { return MPVLib.getPropertyString("aid"); } catch (Throwable t) { return "?"; }
    }
    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void buildSubtitlePanel() {
        int restore = panel.isOpen() ? panel.focusedIndex() : -1;
        panel.begin("Subtitles");
        List<MpvTrack> tracks = MpvTrack.read("sub");

        panel.row("Off", null, sel.isOff(), () -> { disableSubtitles(); autoSubDone = true; rebuildPanel(); });

        List<MpvTrack> builtIn = new ArrayList<>();
        for (MpvTrack t : tracks) if (!fallbackTrackIds.contains(t.id)) builtIn.add(t);
        if (!builtIn.isEmpty()) {
            panel.section("In this video");
            int text = 0, image = 0;
            for (final MpvTrack t : builtIn) {
                if (t.isImage()) image++; else text++;
                panel.row(t.label(), t.detail(), sel.isEmbedded(t.id),
                        () -> { selectEmbedded(t); autoSubDone = true; rebuildPanel(); });
            }
            if (image > 0 && text == 0)
                panel.note("These are image-based (PGS / VobSub) and can't be drawn by this player. Choosing one loads the matching online subtitle instead.");
            else if (image > 0)
                panel.note("Tracks marked \"Image\" can't be drawn here; choosing one loads the matching online subtitle instead.");
        }

        panel.section("Online subtitles");
        if (subsLoading) panel.note("Searching your addons…");
        List<SubtitleItem> shown = new ArrayList<>(onlineRanked);
        if (shown.isEmpty() && !subsLoading) {
            panel.note(videoId == null || videoId.isEmpty()
                    ? "Online search isn't available for this stream."
                    : "None found. Install a subtitle addon from Cahaya's addon screen.");
        }
        int limit = onlineExpanded ? shown.size() : Math.min(ONLINE_LIMIT, shown.size());
        Map<String, Integer> perLang = new HashMap<>();
        for (int i = 0; i < shown.size(); i++) {
            final SubtitleItem s = shown.get(i);
            String lang = LangUtil.display(s.lang);
            int n = perLang.containsKey(lang) ? perLang.get(lang) + 1 : 1;
            perLang.put(lang, n);
            boolean active = sel.isOnline(s.url);
            if (i >= limit && !active) continue;          // the one in use stays visible when collapsed
            String detail = s.label == null ? "" : s.label;
            int cut = detail.indexOf("· ");
            if (cut >= 0) detail = detail.substring(cut + 2);
            if (loadingUrls.contains(s.url)) detail = "Loading…";
            panel.row(n > 1 ? lang + " " + n : lang, detail, active, () -> useOnlineSubtitle(s));
        }
        if (limit < shown.size()) {
            final int more = shown.size() - limit;
            panel.row("Show " + more + " more", null, false, () -> { onlineExpanded = true; rebuildPanel(); });
        }

        panel.section("Adjust");
        panel.stepper("Delay", () -> TimeFmt.delay(subDelayMs), dir -> changeDelay(dir * 100),
                () -> { subDelayMs = 0; applyDelay(); });
        panel.row("Appearance", "Size, colour, outline, background, height", false, () -> {
            panelKind = P_STYLE;
            subtitleOverlay.setPreview(true);
            rebuildPanel();
        });
        panel.end(restore);
    }

    /** Embedded track: mpv decodes it and the overlay draws mpv's "sub-text". Image tracks are redirected. */
    private void selectEmbedded(MpvTrack t) {
        diag("select embedded id=" + t.id + " lang=" + t.lang + " codec=" + t.codec + " image=" + t.isImage());
        if (t.isImage()) { useOnlineInsteadOfImage(t); return; }
        sel = SubtitleSelection.embedded(t.id);
        loggedMpvText = false;
        subtitleOverlay.setCues(null);                 // MPV mode
        subtitleOverlay.setSubtitlesOn(true);
        try {
            MPVLib.setPropertyBoolean("sub-visibility", true);
            MPVLib.setPropertyString("sid", String.valueOf(t.id));
        } catch (Throwable ignored) {}
        hint("Subtitles: " + t.label());
        updateSubsButton(true);
    }

    /**
     * PGS/VobSub can't be drawn without mpv's own video renderer. Don't tick something that can't
     * show: keep the current choice and switch to the best online subtitle in that track's language.
     */
    private void useOnlineInsteadOfImage(MpvTrack t) {
        String lang = (t.lang == null || t.lang.isEmpty()) ? prefs.preferredLang : t.lang;
        SubtitleItem best = SubtitleRanker.bestFor(onlineRanked, lang);
        if (best != null) {
            hint("Image subtitles can't be shown, using online " + LangUtil.display(lang));
            useOnlineSubtitle(best);
            autoSubDone = true;
        } else if (subsLoading) {
            wantLang = lang;
            hint("Image subtitles can't be shown. Searching online for " + LangUtil.display(lang) + "…");
        } else {
            hint("Image subtitles can't be shown, and no online " + LangUtil.display(lang) + " subtitle was found");
        }
    }

    private void disableSubtitles() {
        diag("subtitles off");
        sel = SubtitleSelection.OFF;
        wantLang = null;
        subtitleOverlay.setCues(null);
        subtitleOverlay.setSubtitlesOn(false);
        try {
            MPVLib.setPropertyString("sid", "no");
            MPVLib.setPropertyBoolean("sub-visibility", false);
        } catch (Throwable ignored) {}
        hint("Subtitles off");
        updateSubsButton(false);
    }

    private void updateSubsButton(boolean on) { subsBtn.setActive(on); }

    /**
     * Online subtitle: downloaded (charset-fixed), parsed into cues, and drawn by the overlay from the
     * playback time. mpv isn't involved, so it works on any renderer, right after seeks, and with
     * adjustable delay. The tick moves immediately; if the download fails it moves back.
     */
    private void useOnlineSubtitle(final SubtitleItem s) {
        List<SubtitleCue> cached = cueCache.get(s.url);
        if (cached != null) { applyOnline(s, cached, null); return; }
        if (!loadingUrls.add(s.url)) return;
        final SubtitleSelection prev = sel;
        sel = SubtitleSelection.online(s.url);
        wantLang = null;
        hint("Loading " + LangUtil.display(s.lang) + " subtitles…");
        rebuildPanel();
        new Thread(() -> {
            try {
                final File f = SubtitleLoader.fetch(getApplicationContext(), s.url, s.lang);
                final String text = readUtf8(f);
                final List<SubtitleCue> cues = CueParser.parse(text);
                main.post(() -> {
                    loadingUrls.remove(s.url);
                    if (destroyed) return;
                    if (!cues.isEmpty()) cueCache.put(s.url, cues);
                    if (sel.isOnline(s.url)) applyOnline(s, cues, f);   // user may have picked something else meanwhile
                    else rebuildPanel();
                });
            } catch (Exception e) {
                diag("online subtitle failed: " + e);
                main.post(() -> {
                    loadingUrls.remove(s.url);
                    if (sel.isOnline(s.url)) sel = prev;                  // un-tick: it never loaded
                    hint("Couldn't load that subtitle");
                    rebuildPanel();
                });
            }
        }, "sub-load").start();
    }

    private void applyOnline(SubtitleItem s, List<SubtitleCue> cues, File file) {
        sel = SubtitleSelection.online(s.url);
        autoSubDone = true;
        subtitleOverlay.setSubtitlesOn(true);
        if (!cues.isEmpty()) {
            diag("online subtitle " + LangUtil.display(s.lang) + ": " + cues.size() + " cues, first at "
                    + cues.get(0).startMs + " ms");
            subtitleOverlay.setCues(cues);
            subtitleOverlay.setDelayMs(subDelayMs);
            try { MPVLib.setPropertyString("sid", "no"); } catch (Throwable ignored) {}   // stop mpv decoding another track
        } else if (file != null) {
            // Our parser found nothing (unusual format): fall back to letting mpv decode the file.
            diag("online subtitle unparseable, falling back to mpv sub-add");
            subtitleOverlay.setCues(null);
            loggedMpvText = false;
            String l = LangUtil.canonical(s.lang);
            try {
                MPVLib.command(new String[]{"sub-add", file.getAbsolutePath(), "cached",
                        LangUtil.display(s.lang), l.isEmpty() ? "und" : l});
                MPVLib.setPropertyBoolean("sub-visibility", true);
                int id = Integer.parseInt(MPVLib.getPropertyString("sid"));
                if (id > 0) fallbackTrackIds.add(id);
            } catch (Throwable ignored) {}
        }
        hint("Subtitles: " + LangUtil.display(s.lang));
        updateSubsButton(true);
        schedulePanelRefresh();
    }

    private static String readUtf8(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void changeDelay(int deltaMs) {
        subDelayMs = Math.max(-180000, Math.min(180000, subDelayMs + deltaMs));
        applyDelay();
    }

    private void applyDelay() {
        subtitleOverlay.setDelayMs(subDelayMs);                       // online (cue) subtitles
        try { MPVLib.setPropertyDouble("sub-delay", subDelayMs / 1000.0); } catch (Throwable ignored) {}  // embedded
    }

    /** Make the tick reflect what mpv itself auto-selected (default / forced / slang pick). */
    private void syncSelectionFromMpv(List<MpvTrack> subs) {
        for (MpvTrack t : subs) {
            if (!t.selected) continue;
            if (t.isImage()) {                                        // can't be drawn: don't claim it
                try { MPVLib.setPropertyString("sid", "no"); } catch (Throwable ignored) {}
                break;
            }
            sel = SubtitleSelection.embedded(t.id);
            updateSubsButton(true);
            diag("mpv auto-selected embedded id=" + t.id + " lang=" + t.lang + " codec=" + t.codec);
            return;
        }
        sel = SubtitleSelection.OFF;
        updateSubsButton(false);
    }

    /** Pick a subtitle automatically: embedded text track in the preferred language, else best online match. */
    private void autoSelectSubtitle() {
        if (autoSubDone || !fileLoaded) return;
        String pl = prefs.preferredLang;
        List<MpvTrack> subs = MpvTrack.read("sub");
        if ("off".equals(pl)) {                                       // "Auto-select: Off" means exactly that
            autoSubDone = true;
            sel = SubtitleSelection.OFF;
            subtitleOverlay.setCues(null);
            subtitleOverlay.setSubtitlesOn(false);
            try { MPVLib.setPropertyString("sid", "no"); } catch (Throwable ignored) {}
            updateSubsButton(false);
            return;
        }
        MpvTrack pick = null;
        for (MpvTrack t : subs)
            if (LangUtil.matches(t.lang, pl) && !t.forced && !t.isImage()) { pick = t; break; }
        if (pick != null) { selectEmbedded(pick); autoSubDone = true; return; }
        SubtitleItem best = SubtitleRanker.bestFor(onlineRanked, pl);
        if (best != null) { useOnlineSubtitle(best); autoSubDone = true; return; }
        if (sel.isOff()) syncSelectionFromMpv(subs);                  // show truthfully what mpv picked meanwhile
        if (!subsLoading) autoSubDone = true;                         // nothing matched and nothing more is coming
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
            if (wantLang != null) {                                   // user picked an image track while we were searching
                SubtitleItem b = SubtitleRanker.bestFor(onlineRanked, wantLang);
                if (b != null) {
                    String l = wantLang; wantLang = null;
                    hint("Image subtitles can't be shown, using online " + LangUtil.display(l));
                    useOnlineSubtitle(b);
                    autoSubDone = true;
                } else if (pending[0] <= 0) {
                    hint("No online " + LangUtil.display(wantLang) + " subtitle found");
                    wantLang = null;
                }
            }
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
        panel.note("Changes show live in the sample text.");
        panel.stepper("Size", () -> prefs.size + "%", dir -> {
            prefs.size = Math.max(SubtitlePrefs.SIZE_MIN, Math.min(SubtitlePrefs.SIZE_MAX,
                    prefs.size + dir * SubtitlePrefs.SIZE_STEP));
            styleChanged();
        }, () -> { prefs.size = SubtitlePrefs.SIZE_DEFAULT; styleChanged(); });
        panel.stepper("Height", () -> prefs.lift == 0 ? "Bottom" : "+" + prefs.lift, dir -> {
            prefs.lift = Math.max(SubtitlePrefs.LIFT_MIN, Math.min(SubtitlePrefs.LIFT_MAX, prefs.lift + dir * 2));
            styleChanged();
        }, () -> { prefs.lift = SubtitlePrefs.LIFT_DEFAULT; styleChanged(); });
        panel.stepper("Colour", () -> {
            SpannableString sp = new SpannableString("\u25CF  " + SubtitlePrefs.COLOR_NAMES[prefs.colorIdx]);
            sp.setSpan(new ForegroundColorSpan(SubtitlePrefs.COLORS[prefs.colorIdx]), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sp;
        }, dir -> {
            int n = SubtitlePrefs.COLORS.length;
            prefs.colorIdx = (prefs.colorIdx + dir + n) % n;
            styleChanged();
        }, null);
        toggle("Bold", () -> prefs.bold, v -> prefs.bold = v);
        panel.stepper("Edge", () -> SubtitlePrefs.EDGE_NAMES[prefs.edge], dir -> {
            int n = SubtitlePrefs.EDGE_NAMES.length;
            prefs.edge = (prefs.edge + dir + n) % n;
            styleChanged();
        }, null);
        toggle("Background box", () -> prefs.background, v -> prefs.background = v);
        if (prefs.background) {
            panel.stepper("Background opacity", () -> prefs.bgOpacity + "%", dir -> {
                prefs.bgOpacity = Math.max(SubtitlePrefs.OPACITY_MIN, Math.min(SubtitlePrefs.OPACITY_MAX,
                        prefs.bgOpacity + dir * SubtitlePrefs.OPACITY_STEP));
                styleChanged();
            }, null);
        }
        toggle("Hide SDH text", () -> prefs.stripSdh, v -> prefs.stripSdh = v);

        panel.section("Behaviour");
        panel.stepper("Auto-select", () -> "off".equals(prefs.preferredLang) ? "Off" : LangUtil.display(prefs.preferredLang), dir -> {
            List<String> choices = new ArrayList<>(java.util.Arrays.asList(LANG_CHOICES));
            if (!choices.contains(prefs.preferredLang)) choices.add(1, prefs.preferredLang);
            int i = choices.indexOf(prefs.preferredLang);
            prefs.preferredLang = choices.get((i + dir + choices.size()) % choices.size());
            prefs.save();
        }, null);
        panel.row("Reset appearance", null, false, () -> { prefs.resetStyle(); styleChanged(); rebuildPanel(); });
        panel.end(restore);
    }

    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    /** On/Off row. A change that adds or removes other rows (background -> opacity) rebuilds the panel. */
    private void toggle(String label, final BoolGet get, final BoolSet set) {
        final Runnable flip = () -> {
            set.set(!get.get());
            styleChanged();
            if (panelKind == P_STYLE) schedulePanelRebuild();
        };
        panel.stepper(label, () -> get.get() ? "On" : "Off", dir -> flip.run(), flip);
    }

    private void schedulePanelRebuild() {
        main.removeCallbacks(refreshPanel);
        main.postDelayed(refreshPanel, 60);
    }

    /** Save, push the mpv-side bits, and redraw the on-screen subtitle with the new look. */
    private void styleChanged() {
        prefs.save();
        prefs.applyToMpv();
        subtitleOverlay.applyStyle();
    }

    // ---- audio / speed ---------------------------------------------------

    /** Delayed audio-switch verification (see the click handler).
     *  Runs 800ms after a pick: if mpv STILL reports no audio by
     *  then, the pick genuinely failed and auto is the rescue. A
     *  new file load resets selectedAudioId to -2, which skips
     *  this check so it can never clobber a fresh stream. */
    private final Runnable verifyAudio = () -> {
        // v1.6.0: verify, then RESTORE — never auto, never mute.
        // mpv.log 2026-10-27 proved the failure mode: the panel
        // offered id 113 while the file's only audio track is
        // aid=3, so setting 113 deselected the working track and
        // aid stayed "no" forever. Any pick that leaves aid at
        // "no" after 800ms gets one more check at 3.8s (slow
        // torrent switches need time); if it still fails, the
        // track playing before the click is restored, so a bad
        // option can never leave the user muted.
        if (selectedAudioId < 0) return;
        try {
            String a = MPVLib.getPropertyString("aid");
            diagAudio("verify: aid=" + a + " pass=" + audioVerifyPass);
            try { MPVLib.logMessage("CahayaAudioVerify", 2, "aid=" + a + " selected=" + selectedAudioId + " pass=" + audioVerifyPass); } catch (Throwable ignored) {}
            if (("no".equals(a) || "-1".equals(a)) && audioVerifyPass == 0) {
                audioVerifyPass = 1;
                scheduleVerifyAudio(3000);
            } else if ("no".equals(a) || "-1".equals(a)) {
                if (previousAid != null && !previousAid.isEmpty() && !"?".equals(previousAid) && !"no".equals(previousAid)) {
                    try {
                        MPVLib.setPropertyString("aid", previousAid);
                        String back = MPVLib.getPropertyString("aid");
                        try { selectedAudioId = Integer.parseInt(back); }
                        catch (Throwable ignored) { try { selectedAudioId = Integer.parseInt(previousAid); } catch (Throwable ignored2) {} }
                    } catch (Throwable ignored) {}
                    hint("Audio track unavailable, restored previous");
                } else {
                    hint("Audio track unavailable, pick another");
                }
                schedulePanelRefresh();
            }
        } catch (Throwable ignored) {}
    };

    /** Posts the audio verify runnable (a method, so the
     *  runnable's own initializer never self-references it —
     *  javac rejects that). */
    private void scheduleVerifyAudio(long delay) {
        main.postDelayed(verifyAudio, delay);
    }

    private void buildAudioPanel() {
        int restore = panel.isOpen() ? panel.focusedIndex() : -1;
        panel.begin("Audio");
        List<MpvTrack> tracks = MpvTrack.read("audio");
        diagAudio("panel open: " + tracks.size() + " tracks, current aid=" + getAidSafe());
        for (MpvTrack t : tracks) {
            diagAudio("  track id=" + t.id + " lang=" + t.lang + " codec=" + t.codec);
        }
        try {
            StringBuilder lb = new StringBuilder("panel open: " + tracks.size() + " tracks, current aid=" + getAidSafe());
            for (MpvTrack t : tracks) lb.append(" | id=").append(t.id).append(" lang=").append(t.lang).append(" codec=").append(t.codec);
            MPVLib.logMessage("CahayaAudioPanel", 2, lb.toString());
        } catch (Throwable ignored) {}
        if (tracks.isEmpty()) {
            panel.note("No audio tracks reported yet.");
        }
        // Use our tracked selection if set, else fall back to mpv's reported state
        if (selectedAudioId >= 0) {
            for (MpvTrack t : tracks) t.selected = (t.id == selectedAudioId);
        }
        for (int ai = 0; ai < tracks.size(); ai++) {
            final MpvTrack t = tracks.get(ai);
            // TV-friendly label: Language · Title (e.g. "English · GalaxyTV")
            // Note: channel count from demux-channel-count is unreliable (returns garbage like 97),
            // so we don't display it. Codec is shown.
            // A track with neither language nor title is "Audio N"
            // (position, not the mpv id) — never "Unknown".
            String label = t.label();
            if ((t.lang == null || LangUtil.canonical(t.lang).isEmpty())
                    && (t.title == null || t.title.isEmpty())) {
                label = "Audio " + (ai + 1);
            }
            String detail = (t.codec != null && !t.codec.isEmpty()) ? t.codec.toUpperCase() : "";
            if (t.isDefault) detail += (detail.isEmpty() ? "" : " · ") + "Default";
            final String fLabel = label;
            final String fLang = t.lang;
            final String fCodec = t.codec;
            final int fId = t.id;
            panel.row(label, detail, t.selected, () -> {
                // Re-read tracks to get fresh ID. Prefer the row's
                // own id when the fresh list still carries it (most
                // faithful to the option clicked, and immune to
                // duplicate lang+codec tracks); then lang+codec.
                List<MpvTrack> fresh = MpvTrack.read("audio");
                int useId = -1;
                for (MpvTrack ft : fresh) {
                    if (ft.id == fId) { useId = ft.id; break; }
                }
                if (useId < 0) {
                    for (MpvTrack ft : fresh) {
                        if (eq(ft.lang, fLang) && eq(ft.codec, fCodec)) { useId = ft.id; break; }
                    }
                }
                if (useId < 0 && !fresh.isEmpty()) useId = fresh.get(0).id; // fallback
                diagAudio("user select lang=" + fLang + " codec=" + fCodec + " -> fresh id=" + useId);
                if (useId < 0) {
                    // No audio track readable right now (track-list not
                    // populated yet): do NOT touch aid — setting
                    // aid=-1 disables audio, and the read-back is
                    // "-1" rather than "no", so the auto fallback
                    // below would never fire and the sound goes off
                    // and stays off.
                    diagAudio("no fresh id, leaving aid untouched");
                    hint("Audio tracks not ready yet");
                    return;
                }
                try {
                    // v1.5.7: do NOT read aid back immediately. mpv
                    // switches audio tracks asynchronously (AO re-init);
                    // the immediate read-back returns "no" while the
                    // switch is still in flight, and the old code then
                    // set aid=auto — clobbering the user's pick before
                    // it ever played (mpv.log 2026-10-27: every click
                    // shows aid=<id> followed at once by aid=auto).
                    // Trust the set (it logs "-> 1" on success) and
                    // verify only after the switch has had time to land.
                    previousAid = getAidSafe();
                    audioVerifyPass = 0;
                    MPVLib.setPropertyString("aid", String.valueOf(useId));
                    diagAudio("set aid=" + useId + " from=" + previousAid + " (verify 800ms/3.8s, restore on failure)");
                    try { MPVLib.logMessage("CahayaAudio", 2, "set aid=" + useId + " from=" + previousAid + " label=" + fLabel + " codec=" + fCodec); } catch (Throwable ignored) {}
                    selectedAudioId = useId;
                    main.removeCallbacks(verifyAudio);
                    main.postDelayed(verifyAudio, 800);
                } catch (Throwable e) {
                    diagAudio("select failed: " + e);
                    hint("Audio switch failed");
                    return;
                }
                hint("Audio: " + fLabel);
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
        else if ("sub-text".equals(property)) subtitleOverlay.onText("");   // unavailable = no subtitle now
    }
    @Override public void eventProperty(String property, long value) {}
    @Override public void eventProperty(String property, double value) {
        if ("time-pos".equals(property)) { mpvTimePos = value; subtitleOverlay.onTime(value); }
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
                if (value) { saveCurrentProgress(); if (!panel.isOpen()) showController(); } else scheduleHide();
            });
        } else if ("paused-for-cache".equals(property)) {
            main.post(() -> { buffering = value; updateProgress(); });
        }
    }
    @Override public void eventProperty(String property, String value) {
        if ("sub-text".equals(property)) {
            if (!loggedMpvText && value != null && !value.trim().isEmpty() && !subtitleOverlay.isCueMode()) {
                loggedMpvText = true;
                diag("mpv sub-text is delivering text (embedded path works): " + value.trim().replace('\n', ' '));
            }
            subtitleOverlay.onText(value);
        }
    }
    @Override public void event(int eventId) {
        if (eventId == 17) main.post(() -> { readVideoAspect(); applyAspect(); }); // VIDEO_RECONFIG
        else if (eventId == 8) main.post(this::onFileLoaded);          // MPV_EVENT_FILE_LOADED
        // MPV_EVENT_END_FILE — v1.5.3: ignored while a Next switch is
        // in flight, otherwise the outgoing file's end event finishes
        // the player right after the user picks the next stream.
        else if (eventId == 7) main.post(() -> {
            if (!switchingStream) finish();
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
