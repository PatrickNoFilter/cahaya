package com.patrisius.cahaya.ui;

/**
 * v1.32.0: in-memory hand-off for the Live TV channel list. MainActivity
 * fills it when the TV tab is tapped; PlayerActivity reads it in TV
 * mode. (If the process died in between, PlayerActivity falls back
 * to the tv_channels.json cache via IptvPlaylist.loadCache.)
 *
 * v1.35.0: the last working server per channel is persisted in
 * SharedPreferences (keyed by normalized channel name, value = the
 * server URL). A server URL survives playlist refreshes — a server
 * INDEX would not, because a refresh can reorder the servers — so
 * the next zap starts at the server that actually played last time.
 */
public class TvChannelStore {
    private static java.util.List<TvChannel> channels;
    private static int index;
    private static final String PREFS = "tv_last_good";

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

    /** Normalize a channel name the same way IptvPlaylist dedupes. */
    public static String normalizeName(String name) {
        if (name == null) return "";
        String n = name.trim();
        n = n.replaceAll("(?i)\\s*\\(YouTube\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\((1080p|720p|480p|360p|2160p)\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\[(Not 24/7|Geo-blocked)\\]\\s*$", "");
        n = n.replaceAll("(?i)\\s*\\(Indonesia\\)\\s*$", "");
        n = n.replaceAll("(?i)\\s+\\d+\\s*$", "");
        return n.trim().toLowerCase(java.util.Locale.US);
    }

    /** Remember that this server URL played OK for this channel. */
    public static void saveLastGoodServer(android.content.Context ctx,
                                          String channelName, String serverUrl) {
        if (ctx == null || channelName == null || serverUrl == null) return;
        try {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putString(normalizeName(channelName), serverUrl).apply();
        } catch (Throwable ignored) {
        }
    }

    /** The server URL that last played OK for this channel, or null. */
    public static String getLastGoodServerUrl(android.content.Context ctx,
                                              String channelName) {
        if (ctx == null || channelName == null) return null;
        try {
            return ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getString(normalizeName(channelName), null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Index of the remembered server inside this channel, or -1. */
    public static int lastGoodIndex(android.content.Context ctx, TvChannel ch) {
        if (ctx == null || ch == null) return -1;
        String url = getLastGoodServerUrl(ctx, ch.name);
        if (url == null) return -1;
        for (int i = 0; i < ch.servers.size(); i++) {
            if (url.equals(ch.servers.get(i).url)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // v1.35.1: dead-server memory. A server that fails to load or
    // resolve is remembered as dead (by URL, with a timestamp) and
    // skipped for 7 days, so the user stops waiting 8 s per dead
    // server on every zap. A server that plays again is un-marked.
    // ------------------------------------------------------------------
    private static final String DEAD_PREFS = "tv_dead_servers";
    private static final long DEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Mark this server URL dead (now). */
    public static void markDead(android.content.Context ctx, String serverUrl) {
        if (ctx == null || serverUrl == null) return;
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(DEAD_PREFS, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> set =
                    new java.util.HashSet<>(p.getStringSet("dead", new java.util.HashSet<>()));
            // Drop any older entry for the same URL, then add a fresh one.
            java.util.Set<String> next = new java.util.HashSet<>();
            for (String e : set) {
                if (!e.startsWith(serverUrl + "|")) next.add(e);
            }
            next.add(serverUrl + "|" + System.currentTimeMillis());
            p.edit().putStringSet("dead", next).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Forget a dead mark (the server played OK again). */
    public static void unmarkDead(android.content.Context ctx, String serverUrl) {
        if (ctx == null || serverUrl == null) return;
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(DEAD_PREFS, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> set =
                    new java.util.HashSet<>(p.getStringSet("dead", new java.util.HashSet<>()));
            java.util.Set<String> next = new java.util.HashSet<>();
            for (String e : set) {
                if (!e.startsWith(serverUrl + "|")) next.add(e);
            }
            if (next.size() != set.size()) p.edit().putStringSet("dead", next).apply();
        } catch (Throwable ignored) {
        }
    }

    /** True when this server URL is remembered dead and the mark is
     *  still fresh (within 7 days). */
    public static boolean isDead(android.content.Context ctx, String serverUrl) {
        if (ctx == null || serverUrl == null) return false;
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(DEAD_PREFS, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> set =
                    p.getStringSet("dead", new java.util.HashSet<>());
            long now = System.currentTimeMillis();
            for (String e : set) {
                if (!e.startsWith(serverUrl + "|")) continue;
                try {
                    long ts = Long.parseLong(e.substring(serverUrl.length() + 1));
                    return now - ts < DEAD_TTL_MS;
                } catch (Throwable ignored) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
