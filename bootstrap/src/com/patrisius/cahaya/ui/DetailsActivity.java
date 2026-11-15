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
import com.patrisius.cahaya.addon.SubtitleItem;
import com.patrisius.cahaya.torrent.TorrentStreamer;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;
import com.patrisius.cahaya.jsrunner.JsScraperRunner;
import com.patrisius.cahaya.scraper.FibWatchScraper;
import com.patrisius.cahaya.scraper.Scraper;

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

        // v1.26.4: MovieBox fallback metadata, passed by MainActivity
        // for titles no catalog resolves (e.g. Alas Roban, which
        // Cinemeta does not carry) — build Details from it directly,
        // no /meta fetch. Streams still gather by title/year, so the
        // MovieBox providers return playable links.
        String mbMeta = getIntent().getStringExtra("mb_meta");
        if (mbMeta != null) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(mbMeta);
                details = MetaDetails.fromMovieBox(itemId, itemType,
                        o.optString("name", ""),
                        o.optString("poster", ""),
                        o.optString("desc", ""),
                        o.optString("year", ""),
                        o.optString("genres", ""),
                        o.optString("rating", ""),
                        o.optInt("episodes", 0));
                buildUi(details);
                return;
            } catch (Exception ignored) {
                // fall through to the /meta fetch
            }
        }

        TextView loading = new TextView(this);
        loading.setText("Loading details…");
        loading.setTextColor(Color.GRAY);
        loading.setPadding(dp(28), dp(28), 0, 0);
        setContentView(loading);

        // v1.3.1: TMDB-addon ids fetch meta from the TMDB addon.
        // v1.45.2: TMDB-merged episode titles, so the episode list
        // and the play picker show the real title even when
        // Cinemeta only has "Episode N".
        String metaAddon = itemId != null && itemId.startsWith("tmdb:")
                ? AddonManager.TMDB_ADDON : AddonManager.DEFAULT_ADDON;
        mgr.metaWithEpisodeTitles(metaAddon, itemType, itemId,
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
        // v1.7.0: on phones the poster stacks above the info instead
        // of beside it — side-by-side left the info column ~50dp wide.
        boolean phone = PhoneUi.isPhone(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(phone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setPadding(phone ? dp(16) : dp(28), phone ? dp(16) : dp(28),
                phone ? dp(16) : dp(28), phone ? dp(16) : dp(28));

        ImageView poster = new ImageView(this);
        int pw = phone ? dp(160) : dp(220);
        poster.setLayoutParams(new LinearLayout.LayoutParams(pw, phone ? dp(240) : dp(320)));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.parseColor("#23262B"));
        root.addView(poster);
        loadPoster(d.poster, poster);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = phone
                ? new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.setMargins(phone ? 0 : dp(28), phone ? dp(16) : 0, 0, 0);
        root.addView(info, ilp);

        TextView title = new TextView(this);
        title.setText(d.name);
        title.setTextSize(phone ? 24 : 32);
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
        info.addView(play, new LinearLayout.LayoutParams(
                phone ? ViewGroup.LayoutParams.MATCH_PARENT : dp(220),
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
        // v1.36.4: unified title — "S1E8 - Episode Title".
        StringBuilder lb = new StringBuilder();
        if (vi.season > 0 && vi.episode > 0) {
            lb.append("S").append(vi.season).append("E").append(vi.episode).append(" - ");
        } else if (vi.season > 0) {
            lb.append("S").append(vi.season).append(" - ");
        } else if (vi.episode > 0) {
            lb.append("E").append(vi.episode).append(" - ");
        }
        lb.append(vi.title);
        String label = lb.toString();
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
        // v1.45.0: the stream picker title names the episode, not
        // "Choose stream" — same unified format as the player's
        // next-episode title (v1.36.4): "S1E15 - Series - Episode".
        // For a movie there is no episode, so it is just the title.
        final String title = vi != null
                ? (vi.season > 0 && vi.episode > 0
                        ? "S" + vi.season + "E" + vi.episode + " - " : "")
                  + details.name
                  + (vi.title != null && !vi.title.isEmpty() ? " - " + vi.title : "")
                : details.name;

        // v1.42.0: animated loading dialog (spinner + message) —
        // the old bare text looked frozen while 65 providers ran.
        final AlertDialog progress = LoadingDialog.show(this, "Finding streams…");

        final List<StreamItem> all = new ArrayList<>();
        final List<String> addonUrls = new ArrayList<>(mgr.addonUrls());
        final List<Plugin> enabledPlugins = new ArrayList<>();
        for (Plugin p : plugins.plugins()) {
            if (p.enabled) {
                enabledPlugins.add(p);
            }
        }
        // v1.23.0: pending counts EVERY counted source exactly —
        // addons + plugins + FibWatch (1) + JS scrapers (array length).
        // The old "+3" dated from 2 JS providers; with 65 providers the
        // picker fired after 3 of 5 completions and dropped the late
        // ones. The site sniffer count is added below (addAndGet) once
        // collect exists; all completions post to main, so none can
        // fire before this method returns.
        // v1.39.0: all 65 Nuvio providers run in one batch. MovieBox
        // + the fast ones (VidRock, DesiFlix) are first in the array
        // and first in the picker; the rest land in "More".
        // v1.43.0: progressive picker. The picker no longer waits
        // for all 65 providers: it opens as soon as the priority
        // providers (MovieBox, VidRock, DesiFlix, 4khdhub) are back
        // — or 5s after the search starts, whichever comes first —
        // and late providers append their column live via
        // StreamPicker.addStreams(). v1.44.0: no timeout on provider
        // load at all — a slow provider runs until it returns or
        // errors (the 15s/30s timeouts cut providers off before
        // they could finish under the 65-WebView load). The picker
        // still opens at 5s, so a slow provider never blocks it.
        String[] jsProviders = JsScraperRunner.ALL_NUVIO_PROVIDERS;
        final java.util.Set<String> PRIORITY =
                new java.util.HashSet<>(java.util.Arrays.asList(
                        "moviebox", "movieboxtv", "vidrock", "desiflix", "4khdhub"));
        final java.util.Set<String> priorityDone =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        final boolean[] pickerShown = {false};
        final android.os.Handler earlyHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable showEarly = () -> {
            if (pickerShown[0]) return;
            synchronized (all) {
                if (all.isEmpty()) return; // nothing yet — let done show it
            }
            pickerShown[0] = true;
            earlyHandler.removeCallbacksAndMessages(null);
            progress.dismiss();
            showStreamPicker(title, videoId, new ArrayList<>(all));
        };
        // Deadline: open the picker within 5s even if a priority
        // provider is slow — the rest append live.
        earlyHandler.postDelayed(showEarly, 5000);
        final AtomicInteger pending = new AtomicInteger(
                addonUrls.size() + enabledPlugins.size() + 1 + jsProviders.length);
        if (pending.get() == 0) {
            earlyHandler.removeCallbacksAndMessages(null);
            progress.dismiss();
            Toast.makeText(this, "No addons or plugins installed", Toast.LENGTH_SHORT).show();
            return;
        }

        final Runnable done = () -> {
            if (pending.decrementAndGet() > 0) {
                return;
            }
            earlyHandler.removeCallbacksAndMessages(null);
            progress.dismiss();
            if (pickerShown[0]) {
                // Picker already open — late providers appended live.
                // All providers are done now, so hide the loading footer.
                StreamPicker.setLoading(false);
                return;
            }
            if (all.isEmpty()) {
                Toast.makeText(DetailsActivity.this,
                        "No streams found — install a streams addon or plugin",
                        Toast.LENGTH_LONG).show();
                return;
            }
            // v1.5.1: StreamPicker sorts seeders-desc within each column.
            showStreamPicker(title, videoId, all);
            StreamPicker.setLoading(false);
        };

        AddonManager.Callback<List<StreamItem>> collect =
                new AddonManager.Callback<List<StreamItem>>() {
                    @Override
                    public void onResult(List<StreamItem> items) {
                        synchronized (all) {
                            all.addAll(items);
                        }
                        if (pickerShown[0]) {
                            main.post(() -> StreamPicker.addStreams(items));
                        }
                        main.post(done);
                    }

                    @Override
                    public void onError(Exception e) {
                        main.post(done); // tolerate: a dead source just contributes nothing
                    }
                };
        // v1.43.0: note a JS provider's completion so the picker
        // can open early once the priority set is back.
        final java.util.function.Consumer<String> noteProviderDone = pid -> {
            if (!PRIORITY.contains(pid)) return;
            priorityDone.add(pid);
            if (!pickerShown[0] && priorityDone.containsAll(PRIORITY)) {
                main.post(showEarly);
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
        // In-app scraper (3rd way): searches by Cinemeta title/year,
        // not by video id — episode title is NOT used, the series
        // title is, so episodes resolve to the right series page.
        int scraperYear = 0;
        if (details.releaseInfo != null) {
            java.util.regex.Matcher ym = java.util.regex.Pattern
                    .compile("\\b(19|20)\\d{2}\\b")
                    .matcher(details.releaseInfo);
            if (ym.find()) {
                try {
                    scraperYear = Integer.parseInt(ym.group());
                } catch (Exception ignored) {
                }
            }
        }
        int scraperSeason = vi != null ? vi.season : 0;
        int scraperEpisode = vi != null ? vi.episode : 0;
        new FibWatchScraper().getStreams(details.name, scraperYear,
                itemType, scraperSeason, scraperEpisode,
                new Scraper.Callback() {
                    @Override
                    public void onResult(List<StreamItem> items) {
                        collect.onResult(items);
                    }

                    @Override
                    public void onError(Exception e) {
                        collect.onError(e);
                    }
                });
        // WebView JS scrapers (2nd way): each provider runs in its own
        // headless WebView. 4khdhub/PlayIMDb need Cinemeta's moviedb_id;
        // the MovieBox providers search by title/year and run without
        // it (v1.26.4 — a fallback Details has no moviedb_id, and its
        // MovieBox links are the whole point). Fail-soft: a dead
        // WebView never blocks the picker.
        // v1.5.0: TMDB addon meta carries no moviedbId — derive the
        // numeric id from a tmdb: id so the JS scrapers still run.
        int jsMovieDbId = details.moviedbId;
        if (jsMovieDbId == 0 && details.id != null
                && details.id.startsWith("tmdb:")) {
            try {
                jsMovieDbId = Integer.parseInt(details.id.substring(5));
            } catch (Exception ignored) {
            }
        }
        for (String pid : jsProviders) {
            final String providerId = pid;
            new JsScraperRunner(this, providerId).run(jsMovieDbId, itemType,
                    scraperSeason, scraperEpisode, details.name, scraperYear,
                    new JsScraperRunner.Callback() {
                        @Override
                        public void onResult(List<StreamItem> items) {
                            noteProviderDone.accept(providerId);
                            collect.onResult(items);
                        }

                        @Override
                        public void onError(String message) {
                            noteProviderDone.accept(providerId);
                            collect.onError(new Exception(message));
                        }
                    });
        }
    }

    private void showStreamPicker(String title, String videoId, List<StreamItem> streams) {
        // v1.5.1: grouped column picker (PlayIMDb / Torrentio /
        // 4khdhub); the pick action is unchanged.
        // v1.45.0: the dialog title is the episode title itself
        // ("S1E15 - Series - Episode"), not "Choose stream" — the
        // title arrives already built that way from playVideo.
        StreamPicker.show(this, title, streams, s -> {
            if (s.isTorrent()) {
                playTorrent(title, videoId, s);
                return;
            }
            Intent i = new Intent(DetailsActivity.this, PlayerActivity.class);
            i.putExtra("url", s.url);
            i.putExtra("title", title);
            i.putExtra("type", itemType);
            i.putExtra("video_id", videoId);
            i.putExtra("poster", details.poster);
            if (s.headers != null && !s.headers.isEmpty()) {
                StringBuilder hb = new StringBuilder();
                for (java.util.Map.Entry<String, String> e : s.headers.entrySet()) {
                    if (hb.length() > 0) hb.append("\n");
                    hb.append(e.getKey()).append(": ").append(e.getValue());
                }
                i.putExtra("headers", hb.toString());
            }
            // v1.34.0: provider-attached subtitles (MovieBox's own
            // captions). PlayerActivity merges them into the subtitle
            // picker alongside the addon results.
            if (s.subtitles != null && !s.subtitles.isEmpty()) {
                String[] su = new String[s.subtitles.size()];
                String[] sl = new String[s.subtitles.size()];
                String[] sb = new String[s.subtitles.size()];
                for (int si = 0; si < s.subtitles.size(); si++) {
                    SubtitleItem sub = s.subtitles.get(si);
                    su[si] = sub.url;
                    sl[si] = sub.lang;
                    sb[si] = sub.label != null && !sub.label.isEmpty() ? sub.label : sub.lang;
                }
                i.putExtra("sub_urls", su);
                i.putExtra("sub_langs", sl);
                i.putExtra("sub_labels", sb);
            }
            // Pass resume position if coming from Continue Watching
            double resume = getIntent().getDoubleExtra("resume_pos", 0);
            if (resume > 0) i.putExtra("start_pos", resume);
            startActivity(i);
        }, PlayerActivity.lastPlayedUrl(this, videoId), "Last played");
    }

    /** Nuvio-style: stream the torrent through our own engine. */
    private void playTorrent(String title, String videoId, StreamItem s) {
        TextView msg = new TextView(this);
        msg.setPadding(48, 32, 48, 32);
        msg.setTextSize(18);
        msg.setText("Starting…");
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Torrent")
                .setView(msg)
                .setNegativeButton("Cancel", (d, w) -> TorrentStreamer.stopActive())
                .setCancelable(false)
                .create();
        dlg.show();

        new TorrentStreamer().start(this, s.url, s.fileIdx,
                new TorrentStreamer.Callback() {
                    @Override
                    public void onProgress(String stage, int percent, String detail) {
                        main.post(() -> {
                            if (dlg.isShowing()) {
                                msg.setText(stage + "\n" + percent + "%"
                                        + (detail.isEmpty() ? "" : "\n" + detail));
                            }
                        });
                    }

                    @Override
                    public void onReady(String httpUrl, String fileName) {
                        main.post(() -> {
                            if (dlg.isShowing()) dlg.dismiss();
                            launchTorrentPlayer(httpUrl, title, videoId);
                        });
                    }

                    @Override
                    public void onError(String errMsg) {
                        main.post(() -> {
                            if (dlg.isShowing()) dlg.dismiss();
                            Toast.makeText(DetailsActivity.this, errMsg,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    /**
     * Launch the player straight away with the torrent's local http URL. Subtitles are no longer
     * looked up here: PlayerActivity searches every addon/plugin itself using type + video_id,
     * so playback starts immediately and the subtitle list fills in while the user watches.
     */
    private void launchTorrentPlayer(String httpUrl, String title, String videoId) {
        Intent i = new Intent(DetailsActivity.this, PlayerActivity.class);
        i.putExtra("url", httpUrl);
        i.putExtra("title", title);
        i.putExtra("type", itemType);
        i.putExtra("video_id", videoId);
        // details may be null here; poster is optional
        try { i.putExtra("poster", details.poster); } catch (Throwable ignored) {}
        startActivity(i);
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
                final Bitmap bmp = PosterUtil.decodeSampled(in, 960);
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
