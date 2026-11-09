package com.patrisius.cahaya.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** v1.29.0: downsampled poster decoding.
 *
 *  Root cause of the 2026-11-08 force close: MovieBox covers are
 *  served at print-poster resolutions (Red Oaks 4050x6000, Carolina
 *  Caroline 4168x6118). The old code decoded them at full size and
 *  BitmapFactory scaled the result 2x for this box's 320dpi display
 *  (image carries no density, so it defaults to 160dpi), producing
 *  an 8100x12000 = 388,800,000-byte bitmap that the canvas refuses
 *  to draw ("Canvas: trying to draw too large bitmap", FATAL
 *  2026-11-08 17:23:16, PID 18528, while home rows were rendering).
 *  This helper decodes bounds first, picks a power-of-two
 *  inSampleSize so the longest side fits maxDim, and decodes with
 *  inScaled=false so no density scaling inflates it again. A 960px
 *  poster is ~5MB; the cards are 110x160dp and the hero backdrop
 *  is blurred at 160px wide, so nothing on screen needs more.
 *  Used by every BitmapFactory.decodeStream site in the app
 *  (MainActivity, DetailsActivity, PauseScreensaver). */
public final class PosterUtil {
    private PosterUtil() {}

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** Decode image bytes so the longest side is at most maxDim px.
     *  Returns null when the bytes are not a decodable image. */
    public static Bitmap decodeSampled(byte[] data, int maxDim) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;
        int sample = 1;
        while (Math.max(w, h) / sample > maxDim) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        // v1.29.0: without this, decodeStream scales the bitmap to
        // the display density (2x on this 320dpi box) — the second
        // half of the 388MB crash.
        opts.inScaled = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    public static Bitmap decodeSampled(InputStream in, int maxDim) throws IOException {
        return decodeSampled(readAll(in), maxDim);
    }
}
