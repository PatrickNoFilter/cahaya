package com.patrisius.cahaya.ui.player;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.19.0: Java wrapper for the ffmpeg JNI bitmap-subtitle decoder
 * (libcahayasubs.so). Decodes embedded image subtitles (PGS, VobSub,
 * DVB, XSUB) that neither MediaExtractor nor mpv can render.
 */
public class FfmpegSubs {
    static {
        System.loadLibrary("cahayasubs");
    }

    public static class Track {
        public int index;      // ffmpeg stream index
        public String codec;   // e.g. hdmv_pgs_subtitle
        public String lang;    // e.g. eng
        public String title;
    }

    private long handle = 0;

    public boolean open(String url, String headers) {
        close();
        handle = nativeOpen(url, headers);
        return handle != 0;
    }

    public List<Track> listSubs() {
        List<Track> out = new ArrayList<>();
        if (handle == 0) return out;
        String json = nativeListSubs(handle);
        if (json == null) return out;
        // Minimal JSON parse for our own fixed format.
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Track t = new Track();
                t.index = o.optInt("index", -1);
                t.codec = o.optString("codec", "");
                t.lang = o.optString("lang", "");
                t.title = o.optString("title", "");
                if (t.index >= 0) out.add(t);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public boolean select(int streamIndex) {
        return handle != 0 && nativeSelect(handle, streamIndex);
    }

    /** Next decoded event, or null at EOF / error. Blocks on I/O. */
    public SubtitleEvent next() {
        if (handle == 0) return null;
        try {
            return (SubtitleEvent) nativeNext(handle);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean seek(long ms) {
        return handle != 0 && nativeSeek(handle, ms);
    }

    public void close() {
        if (handle != 0) {
            try {
                nativeClose(handle);
            } catch (Throwable ignored) {
            }
            handle = 0;
        }
    }

    private native long nativeOpen(String url, String headers);

    private native String nativeListSubs(long handle);

    private native boolean nativeSelect(long handle, int streamIndex);

    private native Object nativeNext(long handle);

    private native boolean nativeSeek(long handle, long ms);

    private native void nativeClose(long handle);
}
