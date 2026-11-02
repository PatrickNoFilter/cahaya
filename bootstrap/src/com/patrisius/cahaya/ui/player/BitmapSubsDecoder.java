package com.patrisius.cahaya.ui.player;

import android.graphics.Bitmap;
import android.os.SystemClock;

/**
 * v1.19.0: on-demand decoder for embedded BITMAP subtitles (PGS/VobSub/DVB/XSUB)
 * via the ffmpeg JNI bridge. Decodes forward from the playback position;
 * seeks the decoder when the user seeks.
 *
 * All calls must come from a single background thread (the overlay poll).
 */
public class BitmapSubsDecoder {
    private final FfmpegSubs subs = new FfmpegSubs();
    private SubtitleEvent current;   // event covering (or just before) the last query
    private long decodedUntilMs = 0;
    private boolean eof = false;
    private boolean opened = false;

    /** Open the URL and select the ffmpeg subtitle stream matching codec+lang. */
    public boolean open(String url, String headers, String wantCodec, String wantLang) {
        close();
        if (!subs.open(url, headers)) return false;
        java.util.List<FfmpegSubs.Track> tracks = subs.listSubs();
        // Match by codec, then language; pick the Nth among same-codec tracks
        // by order (mpv and ffmpeg enumerate in container order).
        int seen = 0;
        int pick = -1;
        for (FfmpegSubs.Track t : tracks) {
            if (!t.codec.equalsIgnoreCase(wantCodec)) continue;
            if (wantLang != null && !wantLang.isEmpty()
                    && t.lang != null && !t.lang.isEmpty()
                    && !t.lang.equalsIgnoreCase(wantLang)
                    && !LangUtil.matches(t.lang, wantLang)) {
                continue;
            }
            if (seen == 0) pick = t.index;
            seen++;
            if (pick >= 0) break;
        }
        if (pick < 0) {
            // Fallback: first bitmap-capable stream.
            for (FfmpegSubs.Track t : tracks) {
                String c = t.codec.toLowerCase();
                if (c.contains("pgs") || c.contains("dvd_sub") || c.contains("dvb")
                        || c.contains("xsub")) {
                    pick = t.index;
                    break;
                }
            }
        }
        if (pick < 0 || !subs.select(pick)) {
            subs.close();
            return false;
        }
        opened = true;
        return true;
    }

    /**
     * Bitmap to show at timeMs, or null. Blocks briefly on I/O while
     * decoding forward; seeks the decoder on large jumps.
     */
    public Bitmap getAt(long timeMs) {
        if (!opened) return null;
        // Large jump (seek) -> reposition the decoder.
        long curStart = current != null ? current.startMs : decodedUntilMs;
        if (timeMs < curStart - 3000 || timeMs > decodedUntilMs + 120000) {
            long to = Math.max(0, timeMs - 2000);
            try {
                subs.seek(to);
            } catch (Throwable ignored) {
            }
            decodedUntilMs = to;
            recycle(current);
            current = null;
            eof = false;
        }

        // Current event covers timeMs?
        if (current != null && timeMs >= current.startMs && timeMs < current.endMs) {
            return bitmapOf(current);
        }
        // In a gap before the already-decoded next event: wait for it, don't decode past it.
        if (current != null && current.startMs > timeMs) {
            return null;
        }

        long deadline = SystemClock.uptimeMillis() + 250;
        while (SystemClock.uptimeMillis() < deadline) {
            if (eof) return null;
            SubtitleEvent ev;
            try {
                ev = subs.next();
            } catch (Throwable t) {
                return null;
            }
            if (ev == null) {
                eof = true;
                continue;
            }
            decodedUntilMs = ev.endMs;
            // Fix up an indefinite previous end (PGS uses UINT32_MAX).
            if (current != null && current.endMs > 9e11) {
                current.endMs = ev.startMs;
            }
            if (ev.startMs <= timeMs && timeMs < ev.endMs) {
                recycle(current);
                current = ev;
                return bitmapOf(current);
            } else if (ev.startMs > timeMs) {
                // Gap ahead: no subtitle at timeMs. Keep ev as current
                // (it's the next upcoming) but don't show it yet.
                recycle(current);
                current = ev;
                return null;
            } else {
                // Entirely before timeMs: discard, keep decoding.
                recycle(ev);
            }
        }
        return null;
    }

    private Bitmap bitmapOf(SubtitleEvent ev) {
        if (!ev.isBitmap || ev.argb == null) return null;
        if (ev.bitmap != null && !ev.bitmap.isRecycled()) return ev.bitmap;
        try {
            ev.bitmap = Bitmap.createBitmap(ev.argb, ev.w, ev.h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
        return ev.bitmap;
    }

    private void recycle(SubtitleEvent ev) {
        if (ev != null && ev.bitmap != null) {
            try {
                ev.bitmap.recycle();
            } catch (Throwable ignored) {
            }
            ev.bitmap = null;
        }
    }

    public void close() {
        recycle(current);
        current = null;
        try {
            subs.close();
        } catch (Throwable ignored) {
        }
        opened = false;
        eof = false;
        decodedUntilMs = 0;
    }

    /** The event backing the last bitmap returned (poll thread only). */
    SubtitleEvent currentEvent() {
        return current;
    }
}
