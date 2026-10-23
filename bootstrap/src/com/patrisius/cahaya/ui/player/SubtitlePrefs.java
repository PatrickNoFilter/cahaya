package com.patrisius.cahaya.ui.player;

import android.content.Context;
import android.content.SharedPreferences;

import is.xyz.mpv.MPVLib;

/**
 * Persisted subtitle settings. The video output is always mediacodec_embed
 * (vo=gpu faults on the target Mali boxes), so subtitles are drawn by
 * {@link SubtitleOverlay}; every visual setting below is applied there.
 * Only the SDH filter (a text filter inside mpv) is pushed into mpv.
 */
public class SubtitlePrefs {
    public static final int[] COLORS = {0xFFFFFFFF, 0xFFD9D9D9, 0xFFFFD700, 0xFF00E5FF, 0xFFFF5C5C, 0xFF00FF88};
    public static final String[] COLOR_NAMES = {"White", "Grey", "Yellow", "Cyan", "Red", "Green"};
    public static final String[] EDGE_NAMES = {"Outline", "Shadow", "None"};
    public static final int SIZE_MIN = 50, SIZE_MAX = 250, SIZE_STEP = 10, SIZE_DEFAULT = 100;
    public static final int LIFT_MIN = 0, LIFT_MAX = 30, LIFT_DEFAULT = 4;
    public static final int OPACITY_MIN = 10, OPACITY_MAX = 100, OPACITY_STEP = 10, OPACITY_DEFAULT = 70;

    private final SharedPreferences sp;

    public int size, lift, colorIdx, edge, bgOpacity;
    public boolean bold, background, stripSdh;
    /** ISO language to auto-select, or "off". */
    public String preferredLang;

    public SubtitlePrefs(Context c) {
        sp = c.getSharedPreferences("cahaya_player", Context.MODE_PRIVATE);
        size = clamp(sp.getInt("sub_size", SIZE_DEFAULT), SIZE_MIN, SIZE_MAX);
        lift = clamp(sp.getInt("sub_lift", LIFT_DEFAULT), LIFT_MIN, LIFT_MAX);
        colorIdx = clamp(sp.getInt("sub_color", 0), 0, COLORS.length - 1);
        // older builds stored a boolean "sub_outline"
        edge = clamp(sp.getInt("sub_edge", sp.getBoolean("sub_outline", true) ? 0 : 2), 0, EDGE_NAMES.length - 1);
        bgOpacity = clamp(sp.getInt("sub_bg_opacity", OPACITY_DEFAULT), OPACITY_MIN, OPACITY_MAX);
        bold = sp.getBoolean("sub_bold", false);
        background = sp.getBoolean("sub_bg", false);
        stripSdh = sp.getBoolean("sub_sdh", false);
        preferredLang = sp.getString("sub_lang", defaultLang());
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String defaultLang() {
        String l = LangUtil.canonical(java.util.Locale.getDefault().getLanguage());
        return l.isEmpty() ? "eng" : l;
    }

    public void save() {
        sp.edit().putInt("sub_size", size).putInt("sub_lift", lift).putInt("sub_color", colorIdx)
                .putInt("sub_edge", edge).putInt("sub_bg_opacity", bgOpacity)
                .putBoolean("sub_bold", bold).putBoolean("sub_bg", background)
                .putBoolean("sub_sdh", stripSdh).putString("sub_lang", preferredLang).apply();
    }

    public void resetStyle() {
        size = SIZE_DEFAULT; lift = LIFT_DEFAULT; colorIdx = 0; edge = 0;
        bgOpacity = OPACITY_DEFAULT; bold = false; background = false; stripSdh = false;
        save();
    }

    /** Push the mpv-side setting (SDH filter). Visual style lives in SubtitleOverlay. */
    public void applyToMpv() {
        try { MPVLib.setPropertyBoolean("sub-filter-sdh", stripSdh); } catch (Throwable ignored) {}
    }
}
