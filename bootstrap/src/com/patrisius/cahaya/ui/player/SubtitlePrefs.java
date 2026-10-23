package com.patrisius.cahaya.ui.player;

import android.content.Context;
import android.content.SharedPreferences;

import is.xyz.mpv.MPVLib;

/**
 * Persisted subtitle + renderer settings, and the code that pushes them into
 * mpv. Property names/values follow NuvioTV's NuvioMpvSurfaceView.applySubtitleStyle.
 */
public class SubtitlePrefs {
    public static final int[] COLORS = {0xFFFFFFFF, 0xFFD9D9D9, 0xFFFFD700, 0xFF00E5FF, 0xFFFF5C5C, 0xFF00FF88};
    public static final String[] COLOR_NAMES = {"White", "Grey", "Yellow", "Cyan", "Red", "Green"};
    public static final int SIZE_MIN = 50, SIZE_MAX = 200, SIZE_STEP = 10, SIZE_DEFAULT = 100;
    public static final int LIFT_MIN = 0, LIFT_MAX = 30, LIFT_DEFAULT = 6;

    private final SharedPreferences sp;

    public int size, lift, colorIdx;
    public boolean bold, outline, background, stripSdh;
    /** ISO language to auto-select, or "off". */
    public String preferredLang;
    /** true = GPU renderer (can draw subtitles); false = direct MediaCodec surface (cannot). */
    public boolean gpuRenderer;

    public SubtitlePrefs(Context c) {
        sp = c.getSharedPreferences("cahaya_player", Context.MODE_PRIVATE);
        size = sp.getInt("sub_size", SIZE_DEFAULT);
        lift = sp.getInt("sub_lift", LIFT_DEFAULT);
        colorIdx = Math.max(0, Math.min(COLORS.length - 1, sp.getInt("sub_color", 0)));
        bold = sp.getBoolean("sub_bold", false);
        outline = sp.getBoolean("sub_outline", true);
        background = sp.getBoolean("sub_bg", false);
        stripSdh = sp.getBoolean("sub_sdh", false);
        preferredLang = sp.getString("sub_lang", defaultLang());
        gpuRenderer = sp.getBoolean("gpu_renderer", true);
    }

    private static String defaultLang() {
        String l = LangUtil.canonical(java.util.Locale.getDefault().getLanguage());
        return l.isEmpty() ? "eng" : l;
    }

    public void save() {
        sp.edit().putInt("sub_size", size).putInt("sub_lift", lift).putInt("sub_color", colorIdx)
                .putBoolean("sub_bold", bold).putBoolean("sub_outline", outline)
                .putBoolean("sub_bg", background).putBoolean("sub_sdh", stripSdh).putString("sub_lang", preferredLang)
                .putBoolean("gpu_renderer", gpuRenderer).apply();
    }

    public void resetStyle() {
        size = SIZE_DEFAULT; lift = LIFT_DEFAULT; colorIdx = 0;
        bold = false; outline = true; background = false; stripSdh = false;
        save();
    }

    /** "#AARRGGBB" as mpv expects. */
    static String mpvColor(int argb) {
        return String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    /** Push the current style to the running mpv instance. Each call is isolated so one bad property can't block the rest. */
    public void applyToMpv() {
        setD("sub-scale", size / 100.0);
        setB("sub-bold", bold);
        setD("sub-outline-size", outline ? 1.5 : 0.0);
        setS("sub-outline-color", mpvColor(0xFF000000));
        setS("sub-color", mpvColor(COLORS[colorIdx]));
        // sub-pos: 100 = bottom edge, smaller = higher. Lift keeps text clear of TV overscan.
        setD("sub-pos", 100.0 - lift);
        setS("sub-border-style", background ? "background-box" : "outline-and-shadow");
        setS("sub-back-color", mpvColor(background ? 0xB3000000 : 0x00000000));
        setD("sub-shadow-offset", background ? 5.0 : 0.0);
        setB("sub-filter-sdh", stripSdh);
    }

    private static void setD(String k, double v) { try { MPVLib.setPropertyDouble(k, v); } catch (Throwable ignored) {} }
    private static void setB(String k, boolean v) { try { MPVLib.setPropertyBoolean(k, v); } catch (Throwable ignored) {} }
    private static void setS(String k, String v) { try { MPVLib.setPropertyString(k, v); } catch (Throwable ignored) {} }
}
