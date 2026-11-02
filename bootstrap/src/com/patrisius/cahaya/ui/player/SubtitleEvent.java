package com.patrisius.cahaya.ui.player;

/**
 * v1.19.0: one decoded embedded subtitle, from the ffmpeg JNI bridge.
 * Either a bitmap (PGS / VobSub / DVB / XSUB) or text.
 */
public class SubtitleEvent {
    public final long startMs;
    public long endMs;   // mutable: indefinite ends (PGS UINT32_MAX) are fixed up when the next event arrives
    public final boolean isBitmap;
    // Bitmap fields (video-frame coordinates):
    public final int x, y, w, h;
    public final int[] argb;   // w*h ARGB pixels, null unless isBitmap
    /** Lazily-created Android Bitmap, managed by BitmapSubsDecoder. */
    public android.graphics.Bitmap bitmap;
    // Text field:
    public final String text;  // null unless !isBitmap

    /** Bitmap constructor (called from JNI). */
    public SubtitleEvent(long startMs, long endMs, int x, int y, int w, int h, int[] argb) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.isBitmap = true;
        this.x = x; this.y = y; this.w = w; this.h = h;
        this.argb = argb;
        this.text = null;
    }

    /** Text constructor (called from JNI). */
    public SubtitleEvent(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.isBitmap = false;
        this.x = this.y = this.w = this.h = 0;
        this.argb = null;
        this.text = text;
    }
}
