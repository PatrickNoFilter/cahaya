package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.patrisius.cahaya.CahayaApp;
import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.addon.CatalogItem;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.1 bootstrap home screen: D-pad navigable rows built with framework
 * views (no Leanback dependency). Shows real catalog rows from the
 * installed Stremio-protocol addon. Card click -> details (v0.2).
 */
public class MainActivity extends Activity {

    private final ExecutorService imgIo = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private LruCache<String, Bitmap> memCache;
    private LinearLayout rowsBox;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#14161A"));

        int cacheBytes = (int) (Runtime.getRuntime().maxMemory() / 8);
        memCache = new LruCache<String, Bitmap>(cacheBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Cahaya");
        title.setTextSize(42);
        title.setTextColor(Color.parseColor("#4FC3F7"));
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("v0.1 bootstrap");
        subtitle.setTextColor(Color.GRAY);
        subtitle.setPadding(0, 0, 0, dp(8));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setText("Loading catalogs…");
        statusView.setTextColor(Color.GRAY);
        root.addView(statusView);

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(rowsBox);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        AddonManager mgr = ((CahayaApp) getApplication()).addons();
        mgr.ensureDefaults(new AddonManager.Callback<Boolean>() {
            @Override
            public void onResult(Boolean fresh) {
                loadRows(mgr);
            }

            @Override
            public void onError(Exception e) {
                statusView.setText("Offline — couldn't reach addon: " + e.getMessage());
            }
        });
    }

    private void loadRows(AddonManager mgr) {
        loadRow(mgr, "movie", "top", "Popular Movies");
        loadRow(mgr, "series", "top", "Popular Series");
    }

    private void loadRow(AddonManager mgr, String type, String id, String header) {
        mgr.catalog(AddonManager.DEFAULT_ADDON, type, id,
                new AddonManager.Callback<List<CatalogItem>>() {
                    @Override
                    public void onResult(List<CatalogItem> items) {
                        statusView.setVisibility(View.GONE);
                        rowsBox.addView(makeRow(header, items));
                    }

                    @Override
                    public void onError(Exception e) {
                        statusView.setText("Couldn't load " + header + ": " + e.getMessage());
                    }
                });
    }

    private View makeRow(String header, List<CatalogItem> items) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(14), 0, dp(14));

        TextView h = new TextView(this);
        h.setText(header);
        h.setTextSize(24);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(8));
        col.addView(h);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (CatalogItem it : items) {
            strip.addView(makeCard(it));
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(strip);
        col.addView(hsv);
        return col;
    }

    private View makeCard(final CatalogItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int w = dp(140);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));

        ImageView poster = new ImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(w - dp(8), dp(200)));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.parseColor("#23262B"));
        card.addView(poster);

        final TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextColor(Color.LTGRAY);
        name.setMaxLines(2);
        name.setPadding(0, dp(6), 0, 0);
        card.addView(name);

        card.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.08f : 1.0f);
            v.setScaleY(hasFocus ? 1.08f : 1.0f);
            v.setBackgroundColor(hasFocus ? Color.parseColor("#2A4A5A") : Color.TRANSPARENT);
            name.setTextColor(hasFocus ? Color.WHITE : Color.LTGRAY);
        });
        card.setOnClickListener(v -> Toast.makeText(MainActivity.this,
                item.name + " — details in v0.2", Toast.LENGTH_SHORT).show());

        loadPoster(item.poster, poster);
        return card;
    }

    private void loadPoster(String url, ImageView into) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Bitmap cached = memCache.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setTag(url);
        imgIo.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Cahaya/0.1.0 (Android TV)");
                InputStream in = c.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                if (bmp != null) {
                    memCache.put(url, bmp);
                }
                main.post(() -> {
                    if (url.equals(into.getTag())) {
                        into.setImageBitmap(bmp);
                    }
                });
            } catch (Exception ignored) {
                // leave placeholder
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        imgIo.shutdownNow();
        super.onDestroy();
    }
}
