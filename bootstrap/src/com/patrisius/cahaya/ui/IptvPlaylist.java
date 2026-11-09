package com.patrisius.cahaya.ui;

/**
 * v1.33.0: Live TV playlist. Source = the iptv-org Indonesia list
 * (https://iptv-org.github.io/iptv/countries/id.m3u, 173 channels at
 * last count) merged with the user's 7 curated YouTube live channels.
 *
 * One channel = one list row. Entries that name the same channel
 * (CNN Indonesia as a direct stream AND as a YouTube live stream)
 * merge into one channel with two servers, tried round-robin until
 * one plays — no channel ever appears twice in the list. List order
 * is News first, then National, then Local.
 *
 * YouTube servers are resolved at play time by YtDlpResolver, which
 * runs the bundled yt-dlp (ytdlnis approach: native CPython +
 * yt-dlp script) and returns the live HLS manifest URLs. The old
 * watch-page extraction is gone: its direct googlevideo URLs are
 * rejected by YouTube (HTTP 403 — the `n` parameter must be
 * transformed by YouTube's player JS).
 */
public class IptvPlaylist {

    public static final String PLAYLIST_URL =
            "https://iptv-org.github.io/iptv/countries/id.m3u";
    private static final String CACHE_NAME = "tv_channels.json";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36";

    /** The user's curated YouTube live channels, in the order he sent them. */
    private static final String[][] YOUTUBE = {
            // name, videoId, group, tvgId, logo
            {"CNN Indonesia", "jWfQVN-XKSg", "News", "CNNIndonesia.id",
                    "https://i.imgur.com/l0JyOrm.png"},
            {"Kompas TV", "DOOrIxw5xOw", "News", "KompasTV.id",
                    "https://i.imgur.com/bD6UHeZ.png"},
            {"Metro TV", "tiIOHgDI3Ow", "News", "MetroTV.id",
                    "https://i.imgur.com/QnU70NI.png"},
            {"iNews TV", "UMe1AKUyUN0", "News", "iNews.id", ""},
            {"tvOne", "rQJoEpzKkNk", "News", "tvOne.id",
                    "https://upload.wikimedia.org/wikipedia/commons/thumb/9/91/TvOne_2023.svg/960px-TvOne_2023.svg.png"},
            {"SINDOnews TV", "hFsof3_AkB4", "News", "SINDOnews.id", ""},
            {"IDX Channel", "hjCkB9XRpkU", "Business", "IDXChannel.id", ""},
    };

    /** National free-to-air networks (normalized names). */
    private static final java.util.Set<String> NATIONAL = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "rcti", "sctv", "indosiar", "antv", "gtv", "moji", "rtv",
                    "trans tv", "trans7", "mnctv", "net", "tvri",
                    "trans tv hd", "trans7 hd"));

    /** Extra news names whose iptv-org group is not "News". */
    private static final java.util.Set<String> NEWS_EXTRA = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "cnn indonesia", "kompas tv", "metro tv", "tvone",
                    "inews tv", "sindonews tv", "idx channel",
                    "beritasatu", "bn channel", "btv", "cnbc indonesia",
                    "inews", "sindo news tv"));

    public interface LoadCallback {
        /** Called on the main thread. Null when neither network nor cache worked. */
        void onLoaded(java.util.List<TvChannel> channels);
    }

    /** One resolved YouTube live stream: HLS video + audio manifests. */
    public static class Resolved {
        public final String videoUrl;
        public final String audioUrl;
        public Resolved(String videoUrl, String audioUrl) {
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
        }
    }

    /** Fetch + parse the playlist on a worker thread, merge + dedupe +
     *  sort it, cache the result, and deliver it on the main thread.
     *  Falls back to the cache when the network fails. */
    public static void load(android.content.Context ctx, LoadCallback cb) {
        final android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            java.util.List<TvChannel> list = null;
            try {
                String m3u = httpGet(PLAYLIST_URL, 15000);
                list = parseM3u(m3u);
                TvLog.log("iptv fetched bytes=" + m3u.length()
                        + " parsed=" + list.size());
            } catch (Throwable t) {
                TvLog.log("iptv fetch FAILED: " + t);
            }
            if (list != null && !list.isEmpty()) {
                list = mergeAndSort(list);
                TvLog.log("iptv merged unique=" + list.size());
                saveCache(ctx, list);
            } else {
                list = loadCache(ctx);
                TvLog.log("iptv cache fallback: "
                        + (list == null ? "none" : String.valueOf(list.size())));
            }
            final java.util.List<TvChannel> out = list;
            main.post(() -> cb.onLoaded(out));
        }, "iptv-load").start();
    }

    /** Merge the YouTube entries with the iptv-org list, dedupe by
     *  normalized name (same channel = one row, servers appended),
     *  categorize, and sort News > National > Local. The sort is
     *  stable, so within a category the original order holds (the
     *  YouTube picks stay first in News). */
    private static java.util.List<TvChannel> mergeAndSort(
            java.util.List<TvChannel> iptv) {
        java.util.LinkedHashMap<String, TvChannel> byName =
                new java.util.LinkedHashMap<>();
        // YouTube picks first (his channels, News).
        for (String[] y : YOUTUBE) {
            TvChannel c = new TvChannel(y[0], y[3], y[4], y[2], TvChannel.CAT_NEWS);
            c.addServer(new TvChannel.Server(
                    "https://www.youtube.com/watch?v=" + y[1],
                    "YouTube", "", "", y[1]));
            byName.put(normalize(y[0]), c);
        }
        // iptv-org entries: merge into the same channel when the
        // normalized name matches, else a new row. Direct servers go
        // FIRST in a merged channel (instant; YouTube is the fallback).
        for (TvChannel e : iptv) {
            String key = normalize(e.name);
            TvChannel c = byName.get(key);
            TvChannel.Server direct = new TvChannel.Server(
                    e.servers.get(0).url, "Direct",
                    e.servers.get(0).userAgent, e.servers.get(0).referer, "");
            if (c == null) {
                c = new TvChannel(e.name, e.tvgId, e.logo, e.group,
                        categorize(e));
                c.addServer(direct);
                byName.put(key, c);
            } else {
                // Prefer the direct server first; keep the YouTube
                // server as the fallback.
                c.servers.add(0, direct);
                if (c.logo == null || c.logo.isEmpty()) c.logo = e.logo;
                if (c.tvgId == null || c.tvgId.isEmpty()) c.tvgId = e.tvgId;
            }
        }
        java.util.List<TvChannel> out =
                new java.util.ArrayList<>(byName.values());
        java.util.Collections.sort(out,
                (a, b) -> Integer.compare(a.category, b.category));
        return out;
    }

    /** News when the iptv-org group says so or the name is a known
     *  news channel; National for the big free-to-air networks;
     *  everything else (TVRI regionals, local stations) is Local. */
    private static int categorize(TvChannel e) {
        String g = e.group == null ? "" : e.group.toLowerCase(java.util.Locale.US);
        String n = normalize(e.name);
        if (g.contains("news") || NEWS_EXTRA.contains(n)) return TvChannel.CAT_NEWS;
        if (NATIONAL.contains(n)) return TvChannel.CAT_NATIONAL;
        return TvChannel.CAT_LOCAL;
    }

    /** Normalize a channel name for dedupe: strip the "(YouTube)"
     *  tag, the resolution suffix, the [Not 24/7]/[Geo-blocked] tags
     *  and the "(Indonesia)" disambiguator; lowercase. */
    private static String normalize(String name) {
        if (name == null) return "";
        String n = name.trim();
        n = n.replaceAll("(?i)\\s*\\(YouTube\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\((1080p|720p|480p|360p|2160p)\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\[(Not 24/7|Geo-blocked)\\]\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\(Indonesia\\)\\s*$", "");
        return n.trim().toLowerCase(java.util.Locale.US);
    }

    /** Parse an M3U: an #EXTINF line (attributes + name after the last
     *  comma) followed by the stream URL on the next non-# line
     *  (#EXTVLCOPT lines in between are skipped). Each entry becomes
     *  a one-server channel; merging happens in mergeAndSort. */
    private static java.util.List<TvChannel> parseM3u(String text) {
        java.util.List<TvChannel> out = new java.util.ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\n");
        TvChannel pending = null;
        String pUrl = null, pUa = null, pRef = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                pending = new TvChannel();
                pending.tvgId = attr(line, "tvg-id");
                pending.logo = attr(line, "tvg-logo");
                pending.group = attr(line, "group-title");
                pUa = attr(line, "http-user-agent");
                pRef = attr(line, "http-referrer");
                int c = line.lastIndexOf(',');
                pending.name = c >= 0 ? line.substring(c + 1).trim() : line;
            } else if (line.startsWith("#")) {
                continue; // #EXTM3U, #EXTVLCOPT, comments
            } else if (pending != null) {
                pUrl = line;
                if (pending.name != null && !pending.name.isEmpty()
                        && pUrl != null && !pUrl.isEmpty()) {
                    pending.addServer(new TvChannel.Server(
                            pUrl, "Direct", nz(pUa), nz(pRef), ""));
                    out.add(pending);
                }
                pending = null;
            }
        }
        return out;
    }

    private static String attr(String line, String key) {
        String k = key + "=\"";
        int i = line.indexOf(k);
        if (i < 0) return "";
        int s = i + k.length();
        int e = line.indexOf('"', s);
        return e < 0 ? "" : line.substring(s, e);
    }

    // ------------------------------------------------------------------
    // Cache (tv_channels.json in internal storage)
    // ------------------------------------------------------------------

    private static void saveCache(android.content.Context ctx,
                                  java.util.List<TvChannel> list) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (TvChannel c : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", nz(c.name));
                o.put("tvgId", nz(c.tvgId));
                o.put("logo", nz(c.logo));
                o.put("group", nz(c.group));
                o.put("cat", c.category);
                org.json.JSONArray sv = new org.json.JSONArray();
                for (TvChannel.Server s : c.servers) {
                    org.json.JSONObject so = new org.json.JSONObject();
                    so.put("url", nz(s.url));
                    so.put("label", nz(s.label));
                    so.put("ua", nz(s.userAgent));
                    so.put("referer", nz(s.referer));
                    so.put("yt", nz(s.youtubeId));
                    sv.put(so);
                }
                o.put("servers", sv);
                arr.put(o);
            }
            java.io.FileOutputStream fos =
                    ctx.openFileOutput(CACHE_NAME, android.content.Context.MODE_PRIVATE);
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    public static java.util.List<TvChannel> loadCache(android.content.Context ctx) {
        try {
            java.io.FileInputStream fis = ctx.openFileInput(CACHE_NAME);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            org.json.JSONArray arr =
                    new org.json.JSONArray(new String(bos.toByteArray(), "UTF-8"));
            java.util.List<TvChannel> out = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                TvChannel c = new TvChannel(o.optString("name"), o.optString("tvgId"),
                        o.optString("logo"), o.optString("group"),
                        o.optInt("cat", TvChannel.CAT_LOCAL));
                org.json.JSONArray sv = o.optJSONArray("servers");
                if (sv != null) {
                    for (int j = 0; j < sv.length(); j++) {
                        org.json.JSONObject so = sv.getJSONObject(j);
                        c.addServer(new TvChannel.Server(
                                so.optString("url"), so.optString("label"),
                                so.optString("ua"), so.optString("referer"),
                                so.optString("yt")));
                    }
                } else {
                    // Old flat cache (v1.32.x): one server from the flat fields.
                    c.addServer(new TvChannel.Server(
                            o.optString("url"), o.optString("yt").isEmpty() ? "Direct" : "YouTube",
                            o.optString("ua"), o.optString("referer"),
                            o.optString("yt")));
                }
                if (c.serverCount() > 0) out.add(c);
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // YouTube resolution (via the bundled yt-dlp)
    // ------------------------------------------------------------------

    /** Resolve a YouTube live videoId to HLS video+audio manifests
     *  through the bundled yt-dlp (ytdlnis approach). Returns null
     *  when yt-dlp fails. Call on a worker thread. */
    public static Resolved resolveYouTube(android.content.Context ctx, String videoId) {
        return YtDlpResolver.resolve(ctx, videoId);
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,id;q=0.8");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
        java.io.InputStream in = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
