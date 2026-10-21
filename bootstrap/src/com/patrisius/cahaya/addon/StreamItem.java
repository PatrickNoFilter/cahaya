package com.patrisius.cahaya.addon;

/** One playable stream from a /stream endpoint (or plugin /streams). */
public class StreamItem {
    public final String url;
    public final String title;
    public final String source; // addon/plugin base URL, for labeling
    public final int fileIdx; // torrent file index (-1 = not a torrent / unknown)
    public int seeders = -1; // parsed from Torrentio-style titles (-1 = unknown)
    public String size = null; // e.g. "6.91 GB" (null = unknown)

    public StreamItem(String url, String title, String source) {
        this(url, title, source, -1);
    }

    public StreamItem(String url, String title, String source, int fileIdx) {
        this.url = url;
        this.title = title;
        this.source = source;
        this.fileIdx = fileIdx;
    }

    public boolean isTorrent() {
        return url != null && url.startsWith("magnet:");
    }
}
