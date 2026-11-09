package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** v1.30.0: the Irama now-playing card on Home + the shared
 *  package lookup for the Music sidebar tile.
 *
 *  Irama (Lyra fork) plays YouTube Music through ExoPlayer in a
 *  foreground MusicService that extends MediaLibraryService, so its
 *  MediaSession is reachable from another app with the plain
 *  framework MediaBrowser — no new dependency, no code copied, no
 *  tokens shared. The card shows only while a session exists
 *  (i.e. Irama is running); it hides itself on disconnect or
 *  connection failure, so an Irama that was never opened costs
 *  nothing. Tap the card (or the Music tile) to open Irama; the
 *  buttons send plain transport commands (play/pause/next).
 *  The installed Irama on the box is the debug build
 *  (com.patrisius.irama.debug); the release id is tried first so a
 *  future release install wins. */
public class IramaNowPlaying {
    public static final String PKG_RELEASE = "com.patrisius.irama";
    public static final String PKG_DEBUG = "com.patrisius.irama.debug";
    private static final String SERVICE_CLASS = "com.shnwaz.lyramusic.playback.MusicService";
    private static final String TV_ACTIVITY = "com.shnwaz.lyramusic.ui.tv.TvMainActivity";

    /** Last known Irama playback state, from the Home card's
     *  controller; drives the openIrama() launch flag. */
    private static boolean lastKnownPlaying = false;
    /** True after Cahaya paused Irama for a video; the player
     *  resumes it on exit only when this is set. */
    private static boolean pausedForVideo = false;

    /** Which Irama package is installed, release preferred. */
    public static String iramaPackage(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm.getLaunchIntentForPackage(PKG_RELEASE) != null) return PKG_RELEASE;
        if (pm.getLaunchIntentForPackage(PKG_DEBUG) != null) return PKG_DEBUG;
        return null;
    }

    /** Open Irama's TV screen (Music tile / card tap). False when
     *  not installed.
     *  v1.30.2 (plan B): the old code used
     *  getLaunchIntentForPackage(), which prefers the MAIN+LAUNCHER
     *  activity — the phone MainActivity, whose in-app branding is
     *  still "Lyra Music" — over the leanback one. So the tile opened
     *  the phone UI on the TV. Launch TvMainActivity explicitly
     *  instead; no NEW_TASK flag, so Back returns to Cahaya. Falls
     *  back to the package launcher intent if the TV activity is
     *  ever missing (e.g. a non-TV build).
     *  v1.31.0: when music is playing, bring the existing
     *  TvMainActivity forward (REORDER_TO_FRONT) instead of
     *  stacking a fresh one — the live instance sits on the Player
     *  tab, where the song put it, so the user lands directly on
     *  the player. When nothing plays, a fresh instance opens on
     *  Home as before. */
    public static boolean openIrama(Activity act) {
        String pkg = iramaPackage(act);
        if (pkg == null) {
            Toast.makeText(act, "Irama is not installed", Toast.LENGTH_SHORT).show();
            return false;
        }
        Intent tv = new Intent(Intent.ACTION_MAIN);
        tv.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        tv.setClassName(pkg, TV_ACTIVITY);
        if (lastKnownPlaying) tv.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            act.startActivity(tv);
            return true;
        } catch (Exception ignored) {
            // Not the TV fork — fall back to the package launcher.
        }
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(act, "Irama is not installed", Toast.LENGTH_SHORT).show();
            return false;
        }
        act.startActivity(i);
        return true;
    }

    /** One-shot MediaBrowser: connect to Irama's session, run one
     *  transport command, disconnect. Used by the video player so
     *  it does not depend on the Home card's connection (which is
     *  torn down when the player opens). Fail-soft everywhere:
     *  Irama missing / service down / no session → nothing. */
    private interface OneShotAction { void run(MediaController controller); }

    private static void oneShot(Context ctx, OneShotAction action) {
        String pkg = iramaPackage(ctx);
        if (pkg == null) return;
        try {
            // Holder: the callback runs after construction, so a
            // plain local would read as "might not be initialized".
            final MediaBrowser[] holder = new MediaBrowser[1];
            holder[0] = new MediaBrowser(ctx,
                    new ComponentName(pkg, SERVICE_CLASS),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            try {
                                MediaController c = new MediaController(ctx, holder[0].getSessionToken());
                                action.run(c);
                            } catch (Throwable ignored) {
                            } finally {
                                try { holder[0].disconnect(); } catch (Throwable ignored) {}
                            }
                        }
                        @Override public void onConnectionFailed() {
                            try { holder[0].disconnect(); } catch (Throwable ignored) {}
                        }
                    }, null);
            holder[0].connect();
        } catch (Throwable ignored) {}
    }

    /** v1.31.0: pause Irama if it is playing. Called when a Cahaya
     *  video starts; the player calls it both when playback is
     *  kicked off and when the file loads (the second call is the
     *  safety net for the back-to-back-video race). Sets
     *  pausedForVideo only when it actually paused something, so
     *  a user-paused Irama is never auto-resumed. */
    public static void pauseForVideo(Context ctx) {
        oneShot(ctx, c -> {
            try {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    c.getTransportControls().pause();
                    pausedForVideo = true;
                }
            } catch (Throwable ignored) {}
        });
    }

    /** v1.31.0: resume Irama, but only if Cahaya paused it for a
     *  video. Called when the user exits the Cahaya player. */
    public static void resumeAfterVideo(Context ctx) {
        if (!pausedForVideo) return;
        oneShot(ctx, c -> {
            try {
                c.getTransportControls().play();
                pausedForVideo = false;
            } catch (Throwable ignored) {}
        });
    }

    private final Activity act;
    private MediaBrowser browser;
    private MediaController controller;
    private boolean wantConnection = false;
    private LinearLayout card;
    private ImageView artView;
    private TextView titleView;
    private TextView artistView;
    private TextView playBtn;
    private TextView nextBtn;
    private LinearLayout progressCol;
    private ProgressBar progressBar;
    private TextView timeView;

    /** Re-reads position once a second while playing, so the
     *  progress line and elapsed time keep moving. The controller
     *  only pushes state on play/pause/seek, so without this the
     *  line would freeze between events. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (controller == null || card == null || card.getVisibility() != View.VISIBLE) return;
            refresh(controller);
        }
    };

    public IramaNowPlaying(Activity act) {
        this.act = act;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    /** Build the card once; MainActivity overlays it on the hero. */
    public View getView() {
        if (card != null) return card;
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
        // v1.30.3: transparent — the card floats over the hero
        // backdrop. Text carries a shadow so it stays readable.
        c.setBackgroundColor(Color.TRANSPARENT);
        c.setFocusable(true);
        c.setClickable(true);
        c.setVisibility(View.GONE);
        c.setOnFocusChangeListener((v, hasFocus) ->
                v.setForeground(hasFocus ? new MainActivity.GoldBorderDrawable(dp(3), dp(8)) : null));
        c.setOnClickListener(v -> openIrama(act));

        // Single row: art + title/artist + progress + transport.
        // v1.30.4: the progress line + time moved here, between the
        // title and the play button — the old bottom row hung over
        // the hero title. Art shrunk to 32dp so it clears the
        // hero's ★ SERIES / ★ MOVIE badge.
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        artView = new ImageView(act);
        artView.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        top.addView(artView);

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(dp(12), 0, dp(12), 0);
        texts.setLayoutParams(tlp);
        titleView = new TextView(act);
        titleView.setText("♪ Irama");
        titleView.setTextSize(15);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(4, 0, 2, Color.BLACK);
        texts.addView(titleView);
        artistView = new TextView(act);
        artistView.setText("");
        artistView.setTextSize(13);
        artistView.setTextColor(Color.parseColor("#B0B5BA"));
        artistView.setSingleLine(true);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artistView.setShadowLayer(4, 0, 2, Color.BLACK);
        texts.addView(artistView);
        top.addView(texts);

        // Progress line + time, in the middle between the title and
        // the play button (v1.30.4 — was a bottom row that overlapped
        // the hero title).
        progressCol = new LinearLayout(act);
        progressCol.setOrientation(LinearLayout.VERTICAL);
        progressCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pclp.setMargins(dp(12), 0, dp(12), 0);
        progressCol.setLayoutParams(pclp);
        progressBar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#2196F3")));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#5A5E63")));
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        progressCol.addView(progressBar);
        timeView = new TextView(act);
        timeView.setText("");
        timeView.setTextSize(12);
        timeView.setTextColor(Color.parseColor("#B0B5BA"));
        timeView.setShadowLayer(4, 0, 2, Color.BLACK);
        timeView.setGravity(Gravity.END);
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tvlp.topMargin = dp(4);
        timeView.setLayoutParams(tvlp);
        progressCol.addView(timeView);
        top.addView(progressCol);

        playBtn = makeBtn("▶");
        playBtn.setOnClickListener(v -> togglePlay());
        top.addView(playBtn);
        nextBtn = makeBtn("»");
        nextBtn.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        top.addView(nextBtn);

        card = c;
        return card;
    }

    private TextView makeBtn(String glyph) {
        TextView b = new TextView(act);
        b.setText(glyph);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setShadowLayer(4, 0, 2, Color.BLACK);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, hasFocus) ->
                v.setForeground(hasFocus ? new MainActivity.GoldBorderDrawable(dp(3), dp(8)) : null));
        return b;
    }

    private void togglePlay() {
        if (controller == null) return;
        PlaybackState st = controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (playing) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    /** Connect to Irama's session. Safe to call repeatedly; no-ops
     *  when Irama is not installed or its service is not up. */
    public void connect() {
        if (browser != null || card == null) return;
        String pkg = iramaPackage(act);
        if (pkg == null) return;
        wantConnection = true;
        browser = new MediaBrowser(act,
                new ComponentName(pkg, SERVICE_CLASS),
                connCb, null);
        browser.connect();
    }

    public void disconnect() {
        wantConnection = false;
        lastKnownPlaying = false;
        if (controller != null) {
            try { controller.unregisterCallback(ctrlCb); } catch (Exception ignored) {}
            controller = null;
        }
        if (browser != null) {
            try { browser.disconnect(); } catch (Exception ignored) {}
            browser = null;
        }
        setVisible(false);
    }

    private void setVisible(boolean show) {
        if (card == null) return;
        if (!show) card.removeCallbacks(tick);
        card.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private final MediaBrowser.ConnectionCallback connCb = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            if (browser == null || !wantConnection) return;
            try {
                controller = new MediaController(act, browser.getSessionToken());
            } catch (Exception e) {
                controller = null;
                return;
            }
            controller.registerCallback(ctrlCb);
            refresh(controller);
        }

        @Override
        public void onConnectionSuspended() {
            if (controller != null) {
                try { controller.unregisterCallback(ctrlCb); } catch (Exception ignored) {}
                controller = null;
            }
            setVisible(false);
        }

        @Override
        public void onConnectionFailed() {
            controller = null;
            browser = null;
            setVisible(false);
        }
    };

    private final MediaController.Callback ctrlCb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refresh(controller);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refresh(controller);
        }

        @Override
        public void onSessionDestroyed() {
            controller = null;
            setVisible(false);
        }
    };

    private void refresh(MediaController ctrl) {
        if (ctrl == null || card == null) { setVisible(false); return; }
        MediaMetadata md = ctrl.getMetadata();
        PlaybackState st = ctrl.getPlaybackState();
        if (md == null && (st == null || st.getState() == PlaybackState.STATE_NONE)) {
            setVisible(false);
            return;
        }
        String title = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        String artist = md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        titleView.setText(title != null && !title.isEmpty() ? title : "♪ Irama");
        artistView.setText(artist != null ? artist : "");
        Bitmap art = md != null ? md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) : null;
        if (art == null && md != null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != null) {
            // v1.29.0 lesson: never draw an unbounded bitmap — a
            // 1200px YouTube Music cover is fine, but cap it anyway.
            if (art.getByteCount() > 4 * 1024 * 1024) {
                int mw = 400;
                int mh = Math.max(1, (int) (art.getHeight() * (mw / (double) art.getWidth())));
                art = Bitmap.createScaledBitmap(art, mw, mh, true);
            }
            artView.setImageBitmap(art);
        } else {
            artView.setImageBitmap(null);
        }
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        lastKnownPlaying = playing;
        playBtn.setText(playing ? "❚❚" : "▶");
        // v1.30.3: progress line + elapsed / total time. The state's
        // position is stamped at its last update, so add the time
        // elapsed since then while playing.
        long duration = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        long pos = 0;
        if (st != null) {
            pos = st.getPosition();
            if (playing) pos += android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
            if (pos < 0) pos = 0;
        }
        if (duration > 0) {
            if (pos > duration) pos = duration;
            progressBar.setMax((int) duration);
            progressBar.setProgress((int) pos);
            timeView.setText(fmtTime(pos) + " / " + fmtTime(duration));
            progressCol.setVisibility(View.VISIBLE);
        } else {
            progressCol.setVisibility(View.GONE);
        }
        setVisible(true);
        // Tick once a second while playing so the line moves.
        card.removeCallbacks(tick);
        if (playing && duration > 0) card.postDelayed(tick, 1000);
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        String mm = (m < 10 ? "0" : "") + m;
        String ss = (sec < 10 ? "0" : "") + sec;
        if (h > 0) return h + ":" + mm + ":" + ss;
        return m + ":" + ss;
    }
}
