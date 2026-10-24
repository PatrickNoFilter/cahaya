package com.patrisius.cahaya.ui.player;

/** One timed subtitle line. Times are milliseconds from the start of the video. */
public final class SubtitleCue {
    public final long startMs, endMs;
    public final String text;

    public SubtitleCue(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }
}
