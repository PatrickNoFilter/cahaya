package com.patrisius.cahaya.addon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.1 bootstrap addon manager: zero-dependency Stremio-protocol client.
 * HttpURLConnection + org.json (both in the Android framework), background
 * threads via ExecutorService, results posted to the main thread.
 */
public class AddonManager {

    /** Default addon installed on first run. */
    public static final String DEFAULT_ADDON = "https://v3-cinemeta.strem.io";
    /** Default subtitle addon installed on first run (keyless community subs). */
    public static final String SUBTITLES_DEFAULT = "https://subsource.strem.top";

    public interface Callback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());

    public AddonManager(Context ctx) {
        prefs = ctx.getSharedPreferences("cahaya_addons", Context.MODE_PRIVATE);
    }

    public Set<String> addonUrls() {
        return prefs.getStringSet("urls", new HashSet<String>());
    }

    /** Installs the default addons on first run (validates their manifests). */
    public void ensureDefaults(Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                Set<String> urls = new HashSet<>(addonUrls());
                boolean fresh = false;
                for (String def : new String[]{DEFAULT_ADDON, SUBTITLES_DEFAULT}) {
                    if (!urls.contains(def)) {
                        fetchText(def + "/manifest.json"); // throws if dead
                        urls.add(def);
                        fresh = true;
                    }
                }
                prefs.edit().putStringSet("urls", urls).apply();
                post(cb, fresh, null);
            } catch (Exception e) {
                post(cb, false, e);
            }
        });
    }

    /** Async raw GET, result on the main thread. 404 and friends -> onError. */
    public void fetchTextAsync(final String url, final Callback<String> cb) {
        io.execute(() -> {
            try {
                post(cb, fetchText(url), null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    /** GET {base}/meta/{type}/{id}.json -> full details. */
    public void meta(String baseUrl, String type, String id,
                     Callback<MetaDetails> cb) {
        fetchTextAsync(baseUrl + "/meta/" + type + "/" + id + ".json",
                new Callback<String>() {
                    @Override
                    public void onResult(String body) {
                        try {
                            JSONObject m = new JSONObject(body).getJSONObject("meta");
                            MetaDetails d = new MetaDetails();
                            d.id = m.optString("id", id);
                            d.type = m.optString("type", type);
                            d.name = m.optString("name", "?");
                            d.poster = m.optString("poster", null);
                            d.background = m.optString("background", null);
                            d.description = m.optString("description", "");
                            d.releaseInfo = m.optString("releaseInfo",
                                    m.optString("year", ""));
                            JSONArray vids = m.optJSONArray("videos");
                            if (vids != null) {
                                for (int i = 0; i < vids.length(); i++) {
                                    JSONObject v = vids.getJSONObject(i);
                                    MetaDetails.VideoInfo vi = new MetaDetails.VideoInfo();
                                    vi.id = v.optString("id", "");
                                    if (vi.id.isEmpty()) {
                                        continue;
                                    }
                                    vi.title = v.optString("title",
                                            v.optString("name", "Episode " + v.optInt("episode", i + 1)));
                                    vi.thumbnail = v.optString("thumbnail", null);
                                    vi.season = v.optInt("season", 0);
                                    vi.episode = v.optInt("episode", 0);
                                    d.videos.add(vi);
                                }
                            }
                            cb.onResult(d);
                        } catch (Exception e) {
                            cb.onError(e);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        cb.onError(e);
                    }
                });
    }

    /**
     * GET {base}/stream/{type}/{videoId}.json -> playable streams.
     * Addons without the resource answer 404 -> empty list (not an error),
     * so aggregation across addons stays simple.
     */
    public void streams(String baseUrl, String type, String videoId,
                        Callback<List<StreamItem>> cb) {
        fetchTextAsync(baseUrl + "/stream/" + type + "/" + videoId + ".json",
                new Callback<String>() {
                    @Override
                    public void onResult(String body) {
                        try {
                            List<StreamItem> out = new ArrayList<>();
                            JSONArray arr = new JSONObject(body).optJSONArray("streams");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject s = arr.getJSONObject(i);
                                    String u = s.optString("url", "");
                                    if (u.isEmpty()) {
                                        continue;
                                    }
                                    String t = s.optString("title",
                                            s.optString("name", "Stream"));
                                    // Stremio titles are often "filename\nsize ▲"; first line is enough
                                    int nl = t.indexOf('\n');
                                    if (nl > 0) {
                                        t = t.substring(0, nl);
                                    }
                                    out.add(new StreamItem(u, t, baseUrl));
                                }
                            }
                            cb.onResult(out);
                        } catch (Exception e) {
                            cb.onError(e);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                            cb.onResult(new ArrayList<StreamItem>());
                        } else {
                            cb.onError(e);
                        }
                    }
                });
    }

    /**
     * GET {base}/subtitles/{type}/{videoId}.json -> subtitle files.
     * 404 -> empty list (addon has no subs for this title).
     */
    public void subtitles(String baseUrl, String type, String videoId,
                          Callback<List<SubtitleItem>> cb) {
        fetchTextAsync(baseUrl + "/subtitles/" + type + "/" + videoId + ".json",
                new Callback<String>() {
                    @Override
                    public void onResult(String body) {
                        try {
                            List<SubtitleItem> out = new ArrayList<>();
                            JSONArray arr = new JSONObject(body).optJSONArray("subtitles");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject s = arr.getJSONObject(i);
                                    String u = s.optString("url", "");
                                    if (u.isEmpty()) {
                                        continue;
                                    }
                                    String lang = s.optString("lang", "?");
                                    out.add(new SubtitleItem(u, lang,
                                            lang + " · " + hostOf(baseUrl)));
                                }
                            }
                            cb.onResult(out);
                        } catch (Exception e) {
                            cb.onError(e);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                            cb.onResult(new ArrayList<SubtitleItem>());
                        } else {
                            cb.onError(e);
                        }
                    }
                });
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** GET {base}/catalog/{type}/{id}.json -> items. */
    public void catalog(String baseUrl, String type, String catalogId,
                        Callback<List<CatalogItem>> cb) {
        io.execute(() -> {
            try {
                String body = fetchText(
                        baseUrl + "/catalog/" + type + "/" + catalogId + ".json");
                JSONObject root = new JSONObject(body);
                JSONArray metas = root.optJSONArray("metas");
                List<CatalogItem> out = new ArrayList<>();
                if (metas != null) {
                    for (int i = 0; i < metas.length(); i++) {
                        JSONObject m = metas.getJSONObject(i);
                        String id = m.optString("id", m.optString("imdb_id", ""));
                        if (id.isEmpty()) {
                            continue;
                        }
                        out.add(new CatalogItem(
                                id,
                                m.optString("type", type),
                                m.optString("name", "?"),
                                m.optString("poster", null)));
                    }
                }
                post(cb, out, null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    private <T> void post(Callback<T> cb, T result, Exception e) {
        main.post(() -> {
            if (e == null) {
                cb.onResult(result);
            } else {
                cb.onError(e);
            }
        });
    }

    /** Plain GET with a Cahaya user agent. Follows redirects. */
    public static String fetchText(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Cahaya/0.1.0 (Android TV)");
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " for " + urlStr);
        }
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        r.close();
        return sb.toString();
    }
}
