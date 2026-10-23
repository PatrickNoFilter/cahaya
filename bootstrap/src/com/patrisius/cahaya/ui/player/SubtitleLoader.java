package com.patrisius.cahaya.ui.player;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Downloads an addon subtitle with Android's own HTTP/TLS stack, normalises it
 * to UTF-8, and caches it. mpv then loads a local file: this avoids mpv's TLS
 * trust-store problem on Android and fixes legacy encodings (SubtitleDecoder).
 * Blocking: call from a background thread.
 */
public final class SubtitleLoader {
    private SubtitleLoader() {}

    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    public static File fetch(Context ctx, String url, String lang) throws IOException {
        File dir = new File(ctx.getCacheDir(), "subs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
        prune(dir);
        String key = Integer.toHexString(url.hashCode());
        File[] hit = dir.listFiles((d, n) -> n.startsWith(key + "."));
        if (hit != null && hit.length > 0 && hit[0].length() > 0) return hit[0];

        byte[] raw = download(url);
        String text = SubtitleDecoder.decode(raw, lang);
        if (text.trim().isEmpty()) throw new IOException("empty subtitle");
        File out = new File(dir, key + SubtitleDecoder.guessExtension(text));
        try (FileOutputStream f = new FileOutputStream(out)) {
            f.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static byte[] download(String start) throws IOException {
        String u = start;
        for (int hop = 0; hop < 5; hop++) {
            u = upgradeToHttps(u);
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(false);       // follow manually so http<->https hops work
            c.setRequestProperty("User-Agent", "Cahaya/0.9 (Android TV)");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without Location");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
            try (InputStream raw = c.getInputStream()) {
                InputStream in = raw;
                String enc = c.getContentEncoding();
                boolean gz = enc != null && enc.equalsIgnoreCase("gzip");
                byte[] all = readCapped(in);
                // Some subtitle hosts serve .gz files without a Content-Encoding header.
                if (gz || (all.length > 2 && (all[0] & 0xFF) == 0x1F && (all[1] & 0xFF) == 0x8B)) {
                    all = readCapped(new GZIPInputStream(new java.io.ByteArrayInputStream(all)));
                }
                return all;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /** The app's network security config only allows cleartext to localhost, so upgrade other http:// URLs. */
    static String upgradeToHttps(String url) {
        if (!url.regionMatches(true, 0, "http://", 0, 7)) return url;
        String rest = url.substring(7);
        String host = rest.split("[/:?#]", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.equals("127.0.0.1")) return url;
        return "https://" + rest;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > MAX_BYTES) throw new IOException("subtitle too large");
        }
        return bo.toByteArray();
    }

    private static void prune(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return;
        long now = System.currentTimeMillis();
        for (File f : all) if (now - f.lastModified() > MAX_AGE_MS) f.delete();
    }
}
