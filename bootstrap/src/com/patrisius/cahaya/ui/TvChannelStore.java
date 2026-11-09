package com.patrisius.cahaya.ui;

/**
 * v1.32.0: in-memory hand-off for the Live TV channel list. MainActivity
 * fills it when the TV tab is tapped; PlayerActivity reads it in TV
 * mode. (If the process died in between, PlayerActivity falls back
 * to the tv_channels.json cache via IptvPlaylist.loadCache.)
 */
public class TvChannelStore {
    private static java.util.List<TvChannel> channels;
    private static int index;

    public static void set(java.util.List<TvChannel> c, int i) {
        channels = c;
        index = i;
    }

    public static java.util.List<TvChannel> get() {
        return channels;
    }

    public static int getIndex() {
        return index;
    }

    public static void setIndex(int i) {
        index = i;
    }
}
