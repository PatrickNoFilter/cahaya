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
    /** Nuvio TMDB catalog addon: the only built-in source with
     *  movies/series-by-language catalogs (Japanese, Korean, Chinese). */
    public static final String NUVIO_CATALOG = "https://catalog.nuvio.tv";
    /** Default subtitle addon installed on first run (keyless community subs). */
    public static final String SUBTITLES_DEFAULT = "https://subsource.strem.top";
    /** More defaults: streams + extra subtitles. Empty results are tolerated. */
    public static final String[] MORE_DEFAULTS = {
            "https://mediafusion.elfhosted.com", // streams (needs config for most sources)
            "https://subdl.strem.top",           // subtitles
            "https://torrentio.strem.fun",       // streams (main torrent source)
            "https://comet.elfhosted.com",       // streams (needs config)
            "https://catalog.nuvio.tv",          // catalog
            "https://opensubtitles-v3.strem.io", // subtitles (needs API key)
    };
    /**
     * Built-in addons, hardcoded so they survive reinstalls and data wipes.
     * These are always active; user-added addons from prefs are merged in.
     */
    public static final String[] BUILT_IN_ADDONS = {
            "https://v3-cinemeta.strem.io",
            "https://subdl.strem.top",
            "https://subsource.strem.top",
            "https://opensubtitles-v3.strem.io",
            "https://mediafusion.elfhosted.com",
            "https://catalog.nuvio.tv",
            "https://comet.elfhosted.com",
            "https://torrentio.strem.fun",
    };
    /**
     * Curated suggestions shown in the Addons screen (not auto-installed).
     * Each entry: {name, manifest base URL, note}.
     */
    public static final String[][] SUGGESTED_ADDONS = {
            {"OpenSubtitles v3", "https://opensubtitles-v3.strem.io",
                    "Needs a free API key from opensubtitles.com"},
    };

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
        // Built-ins are hardcoded so they survive reinstalls/data wipes.
        // User-added addons from prefs are merged in.
        Set<String> urls = new HashSet<>();
        for (String u : BUILT_IN_ADDONS) urls.add(u);
        urls.addAll(prefs.getStringSet("urls", new HashSet<String>()));
        return urls;
    }

    /** Installs the default addons on first run (validates their manifests). */
    public void ensureDefaults(Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                Set<String> urls = new HashSet<>(addonUrls());
                boolean fresh = false;
                List<String> defs = new ArrayList<>();
                defs.add(DEFAULT_ADDON);
                defs.add(SUBTITLES_DEFAULT);
                for (String m : MORE_DEFAULTS) {
                    defs.add(m);
                }
                for (String def : defs) {
                    if (!urls.contains(def)) {
                        // Add the default; if the manifest fetch fails (e.g. Cloudflare
                        // block), still add it — the user can remove it manually.
                        // A dead default must not block the others.
                        try {
                            fetchText(def + "/manifest.json"); // throws if dead
                        } catch (Exception skipped) {
                        }
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

    /** Installs one addon from its base URL (validates manifest.json first). */
    public void installAddon(final String baseUrl, final Callback<String> cb) {
        final String base = baseUrl.replaceAll("/+$", "");
        fetchTextAsync(base + "/manifest.json", new Callback<String>() {
            @Override
            public void onResult(String body) {
                try {
                    JSONObject m = new JSONObject(body);
                    String name = m.optString("name", base);
                    Set<String> urls = new HashSet<>(addonUrls());
                    urls.add(base);
                    prefs.edit().putStringSet("urls", urls).apply();
                    post(cb, name, null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }

            @Override
            public void onError(Exception e) {
                post(cb, null, e);
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
                            d.moviedbId = m.optInt("moviedb_id", 0);
                            JSONArray genres = m.optJSONArray("genres");
                            if (genres != null) {
                                StringBuilder g = new StringBuilder();
                                for (int gi = 0; gi < genres.length(); gi++) {
                                    if (g.length() > 0) g.append(", ");
                                    g.append(genres.optString(gi, ""));
                                }
                                d.genres = g.toString();
                            }
                            d.rating = m.optString("imdbRating", "");
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
                                    int fileIdx = s.optInt("fileIdx", -1);
                                    if (u.isEmpty()) {
                                        // Torrentio-style: infoHash instead of url
                                        u = magnetFor(s);
                                        if (u.isEmpty()) {
                                            continue;
                                        }
                                    }
                                    String name = s.optString("name", "");
                                    String t = s.optString("title", name);
                                    if (t.isEmpty()) {
                                        t = name.isEmpty() ? "Stream" : name;
                                    }
                                    int seeders = parseSeeders(t);
                                    String size = parseSize(t);
                                    // Stremio titles are often "filename\nsize ▲"; first line is enough
                                    int nl = t.indexOf('\n');
                                    if (nl > 0) {
                                        t = t.substring(0, nl);
                                    }
                                    // Torrentio puts quality ("4k HDR") on name's 2nd line
                                    int nnl = name.indexOf('\n');
                                    if (nnl > 0 && nnl + 1 < name.length()) {
                                        String qual = name.substring(nnl + 1).trim();
                                        int qnl = qual.indexOf('\n');
                                        if (qnl > 0) qual = qual.substring(0, qnl);
                                        if (!qual.isEmpty()) {
                                            t = "[" + qual + "] " + t;
                                        }
                                    }
                                    StreamItem item = new StreamItem(u, t, baseUrl, fileIdx);
                                    item.seeders = seeders;
                                    item.size = size;
                                    out.add(item);
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

    /** Torrentio titles carry "👤 186 💾 6.91 GB" on the 2nd line. -1 if absent. */
    private static int parseSeeders(String title) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("👤\\s*(\\d+)").matcher(title);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** e.g. "6.91 GB" from "💾 6.91 GB". null if absent. */
    private static String parseSize(String title) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("💾\\s*([\\d.]+\\s*[KMGT]B)").matcher(title);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Public trackers baked into every magnet: Torrentio entries often carry
     *  no sources of their own, and pure-DHT metadata fetch stalls. */
    private static final String[] PUBLIC_TRACKERS = {
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.edkj.club:6969/announce",
    };

    /** Builds a magnet URI from a stream entry carrying infoHash (+ trackers). */
    private static String magnetFor(JSONObject s) {
        String infoHash = s.optString("infoHash", "").trim();
        if (infoHash.isEmpty()) {
            return "";
        }
        StringBuilder m = new StringBuilder("magnet:?xt=urn:btih:")
                .append(infoHash);
        try {
            String dn = s.optString("title", s.optString("name", ""));
            int nl = dn.indexOf('\n');
            if (nl > 0) dn = dn.substring(0, nl);
            dn = dn.trim();
            if (!dn.isEmpty()) {
                m.append("&dn=").append(java.net.URLEncoder.encode(dn, "UTF-8"));
            }
            java.util.LinkedHashSet<String> trackers = new java.util.LinkedHashSet<>();
            JSONArray sources = s.optJSONArray("sources");
            if (sources != null) {
                for (int i = 0; i < sources.length(); i++) {
                    String tr = sources.optString(i, "").trim();
                    // Torrentio lists trackers as "tracker:udp://..."
                    if (tr.startsWith("tracker:")) {
                        tr = tr.substring("tracker:".length());
                    }
                    if (!tr.isEmpty() && !tr.startsWith("dht:")) {
                        trackers.add(tr);
                    }
                }
            }
            for (String tr : PUBLIC_TRACKERS) {
                trackers.add(tr);
            }
            for (String tr : trackers) {
                m.append("&tr=").append(
                        java.net.URLEncoder.encode(tr, "UTF-8"));
            }
        } catch (Exception ignored) {
        }
        return m.toString();
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
        catalogExtra(baseUrl, type, catalogId, null, cb);
    }

    /**
     * GET {base}/catalog/{type}/{id}/{extra}.json -> items, e.g. Nuvio's
     * movies-by-language catalog with extra "genre=Japanese". Null extra
     * behaves exactly like catalog().
     */
    public void catalogExtra(String baseUrl, String type, String catalogId,
                        String extra, Callback<List<CatalogItem>> cb) {
        io.execute(() -> {
            try {
                String url = baseUrl + "/catalog/" + type + "/" + catalogId;
                if (extra != null && !extra.isEmpty()) {
                    url += "/" + extra;
                }
                url += ".json";
                String body = fetchText(url);
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
