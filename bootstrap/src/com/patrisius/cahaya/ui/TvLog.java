package com.patrisius.cahaya.ui;

/**
 * v1.32.1: Live TV diagnostics. Appends timestamped lines to
 * tv.log in getExternalFilesDir (next to mpv.log and
 * js-scraper.log) and mirrors them to logcat under "CahayaTV".
 * Readable on the box via root adb:
 *   su -c 'cat /sdcard/Android/data/com.patrisius.cahaya/files/tv.log'
 * The file is capped (~200 KB, oldest lines dropped) so it cannot
 * grow without bound.
 */
public class TvLog {
    private static final String TAG = "CahayaTV";
    private static final String NAME = "tv.log";
    private static final long MAX_BYTES = 200 * 1024;
    private static android.content.Context appCtx;
    private static final Object LOCK = new Object();

    public static void init(android.content.Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    public static void log(String msg) {
        android.util.Log.i(TAG, msg);
        if (appCtx == null) return;
        synchronized (LOCK) {
            try {
                java.io.File dir = appCtx.getExternalFilesDir(null);
                if (dir == null) return;
                java.io.File f = new java.io.File(dir, NAME);
                if (f.exists() && f.length() > MAX_BYTES) {
                    // Drop the oldest half to stay bounded.
                    byte[] all = readAll(f);
                    int keep = (int) (MAX_BYTES / 2);
                    int from = Math.max(0, all.length - keep);
                    // Start at the next newline so we keep whole lines.
                    while (from < all.length && all[from] != '\n') from++;
                    java.io.FileOutputStream trim =
                            new java.io.FileOutputStream(f, false);
                    trim.write(all, from, all.length - from);
                    trim.close();
                }
                String line = new java.text.SimpleDateFormat(
                        "MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + msg + "\n";
                java.io.FileOutputStream fos =
                        new java.io.FileOutputStream(f, true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] readAll(java.io.File f) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        fis.close();
        return bos.toByteArray();
    }
}
