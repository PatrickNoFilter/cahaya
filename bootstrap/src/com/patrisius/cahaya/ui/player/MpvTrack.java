package com.patrisius.cahaya.ui.player;

import java.util.ArrayList;
import java.util.List;

import is.xyz.mpv.MPVLib;

/** One audio or subtitle track as reported by mpv's track-list. */
public class MpvTrack {
    public int id;
    public String type;         // "audio" | "sub"
    public String lang;
    public String title;
    public String codec;
    public boolean external, forced, isDefault, selected;
    public int channels;

    /** Bitmap subtitle formats cannot be turned into text, so the overlay cannot show them. */
    public boolean isImage() {
        if (codec == null) return false;
        String c = codec.toLowerCase(java.util.Locale.ROOT);
        return c.equals("hdmv_pgs_subtitle") || c.equals("dvd_subtitle")
                || c.equals("dvb_subtitle") || c.equals("xsub") || c.equals("dvb_teletext");
    }

    /** Main line of a picker row: "English", plus the title when it adds information. */
    public String label() {
        // "und"/"unk"/null/empty are all "no language": fall through
        // to the title, then to the caller's positional fallback.
        String name = (lang == null || LangUtil.canonical(lang).isEmpty()) ? null : LangUtil.display(lang);
        if (name == null) return (title != null && !title.isEmpty()) ? title : ("Track " + id);
        if (title != null && !title.isEmpty() && !title.equalsIgnoreCase(name)
                && !title.equalsIgnoreCase(lang)) return name + " · " + title;
        return name;
    }

    /** Secondary line: codec and flags. */
    public String detail() {
        List<String> p = new ArrayList<>();
        if (codec != null && !codec.isEmpty()) p.add(prettyCodec(codec));
        if (channels > 0) p.add(channels == 2 ? "Stereo" : channels == 6 ? "5.1" : channels == 8 ? "7.1" : channels + " ch");
        if (isImage()) p.add("Image, can't display");
        if (forced) p.add("Forced");
        if (isDefault) p.add("Default");
        if (external) p.add("External");
        StringBuilder sb = new StringBuilder();
        for (String s : p) { if (sb.length() > 0) sb.append(" · "); sb.append(s); }
        return sb.toString();
    }

    private static String prettyCodec(String c) {
        c = c.toLowerCase(java.util.Locale.ROOT);
        switch (c) {
            case "subrip": return "SRT";
            case "ass": case "ssa": return "ASS";
            case "webvtt": return "WebVTT";
            case "hdmv_pgs_subtitle": return "PGS";
            case "dvd_subtitle": return "VobSub";
            case "eac3": return "E-AC3";
            case "truehd": return "TrueHD";
            default: return c.toUpperCase(java.util.Locale.ROOT);
        }
    }

    // The numeric/boolean JNI getters in this libplayer build return
    // garbage (track ids like 97/113/121, track-list/count of 121 for a
    // 36-track file, random booleans). Only the STRING getter is reliable,
    // so every field is read as text and parsed here.
    private static int intProp(String name, int def) {
        try {
            String v = MPVLib.getPropertyString(name);
            if (v == null) return def;
            return Integer.parseInt(v.trim());
        } catch (Throwable t) { return def; }
    }

    private static boolean boolProp(String name) {
        try {
            String v = MPVLib.getPropertyString(name);
            return "yes".equals(v) || "true".equals(v) || "1".equals(v);
        } catch (Throwable t) { return false; }
    }

    /** Read all tracks of one type ("audio" or "sub") from the running mpv. Never throws. */
    public static List<MpvTrack> read(String wantType) {
        List<MpvTrack> out = new ArrayList<>();
        try {
            int n = intProp("track-list/count", 0);
            String sel = MPVLib.getPropertyString(wantType.equals("audio") ? "aid" : "sid");
            for (int i = 0; i < n; i++) {
                String base = "track-list/" + i + "/";
                String type = MPVLib.getPropertyString(base + "type");
                // v1.6.4 amendment: continue, not break — a transient
                // null mid-list must not truncate the tracks.
                if (type == null) continue;
                if (!wantType.equals(type)) continue;
                int id = intProp(base + "id", -1);
                if (id < 0) continue;
                MpvTrack t = new MpvTrack();
                t.type = wantType;
                t.id = id;
                t.lang = MPVLib.getPropertyString(base + "lang");
                t.title = MPVLib.getPropertyString(base + "title");
                t.codec = MPVLib.getPropertyString(base + "codec");
                t.external = boolProp(base + "external");
                t.forced = boolProp(base + "forced");
                t.isDefault = boolProp(base + "default");
                if (wantType.equals("audio")) t.channels = intProp(base + "demux-channel-count", 0);
                t.selected = sel != null && sel.equals(String.valueOf(t.id));
                out.add(t);
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
