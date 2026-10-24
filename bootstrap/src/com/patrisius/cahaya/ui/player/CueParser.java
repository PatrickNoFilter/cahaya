package com.patrisius.cahaya.ui.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SRT, WebVTT and ASS/SSA text into timed cues, so downloaded subtitles
 * can be drawn by the app directly instead of depending on mpv's "sub-text"
 * property (which is unavailable right after seeks and only works for the one
 * track mpv has selected). Also does the lookup and the SDH filter.
 * Pure Java, unit-tested.
 */
public final class CueParser {
    private CueParser() {}

    private static final Pattern SRT_TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    private static final Pattern VTT_TIME = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{1,3})");
    private static final Pattern ASS_TIME = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})[.:](\\d{1,3})");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern VTT_TS_TAG = Pattern.compile("<\\d{1,2}:\\d{2}(?::\\d{2})?\\.\\d{3}>");
    private static final Pattern BRACE_TAG = Pattern.compile("\\{[^}]*\\}");

    /** Auto-detects the format. Returns cues sorted by start time; empty (never null) if nothing parsed. */
    public static List<SubtitleCue> parse(String text) {
        if (text == null) return new ArrayList<>();
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.length() > 0 && t.charAt(0) == '﻿') t = t.substring(1);
        List<SubtitleCue> out;
        String head = t.length() > 800 ? t.substring(0, 800) : t;
        if (head.contains("[Script Info]") || head.contains("[Events]") || head.contains("Dialogue:")) out = parseAss(t);
        else if (head.trim().startsWith("WEBVTT")) out = parseBlocks(t, true);
        else out = parseBlocks(t, false);
        Collections.sort(out, new Comparator<SubtitleCue>() {
            @Override public int compare(SubtitleCue a, SubtitleCue b) { return Long.compare(a.startMs, b.startMs); }
        });
        return out;
    }

    // ---- SRT / VTT -----------------------------------------------------

    private static List<SubtitleCue> parseBlocks(String t, boolean vtt) {
        List<SubtitleCue> out = new ArrayList<>();
        String[] lines = t.split("\n", -1);
        Pattern tp = vtt ? VTT_TIME : SRT_TIME;
        int i = 0;
        while (i < lines.length) {
            Matcher m = tp.matcher(lines[i]);
            if (!m.find()) { i++; continue; }
            long s, e;
            if (vtt) {
                s = ms(m.group(1), m.group(2), m.group(3), m.group(4));
                e = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            } else {
                s = ms(m.group(1), m.group(2), m.group(3), m.group(4));
                e = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            }
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < lines.length && !lines[i].trim().isEmpty()) {
                if (tp.matcher(lines[i]).find()) break;      // missing blank line between cues
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
                i++;
            }
            String clean = clean(sb.toString(), vtt);
            if (!clean.isEmpty() && e > s) out.add(new SubtitleCue(s, e, clean));
        }
        return out;
    }

    private static String clean(String raw, boolean vtt) {
        String s = BRACE_TAG.matcher(raw).replaceAll("");          // {\an8} and friends
        if (vtt) s = VTT_TS_TAG.matcher(s).replaceAll("");
        s = HTML_TAG.matcher(s).replaceAll("");                    // <i> <b> <font ..> <c.x> <v Name>
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }

    private static long ms(String h, String m, String s, String frac) {
        long hh = h == null || h.isEmpty() ? 0 : Long.parseLong(h);
        // "5" -> 500 ms, "50" -> 500 ms, "500" -> 500 ms
        long f = Long.parseLong((frac + "00").substring(0, 3));
        return ((hh * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1000 + f;
    }

    // ---- ASS / SSA -----------------------------------------------------

    private static List<SubtitleCue> parseAss(String t) {
        List<SubtitleCue> out = new ArrayList<>();
        int iStart = 1, iEnd = 2, iText = 9, nFields = 10;        // ASS default column order
        boolean inEvents = false;
        for (String raw : t.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) { inEvents = line.equalsIgnoreCase("[Events]"); continue; }
            if (!inEvents) continue;
            if (line.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] f = line.substring(7).split(",");
                nFields = f.length;
                for (int k = 0; k < f.length; k++) {
                    String name = f[k].trim().toLowerCase(java.util.Locale.ROOT);
                    if (name.equals("start")) iStart = k;
                    else if (name.equals("end")) iEnd = k;
                    else if (name.equals("text")) iText = k;
                }
                continue;
            }
            if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)) continue;
            String[] p = line.substring(9).trim().split(",", nFields);      // Text keeps its own commas
            if (p.length <= Math.max(iText, Math.max(iStart, iEnd))) continue;
            long s = assTime(p[iStart]), e = assTime(p[iEnd]);
            if (s < 0 || e <= s) continue;
            String body = p[iText];
            if (body.contains("{\\p1") || body.contains("{\\p2")) continue;  // vector drawing, not text
            body = BRACE_TAG.matcher(body).replaceAll("")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
            String clean = clean(body, false);
            if (!clean.isEmpty()) out.add(new SubtitleCue(s, e, clean));
        }
        return out;
    }

    private static long assTime(String s) {
        Matcher m = ASS_TIME.matcher(s.trim());
        if (!m.matches()) return -1;
        long hh = Long.parseLong(m.group(1)), mm = Long.parseLong(m.group(2)), ss = Long.parseLong(m.group(3));
        String frac = m.group(4);                                    // centiseconds normally
        long f = frac.length() == 3 ? Long.parseLong(frac) : Long.parseLong(frac) * (frac.length() == 1 ? 100 : 10);
        return ((hh * 60 + mm) * 60 + ss) * 1000 + f;
    }

    // ---- lookup --------------------------------------------------------

    /** Text on screen at {@code ms}, joining overlapping cues (e.g. two speakers). "" if none. */
    public static String textAt(List<SubtitleCue> cues, long ms, boolean stripSdh) {
        if (cues == null || cues.isEmpty() || ms < 0) return "";
        int lo = 0, hi = cues.size() - 1, idx = -1;             // last cue with start <= ms
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs <= ms) { idx = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (idx < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = idx; k >= 0 && k > idx - 8; k--) {         // overlaps are rare; look back a few cues
            SubtitleCue c = cues.get(k);
            if (ms >= c.startMs && ms < c.endMs) {
                String tx = stripSdh ? stripSdh(c.text) : c.text;
                if (tx.isEmpty()) continue;
                if (sb.length() > 0) sb.insert(0, '\n');
                sb.insert(0, tx);
            }
        }
        return sb.toString();
    }

    private static final Pattern SDH_BRACKET = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)");
    private static final Pattern SDH_SPEAKER = Pattern.compile("^[A-Z][A-Z0-9 .'-]{1,20}:\\s*");

    /** Removes [sound effects], (descriptions), speaker labels ("JOHN:") and ♪ music lines. */
    public static String stripSdh(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.contains("♪") || line.contains("♫")) continue;
            String l = SDH_BRACKET.matcher(line).replaceAll("");
            l = SDH_SPEAKER.matcher(l.trim()).replaceFirst("").trim();
            if (l.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }
}
