package com.patrisius.cahaya.addon;

import java.util.ArrayList;
import java.util.List;

/** Full metadata from a /meta endpoint. */
public class MetaDetails {
    public String id;
    public String type;
    public String name;
    public String poster;
    public String background;
    public String description;
    public String releaseInfo;
    public final List<VideoInfo> videos = new ArrayList<>();

    public static class VideoInfo {
        public String id;       // e.g. tt123:1:2 for episodes, meta id for movies
        public String title;
        public String thumbnail;
        public int season;
        public int episode;
    }

    public boolean isSeries() {
        return !videos.isEmpty();
    }
}
