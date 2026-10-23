package com.patrisius.cahaya.ui.player;

public final class TimeFmt {
    private TimeFmt() {}

    /** 83 -> "1:23", 5025 -> "1:23:45". The old formatter printed "83:45" for long films. */
    public static String clock(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) seconds = 0;
        long s = (long) seconds;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
        return m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    /** Subtitle delay label: 0 -> "0.0 s", 1500 -> "+1.5 s", -300 -> "-0.3 s". */
    public static String delay(int ms) {
        String sign = ms > 0 ? "+" : (ms < 0 ? "-" : "");
        int a = Math.abs(ms);
        return sign + (a / 1000) + "." + ((a % 1000) / 100) + " s";
    }
}
