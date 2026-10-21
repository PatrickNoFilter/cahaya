package com.patrisius.cahaya.addon;

/** One playable stream from a /stream endpoint (or plugin /streams). */
public class StreamItem {
    public final String url;
    public final String title;
    public final String source; // addon/plugin base URL, for labeling

    public StreamItem(String url, String title, String source) {
        this.url = url;
        this.title = title;
        this.source = source;
    }
}
