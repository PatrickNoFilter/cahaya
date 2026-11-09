package com.patrisius.cahaya.ui;

/**
 * v1.33.0: resolve a YouTube live videoId to HLS manifests through
 * the bundled yt-dlp (ytdlnis approach). Mirrors
 * YTDLPUtil.getStreamingUrlAndChapters: the command prints
 * "%(.{urls,chapters})s" with a format sort that prefers HLS
 * ("-S res:720,+proto:m3u8"); for a live stream the `urls` value
 * holds the video HLS manifest and the audio HLS manifest
 * (newline-separated). The manifests are what mpv plays — the
 * direct googlevideo URLs from the watch page are rejected by
 * YouTube (HTTP 403, `n` challenge).
 *
 * Results are cached in memory until shortly before the URL's
 * `expire` timestamp, so re-zapping to a channel is instant.
 * Call on a worker thread. Subprocess timeout: 60 s.
 */
public class YtDlpResolver {
    private static final long TIMEOUT_MS = 60000;
    private static final long CACHE_MARGIN_MS = 5 * 60 * 1000;

    private static class Cached {
        final String videoUrl, audioUrl;
        final long expireMs;
        Cached(String v, String a, long e) { videoUrl = v; audioUrl = a; expireMs = e; }
    }

    private static final java.util.Map<String, Cached> CACHE =
            new java.util.HashMap<>();

    public static IptvPlaylist.Resolved resolve(android.content.Context ctx,
                                                String videoId) {
        if (videoId == null || videoId.isEmpty()) return null;
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            Cached c = CACHE.get(videoId);
            if (c != null && now < c.expireMs - CACHE_MARGIN_MS) {
                TvLog.log("ytdl cache hit " + videoId);
                return new IptvPlaylist.Resolved(c.videoUrl, c.audioUrl);
            }
        }
        YtDlpRuntime.ensureReady(ctx);
        if (!YtDlpRuntime.isReady()) {
            TvLog.log("ytdl runtime NOT ready, cannot resolve " + videoId);
            return null;
        }
        try {
            java.io.File exe = YtDlpRuntime.pythonExe(ctx);
            java.io.File script = YtDlpRuntime.ytdlpScript(ctx);
            if (!exe.exists() || !script.exists()) {
                TvLog.log("ytdl missing exe/script exe=" + exe.exists()
                        + " script=" + script.exists());
                return null;
            }
            String watch = "https://www.youtube.com/watch?v=" + videoId;
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(exe.getAbsolutePath());
            cmd.add(script.getAbsolutePath());
            cmd.add("--print");
            cmd.add("%(.{urls,chapters})s");
            cmd.add("-S");
            cmd.add("res:720,+proto:m3u8");
            cmd.add("--skip-download");
            cmd.add("--quiet");
            cmd.add("--ignore-errors");
            cmd.add("--no-warnings");
            cmd.add(watch);
            TvLog.log("ytdl resolve " + videoId);
            long t0 = System.currentTimeMillis();
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(YtDlpRuntime.envMap(ctx));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readAll(p.getInputStream());
            boolean done = p.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                TvLog.log("ytdl TIMEOUT " + videoId);
                return null;
            }
            TvLog.log("ytdl exit=" + p.exitValue()
                    + " ms=" + (System.currentTimeMillis() - t0)
                    + " out=" + out.length());
            if (p.exitValue() != 0) return null;
            String urls = extractUrls(out);
            if (urls == null || urls.isEmpty()) {
                TvLog.log("ytdl NO urls in output");
                return null;
            }
            String[] parts = urls.split("\n");
            String video = parts.length > 0 ? parts[0].trim() : "";
            String audio = parts.length > 1 ? parts[1].trim() : null;
            if (video.isEmpty()) return null;
            long expireMs = parseExpire(video);
            TvLog.log("ytdl got video=" + head(video)
                    + " audio=" + (audio == null ? "-" : head(audio))
                    + " expireMs=" + expireMs);
            if (expireMs > 0) {
                synchronized (CACHE) {
                    CACHE.put(videoId, new Cached(video, audio, expireMs));
                }
            }
            return new IptvPlaylist.Resolved(video, audio);
        } catch (Throwable t) {
            TvLog.log("ytdl resolve FAILED " + videoId + ": " + t);
            return null;
        }
    }

    /** Extract the `urls` value from yt-dlp's printed dict
     *  {'urls': '<url1>\n<url2>', 'chapters': [...]}. The newlines
     *  inside the value arrive as the two-character escape \n. */
    private static String extractUrls(String out) {
        if (out == null) return null;
        int i = out.indexOf("'urls': '");
        if (i < 0) i = out.indexOf("\"urls\": \"");
        if (i < 0) return null;
        int start = out.indexOf('\'', i + 8);
        if (start < 0) return null;
        // The value ends at the quote before ", 'chapters'" (or the dict end).
        int end = out.indexOf("', 'chapters'", start + 1);
        if (end < 0) end = out.indexOf("\" , \"chapters\"", start + 1);
        if (end < 0) end = out.indexOf("'}", start + 1);
        if (end < 0) return null;
        String v = out.substring(start + 1, end);
        return v.replace("\\n", "\n").trim();
    }

    /** The manifest URL carries /expire/<epoch-seconds>/ in its path. */
    private static long parseExpire(String url) {
        try {
            int i = url.indexOf("/expire/");
            if (i < 0) return 0;
            int s = i + 8;
            int e = url.indexOf('/', s);
            if (e < 0) return 0;
            return Long.parseLong(url.substring(s, e)) * 1000L;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String head(String url) {
        if (url == null) return "-";
        return url.length() <= 90 ? url : url.substring(0, 90);
    }
}
