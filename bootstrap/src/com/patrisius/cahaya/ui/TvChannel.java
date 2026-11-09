package com.patrisius.cahaya.ui;

/**
 * v1.33.0: one Live TV channel. A channel is a NAME, not a URL: it
 * carries a list of alternate servers (stream URLs) for the same
 * channel, tried in round-robin order until one plays. Two entries
 * that name the same channel (e.g. CNN Indonesia as a direct
 * iptv-org stream AND as a YouTube live stream) merge into one
 * channel with two servers instead of two list rows.
 *
 * Categories (list order): NEWS first, then NATIONAL, then LOCAL.
 */
public class TvChannel {
    public static final int CAT_NEWS = 0;
    public static final int CAT_NATIONAL = 1;
    public static final int CAT_LOCAL = 2;

    /** One alternate stream for this channel. */
    public static class Server {
        public String url;        // direct .m3u8, or a watch URL when YouTube
        public String label;      // "Direct" or "YouTube"
        public String userAgent;
        public String referer;
        public String youtubeId;  // set when this server is a YouTube live stream

        public Server() {}
        public Server(String url, String label, String userAgent,
                      String referer, String youtubeId) {
            this.url = url;
            this.label = label;
            this.userAgent = userAgent;
            this.referer = referer;
            this.youtubeId = youtubeId;
        }
        public boolean isYouTube() {
            return youtubeId != null && !youtubeId.isEmpty();
        }
    }

    public String name;
    public String tvgId;
    public String logo;
    public String group;
    public int category = CAT_LOCAL;
    public java.util.List<Server> servers = new java.util.ArrayList<>();
    /** Last server that played OK for this channel (start here next time). */
    public int lastGoodServer = 0;

    public TvChannel() {}

    public TvChannel(String name, String tvgId, String logo, String group, int category) {
        this.name = name;
        this.tvgId = tvgId;
        this.logo = logo;
        this.group = group;
        this.category = category;
    }

    public void addServer(Server s) {
        if (s == null) return;
        // Same URL twice (playlist repeats) is not a second server.
        for (Server e : servers) {
            if (e.url != null && e.url.equals(s.url)) return;
        }
        servers.add(s);
    }

    public int serverCount() {
        return servers.size();
    }

    public Server serverAt(int i) {
        if (servers.isEmpty()) return null;
        int idx = ((i % servers.size()) + servers.size()) % servers.size();
        return servers.get(idx);
    }

    /** True when any server is a YouTube live stream. */
    public boolean hasYouTube() {
        for (Server s : servers) if (s.isYouTube()) return true;
        return false;
    }

    /** Display name for the category (channel-list rows + info panel). */
    public String categoryName() {
        switch (category) {
            case CAT_NEWS: return "News";
            case CAT_NATIONAL: return "National";
            default: return "Local";
        }
    }
}
