package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.patrisius.cahaya.CahayaApp;
import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.addon.MetaDetails;
import com.patrisius.cahaya.addon.StreamItem;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v0.3 details screen: poster + metadata, Play button, episode list for
 * series. Play aggregates /stream results from every installed addon and
 * every enabled plugin, then offers a stream picker.
 */
public class DetailsActivity extends Activity {

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private LruCache<String, Bitmap> memCache;

    private AddonManager mgr;
    private PluginManager plugins;
    private String itemId;
    private String itemType;
    private MetaDetails details;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#14161A"));

        itemId = getIntent().getStringExtra("id");
        itemType = getIntent().getStringExtra("type");
        if (itemId == null) {
            finish();
            return;
        }

        int cacheBytes = (int) (Runtime.getRuntime().maxMemory() / 8);
        memCache = new LruCache<String, Bitmap>(cacheBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        mgr = ((CahayaApp) getApplication()).addons();
        plugins = new PluginManager(this, mgr);

        TextView loading = new TextView(this);
        loading.setText("Loading details…");
        loading.setTextColor(Color.GRAY);
        loading.setPadding(dp(28), dp(28), 0, 0);
        setContentView(loading);

        mgr.meta(AddonManager.DEFAULT_ADDON, itemType, itemId,
                new AddonManager.Callback<MetaDetails>() {
                    @Override
                    public void onResult(MetaDetails d) {
                        details = d;
                        buildUi(d);
                    }

                    @Override
                    public void onError(Exception e) {
                        loading.setText("Couldn't load details: " + e.getMessage());
                    }
                });
    }

    private void buildUi(MetaDetails d) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));

        ImageView poster = new ImageView(this);
        int pw = dp(220);
        poster.setLayoutParams(new LinearLayout.LayoutParams(pw, dp(320)));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.parseColor("#23262B"));
        root.addView(poster);
        loadPoster(d.poster, poster);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.setMargins(dp(28), 0, 0, 0);
        root.addView(info, ilp);

        TextView title = new TextView(this);
        title.setText(d.name);
        title.setTextSize(32);
        title.setTextColor(Color.WHITE);
        info.addView(title);

        TextView meta = new TextView(this);
        meta.setText((d.releaseInfo != null ? d.releaseInfo : "") + "  ·  " + d.type);
        meta.setTextColor(Color.GRAY);
        meta.setPadding(0, dp(4), 0, dp(12));
        info.addView(meta);

        TextView desc = new TextView(this);
        desc.setText(d.description != null ? d.description : "");
        desc.setTextColor(Color.parseColor("#B0B5BA"));
        desc.setPadding(0, 0, 0, dp(16));
        info.addView(desc);

        Button play = new Button(this);
        play.setText(d.isSeries() ? "Play S1 E1" : "Play");
        play.setFocusable(true);
        info.addView(play, new LinearLayout.LayoutParams(dp(220),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        play.requestFocus();
        play.setOnClickListener(v -> {
            if (d.isSeries()) {
                playVideo(d.videos.get(0));
            } else {
                playVideo(null);
            }
        });

        if (d.isSeries()) {
            TextView eh = new TextView(this);
            eh.setText("Episodes (" + d.videos.size() + ")");
            eh.setTextSize(20);
            eh.setTextColor(Color.WHITE);
            eh.setPadding(0, dp(20), 0, dp(8));
            info.addView(eh);
            for (MetaDetails.VideoInfo vi : d.videos) {
                info.addView(makeEpisodeRow(vi));
            }
        }

        scroll.addView(root);
        setContentView(scroll);
    }

    private View makeEpisodeRow(final MetaDetails.VideoInfo vi) {
        TextView row = new TextView(this);
        String label = (vi.season > 0 ? "S" + vi.season + " " : "")
                + (vi.episode > 0 ? "E" + vi.episode + " — " : "") + vi.title;
        row.setText(label);
        row.setTextSize(16);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setTextColor(Color.parseColor("#B0B5BA"));
        row.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackgroundColor(hasFocus ? Color.parseColor("#2A4A5A") : Color.TRANSPARENT);
            row.setTextColor(hasFocus ? Color.WHITE : Color.parseColor("#B0B5BA"));
        });
        row.setOnClickListener(v -> playVideo(vi));
        return row;
    }

    private void playVideo(MetaDetails.VideoInfo vi) {
        final String videoId = vi != null ? vi.id : details.id;
        final String title = vi != null
                ? details.name + " — " + vi.title
                : details.name;

        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Finding streams…")
                .setCancelable(false)
                .create();
        progress.show();

        final List<StreamItem> all = new ArrayList<>();
        final List<String> addonUrls = new ArrayList<>(mgr.addonUrls());
        final List<Plugin> enabledPlugins = new ArrayList<>();
        for (Plugin p : plugins.plugins()) {
            if (p.enabled) {
                enabledPlugins.add(p);
            }
        }
        final AtomicInteger pending =
                new AtomicInteger(addonUrls.size() + enabledPlugins.size());
        if (pending.get() == 0) {
            progress.dismiss();
            Toast.makeText(this, "No addons or plugins installed", Toast.LENGTH_SHORT).show();
            return;
        }

        final Runnable done = () -> {
            if (pending.decrementAndGet() > 0) {
                return;
            }
            progress.dismiss();
            if (all.isEmpty()) {
                Toast.makeText(DetailsActivity.this,
                        "No streams found — install a streams addon or plugin",
                        Toast.LENGTH_LONG).show();
                return;
            }
            showStreamPicker(title, videoId, all);
        };

        AddonManager.Callback<List<StreamItem>> collect =
                new AddonManager.Callback<List<StreamItem>>() {
                    @Override
                    public void onResult(List<StreamItem> items) {
                        synchronized (all) {
                            all.addAll(items);
                        }
                        main.post(done);
                    }

                    @Override
                    public void onError(Exception e) {
                        main.post(done); // tolerate: a dead source just contributes nothing
                    }
                };
        for (String base : addonUrls) {
            mgr.streams(base, itemType, videoId, collect);
        }
        for (Plugin p : enabledPlugins) {
            plugins.pluginStreams(p.baseUrl, itemType, videoId,
                    new PluginManager.Callback<List<StreamItem>>() {
                        @Override
                        public void onResult(List<StreamItem> items) {
                            collect.onResult(items);
                        }

                        @Override
                        public void onError(Exception e) {
                            collect.onError(e);
                        }
                    });
        }
    }

    private void showStreamPicker(String title, String videoId, List<StreamItem> streams) {
        String[] labels = new String[streams.size()];
        for (int i = 0; i < streams.size(); i++) {
            StreamItem s = streams.get(i);
            labels[i] = s.title + "\n" + hostOf(s.source);
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose stream")
                .setItems(labels, (d, which) -> {
                    StreamItem s = streams.get(which);
                    Intent i = new Intent(DetailsActivity.this, PlayerActivity.class);
                    i.putExtra("url", s.url);
                    i.putExtra("title", title);
                    i.putExtra("type", itemType);
                    i.putExtra("video_id", videoId);
                    startActivity(i);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
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
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Cahaya/0.7.0 (Android TV)");
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
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
