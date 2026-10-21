package com.patrisius.cahaya.addon;

/** One subtitle file from a /subtitles endpoint (or plugin /subtitles). */
public class SubtitleItem {
    public final String url;
    public final String lang;
    public final String label;

    public SubtitleItem(String url, String lang, String label) {
        this.url = url;
        this.lang = lang;
        this.label = label;
    }
}
