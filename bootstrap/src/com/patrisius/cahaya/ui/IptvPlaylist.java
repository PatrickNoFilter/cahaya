package com.patrisius.cahaya.ui;

/**
 * v1.35.0: Live TV playlists. Sources = iptv-org Indonesia
 * (https://iptv-org.github.io/iptv/countries/id.m3u) + the curated
 * riotryulianto Indonesian list (playlist.m3u + backup-streams.m3u,
 * raw.githubusercontent.com), merged with the user's 7 curated
 * YouTube live channels.
 *
 * One channel = one list row. Entries that name the same channel
 * (CNN Indonesia as a direct stream AND as a YouTube live stream,
 * or "ANTV" and "ANTV 1" from two lists) merge into one channel
 * with two servers, tried round-robin until one plays — no channel
 * ever appears twice in the list. List order is News first, then
 * National, then Local.
 *
 * YouTube servers are resolved at play time by YtDlpResolver, which
 * runs the bundled yt-dlp (ytdlnis approach: native CPython +
 * yt-dlp script) and returns the live HLS manifest URLs. The old
 * watch-page extraction is gone: its direct googlevideo URLs are
 * rejected by YouTube (HTTP 403 — the `n` parameter must be
 * transformed by YouTube's player JS).
 */
public class IptvPlaylist {

    /** v1.35.0: three Indonesian sources, fetched and merged.
     *  iptv-org is the base; riotryulianto is a curated, tested
     *  Indonesian list (official streams: Trans TV, TVRI, RodjaTV,
     *  RRI NET, NusantaraTV, DENS.TV) that carries the big national
     *  networks iptv-org lacks (RCTI, Indosiar, GTV, MNCTV, NET,
     *  Trans TV, Trans7) plus alternate servers for shared channels.
     *  Its backup list adds a few more. Same channel from two
     *  sources = one row with two servers (merged by name). */
    public static final String PLAYLIST_URL =
            "https://iptv-org.github.io/iptv/countries/id.m3u";
    public static final String[] PLAYLIST_URLS = {
            "https://iptv-org.github.io/iptv/countries/id.m3u",
            "https://raw.githubusercontent.com/riotryulianto/iptv-playlists/main/playlist.m3u",
            "https://raw.githubusercontent.com/riotryulianto/iptv-playlists/main/backup-streams.m3u",
    };
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

    /** Fetch + parse the playlists on a worker thread, merge + dedupe +
     *  sort them, cache the result, and deliver it on the main thread.
     *  Each source is fetched independently — one failing does not
     *  lose the others. Falls back to the cache when all fail. */
    public static void load(android.content.Context ctx, LoadCallback cb) {
        final android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            java.util.List<TvChannel> all = new java.util.ArrayList<>();
            for (String url : PLAYLIST_URLS) {
                try {
                    String m3u = httpGet(url, 15000);
                    java.util.List<TvChannel> parsed = parseM3u(m3u);
                    TvLog.log("iptv fetched " + url + " bytes=" + m3u.length()
                            + " parsed=" + parsed.size());
                    all.addAll(parsed);
                } catch (Throwable t) {
                    TvLog.log("iptv fetch FAILED " + url + ": " + t);
                }
            }
            java.util.List<TvChannel> list = all.isEmpty() ? null : all;
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
     *  tag, the resolution suffix, the [Not 24/7]/[Geo-blocked] tags,
     *  the "(Indonesia)" disambiguator, and a trailing variant number
     *  ("ANTV 1" / "Kompas TV 2" are the same channel as "ANTV" /
     *  "Kompas TV" with another server); lowercase. */
    private static String normalize(String name) {
        if (name == null) return "";
        String n = name.trim();
        n = n.replaceAll("(?i)\\s*\\(YouTube\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\((1080p|720p|480p|360p|2160p)\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\[(Not 24/7|Geo-blocked)\\]\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\(Indonesia\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s+\\d+\\s*$", "");
        return n.trim().toLowerCase(java.util.Locale.US);
    }

    /** Parse an M3U: an #EXTINF line (attributes + name after the last
     *  comma) followed by the stream URL on the next non-# line.
     *  #EXTVLCOPT lines in between are read for http-user-agent /
     *  http-referrer (the riotryulianto list carries its UA there,
     *  not in the #EXTINF attributes); other # lines are skipped.
     *  Each entry becomes a one-server channel; merging happens in
     *  mergeAndSort. */
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
            } else if (line.startsWith("#EXTVLCOPT:")) {
                // v1.35.0: riotryulianto puts the UA/referrer here.
                String opt = line.substring("#EXTVLCOPT:".length());
                if (opt.toLowerCase(java.util.Locale.US).startsWith("http-user-agent=")) {
                    pUa = opt.substring("http-user-agent=".length()).trim();
                } else if (opt.toLowerCase(java.util.Locale.US).startsWith("http-referrer=")) {
                    pRef = opt.substring("http-referrer=".length()).trim();
                }
            } else if (line.startsWith("#")) {
                continue; // #EXTM3U, comments
            } else if (pending != null) {
                pUrl = line;
                if (pending.name != null && !pending.name.isEmpty()
                        && pUrl != null && !pUrl.isEmpty()) {
                    // v1.35.1: some lists append Kodi-style header
                    // suffixes ("...mpd|Referer=https://..."). The "|"
                    // part is NOT part of the URL — split it off and
                    // use it as the server's headers, or mpv gets a
                    // malformed URL and the channel can never play.
                    String cleanUrl = pUrl;
                    String sRef = nz(pRef), sUa = nz(pUa);
                    int pipe = pUrl.indexOf('|');
                    if (pipe >= 0) {
                        cleanUrl = pUrl.substring(0, pipe).trim();
                        for (String kv : pUrl.substring(pipe + 1).split("\\|")) {
                            int eq = kv.indexOf('=');
                            if (eq <= 0) continue;
                            String k = kv.substring(0, eq).trim();
                            String v = kv.substring(eq + 1).trim();
                            if (k.equalsIgnoreCase("Referer")) sRef = v;
                            else if (k.equalsIgnoreCase("User-Agent")) sUa = v;
                        }
                    }
                    if (!cleanUrl.isEmpty()) {
                        pending.addServer(new TvChannel.Server(
                                cleanUrl, "Direct", sUa, sRef, ""));
                        out.add(pending);
                    }
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
