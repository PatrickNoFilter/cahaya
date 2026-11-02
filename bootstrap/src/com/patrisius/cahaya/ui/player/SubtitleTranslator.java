package com.patrisius.cahaya.ui.player;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Machine-translates subtitle cues into the user's preferred language
 * (Indonesian for him) when no subtitle in that language exists at all.
 *
 * Engine: Google's free translate endpoint (no key). Cue texts go in
 * batches of 25 as repeated q= params over POST; the translated SRT is
 * cached on disk for 30 days keyed by source subtitle + target language,
 * so a rewatch never re-translates. Blocking: call from a background
 * thread. Throws on any failure so the caller can fall back to the
 * untranslated subtitle.
 */
public final class SubtitleTranslator {
    private SubtitleTranslator() {}

    private static final int BATCH = 25;
    private static final long CACHE_MS = 30L * 24 * 3600 * 1000;

    public static List<SubtitleCue> translate(Context ctx, String sourceKey,
            List<SubtitleCue> cues, String targetLang) throws IOException {
        if (cues == null || cues.isEmpty()) throw new IOException("no cues");
        String tl = toTranslateCode(targetLang);
        File cached = cacheFile(ctx, sourceKey, tl);
        if (cached.isFile() && cached.length() > 0
                && System.currentTimeMillis() - cached.lastModified() < CACHE_MS) {
            List<SubtitleCue> hit = CueParser.parse(readUtf8(cached));
            if (!hit.isEmpty()) return hit;
        }
        List<String> out = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i += BATCH) {
            int end = Math.min(i + BATCH, cues.size());
            List<String> texts = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) texts.add(cues.get(j).text);
            out.addAll(translateBatch(texts, tl));
            if (end < cues.size()) {
                try { Thread.sleep(250); }
                catch (InterruptedException e) { throw new IOException("interrupted"); }
            }
        }
        List<SubtitleCue> res = new ArrayList<>(cues.size());
        StringBuilder srt = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue c = cues.get(i);
            String t = i < out.size() ? out.get(i) : c.text;
            res.add(new SubtitleCue(c.startMs, c.endMs, t));
            srt.append(i + 1).append('\n')
               .append(fmt(c.startMs)).append(" --> ").append(fmt(c.endMs)).append('\n')
               .append(t).append("\n\n");
        }
        writeUtf8(cached, srt.toString());
        return res;
    }

    /** ISO 639-2/T ("ind") -> the 2-letter code the endpoint wants ("id"). */
    private static String toTranslateCode(String lang) {
        String c = LangUtil.canonical(lang);
        if (c.length() == 2) return c;
        try {
            String two = new Locale(c).getLanguage();
            if (two != null && two.length() == 2) return two;
        } catch (Exception ignored) {}
        return "id";
    }

    private static List<String> translateBatch(List<String> texts, String tl) throws IOException {
        // Join cues with \n (multi-line cues keep their own newlines) into ONE
        // q param: the endpoint ignores repeated q params but returns one
        // segment per input line, so concatenation + split re-aligns exactly.
        StringBuilder in = new StringBuilder();
        int[] linesPer = new int[texts.size()];
        int wantLines = 0;
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) in.append('\n');
            String t = texts.get(i);
            in.append(t);
            int n = 1;
            for (int k = 0; k < t.length(); k++) if (t.charAt(k) == '\n') n++;
            linesPer[i] = n;
            wantLines += n;
        }
        String all = translateOne(in.toString(), tl);
        String[] lines = all.split("\n", -1);
        // Tolerate a trailing "" when the translation ends with \n.
        int have = lines.length;
        if (have > 0 && lines[have - 1].isEmpty()) have--;
        if (have == wantLines) {
            List<String> out = new ArrayList<>(texts.size());
            int p = 0;
            for (int n : linesPer) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < n; k++) {
                    if (k > 0) sb.append('\n');
                    sb.append(lines[p++]);
                }
                out.add(sb.toString());
            }
            return out;
        }
        // Line counts drifted (rare): fall back to one request per cue.
        List<String> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(translateOne(t, tl));
            try { Thread.sleep(200); } catch (InterruptedException e) { throw new IOException("interrupted"); }
        }
        return out;
    }

    /** Single q param -> concatenated translated text (all per-line segments joined). */
    private static String translateOne(String q, String tl) throws IOException {
        byte[] post = ("q=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                + URLEncoder.encode(tl, "UTF-8") + "&dt=t").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setRequestProperty("User-Agent", "Cahaya/1.16 (Android TV)");
        c.getOutputStream().write(post);
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new IOException("translate HTTP " + code); }
        byte[] raw = readAll(c);
        c.disconnect();
        return parseResponse(new String(raw, StandardCharsets.UTF_8));
    }

    /**
     * Response: [[["trans","orig",...],["trans2","orig2",...],...],...] —
     * element [0] holds one segment per input line. Concatenate every
     * segment's translated text; the caller splits on \n and verifies.
     */
    private static String parseResponse(String json) throws IOException {
        try {
            org.json.JSONArray outer = new org.json.JSONArray(json);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < outer.length(); i++) {
                Object e = outer.opt(i);
                if (!(e instanceof org.json.JSONArray)) continue;
                org.json.JSONArray arr = (org.json.JSONArray) e;
                if (arr.length() == 0) continue;
                Object first = arr.opt(0);
                if (first instanceof org.json.JSONArray) {
                    // A group of segments (one per q): flatten.
                    for (int j = 0; j < arr.length(); j++) {
                        Object s = arr.opt(j);
                        if (s instanceof org.json.JSONArray)
                            sb.append(((org.json.JSONArray) s).optString(0));
                    }
                    break;
                } else if (first instanceof String) {
                    // The segment array itself.
                    for (int j = 0; j < arr.length(); j++) {
                        Object s = arr.opt(j);
                        if (s instanceof org.json.JSONArray)
                            sb.append(((org.json.JSONArray) s).optString(0));
                    }
                    break;
                }
            }
            return sb.toString();
        } catch (org.json.JSONException e) {
            throw new IOException("translate parse: " + e.getMessage());
        }
    }

    private static File cacheFile(Context ctx, String sourceKey, String tl) throws IOException {
        File dir = new File(ctx.getCacheDir(), "subs-tr");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
        File[] all = dir.listFiles();
        if (all != null) {
            long now = System.currentTimeMillis();
            for (File f : all) if (now - f.lastModified() > CACHE_MS) f.delete();
        }
        return new File(dir, md5(sourceKey + "|" + tl) + ".srt");
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    private static String fmt(long ms) {
        long h = ms / 3600000, m = ms / 60000 % 60, s = ms / 1000 % 60, x = ms % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, x);
    }

    private static byte[] readAll(HttpURLConnection c) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        try (java.io.InputStream in = c.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static String readUtf8(File f) throws IOException {
        byte[] b = new byte[(int) Math.min(f.length(), 8 * 1024 * 1024)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static void writeUtf8(File f, String s) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }
}
