package com.patrisius.cahaya.ui.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * v1.17.0: pause screensaver. When playback stays paused, the player shows
 * this full-screen overlay with the movie/series info instead of a frozen
 * frame: poster, title, year, rating, overview and the current progress.
 * The player only carries title/type/poster, so year/rating/overview are
 * fetched from Cinemeta on first show (cached per video).
 *
 * A slow drift every 25 s protects OLED panels from burn-in. Dismissal is
 * owned by PlayerActivity (any key hides it; play keys also resume).
 */
public class PauseScreensaver extends FrameLayout {

    private static final long DRIFT_MS = 25000;
    private static final int DRIFT_DP = 18;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView posterView;
    private final TextView titleView, metaView, overviewView, progressLabel, pausedView, hintView;
    private final ProgressBar progressBar;
    private final LinearLayout content;

    private String shownFor;              // type:baseId currently displayed
    private boolean driftUp = true;

    private static final LruCache<String, Bitmap> posterCache = new LruCache<>(24);
    private static final Map<String, Meta> metaCache = new HashMap<>();

    private static final class Meta {
        String year, rating, overview, poster;
    }

    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (getVisibility() != VISIBLE) return;
            float d = dp(DRIFT_DP) * (driftUp ? 1 : -1);
            driftUp = !driftUp;
            content.animate().translationX(d * 0.6f).translationY(d).setDuration(4000).start();
            main.postDelayed(this, DRIFT_MS);
        }
    };

    public PauseScreensaver(Context ctx) {
        super(ctx);
        setBackgroundColor(0xF514161B);
        setVisibility(GONE);

        boolean narrow = ctx.getResources().getDisplayMetrics().widthPixels
                / ctx.getResources().getDisplayMetrics().density < 600;

        content = new LinearLayout(ctx);
        content.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(48);
        content.setPadding(pad, dp(32), pad, dp(32));
        addView(content, new LayoutParams(-2, -2, Gravity.CENTER));

        posterView = new ImageView(ctx);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterView.setBackgroundColor(0xFF23262B);
        int pw = dp(narrow ? 120 : 170), ph = dp(narrow ? 180 : 255);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(pw, ph);
        if (narrow) { pp.gravity = Gravity.CENTER_HORIZONTAL; pp.bottomMargin = dp(16); }
        else pp.rightMargin = dp(36);
        content.addView(posterView, pp);

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(narrow ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        content.addView(info, new LinearLayout.LayoutParams(
                narrow ? -2 : dp(520), -2));

        pausedView = label(ctx, "❚❚  PAUSED", 14, 0xFF9AA0A6);
        info.addView(pausedView);
        titleView = label(ctx, "", narrow ? 24 : 30, Color.WHITE);
        titleView.setPadding(0, dp(6), 0, 0);
        info.addView(titleView);
        metaView = label(ctx, "", 15, 0xFFB0B5BA);
        metaView.setPadding(0, dp(6), 0, 0);
        info.addView(metaView);
        overviewView = label(ctx, "", 15, 0xFFD9D9D9);
        overviewView.setMaxLines(6);
        overviewView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overviewView.setPadding(0, dp(10), 0, 0);
        if (narrow) overviewView.setGravity(Gravity.CENTER_HORIZONTAL);
        info.addView(overviewView);

        progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(6));
        blp.topMargin = dp(18);
        info.addView(progressBar, blp);
        progressLabel = label(ctx, "", 13, 0xFF9AA0A6);
        progressLabel.setPadding(0, dp(6), 0, 0);
        info.addView(progressLabel);

        hintView = label(ctx, "Press any key to dismiss", 13, 0xFF6B7280);
        hintView.setPadding(0, dp(18), 0, 0);
        info.addView(hintView);
    }

    private static TextView label(Context ctx, String t, int sp, int color) {
        TextView v = new TextView(ctx);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    public boolean isShowing() { return getVisibility() == VISIBLE; }

    /** Fill and show. Extra info (year/rating/overview) arrives async. */
    public void show(String title, String type, String videoId, String posterUrl,
                     double posSec, double durSec) {
        String baseId = videoId == null ? "" : videoId.split(":")[0];
        String key = (type == null ? "" : type) + ":" + baseId;
        boolean changed = !key.equals(shownFor);
        shownFor = key;

        titleView.setText(title == null || title.isEmpty() ? "Unknown title" : title);
        String kind = "series".equals(type) ? "Series" : "Movie";
        metaView.setText(kind);
        overviewView.setText("");
        if (durSec > 0) {
            progressBar.setProgress((int) (1000 * Math.max(0, Math.min(1, posSec / durSec))));
            progressLabel.setText(TimeFmt.clock(posSec) + " / " + TimeFmt.clock(durSec));
        } else {
            progressBar.setProgress(0);
            progressLabel.setText("");
        }
        loadPoster(posterUrl);

        Meta m = metaCache.get(key);
        if (m != null) applyMeta(m, kind);
        else if (changed && !baseId.isEmpty()) fetchMeta(key, type, baseId, kind);

        setVisibility(VISIBLE);
        bringToFront();
        main.removeCallbacks(drift);
        main.postDelayed(drift, DRIFT_MS);
    }

    public void hide() {
        main.removeCallbacks(drift);
        setVisibility(GONE);
    }

    private void applyMeta(Meta m, String kind) {
        StringBuilder sb = new StringBuilder(kind);
        if (m.year != null && !m.year.isEmpty()) sb.append("  •  ").append(m.year);
        if (m.rating != null && !m.rating.isEmpty() && !"0".equals(m.rating) && !"0.0".equals(m.rating))
            sb.append("  •  ★ ").append(m.rating);
        metaView.setText(sb.toString());
        if (m.overview != null && !m.overview.isEmpty()) overviewView.setText(m.overview);
        if (m.poster != null && !m.poster.isEmpty()) loadPoster(m.poster);
    }

    private void fetchMeta(final String key, final String type, final String baseId, final String kind) {
        new Thread(() -> {
            try {
                URL url = new URL("https://v3-cinemeta.strem.io/meta/" + type + "/" + baseId + ".json");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "Cahaya/1.17 (Android TV)");
                InputStream in = c.getInputStream();
                byte[] raw = readAll(in);
                in.close();
                c.disconnect();
                org.json.JSONObject meta = new org.json.JSONObject(new String(raw, "UTF-8"))
                        .optJSONObject("meta");
                if (meta == null) return;
                Meta m = new Meta();
                String rel = meta.optString("releaseInfo", meta.optString("year", ""));
                m.year = rel.length() >= 4 ? rel.substring(0, 4) : rel;
                m.rating = meta.optString("imdbRating", "");
                m.overview = meta.optString("description", "");
                m.poster = meta.optString("poster", "");
                metaCache.put(key, m);
                final String k = shownFor;
                main.post(() -> { if (key.equals(k) && isShowing()) applyMeta(m, kind); });
            } catch (Exception ignored) {}
        }, "screensaver-meta").start();
    }

    private void loadPoster(String url) {
        if (url == null || url.isEmpty()) { posterView.setImageBitmap(null); return; }
        Bitmap cached = posterCache.get(url);
        if (cached != null) { posterView.setImageBitmap(cached); return; }
        posterView.setTag(url);
        posterView.setImageBitmap(null);
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "Cahaya/1.17 (Android TV)");
                InputStream in = c.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                c.disconnect();
                if (bmp != null) posterCache.put(url, bmp);
                main.post(() -> { if (url.equals(posterView.getTag())) posterView.setImageBitmap(bmp); });
            } catch (Exception ignored) {}
        }, "screensaver-poster").start();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
