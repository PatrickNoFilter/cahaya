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
    public String genres;   // comma-joined, e.g. "Drama, Action"
    public String rating;   // IMDb rating string, e.g. "8.2"
    public int moviedbId;   // Cinemeta "moviedb_id" (0 = absent); JS scraper key
    public int episodeCount; // videos.size() at parse time; disk hero cache stores this, not the video list
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

    /** v1.26.4: build from a MovieBox detail (fallback metadata for
     *  titles no catalog resolves). The id stays the synthetic
     *  MovieBox id. For series the episode list is generated 1..N
     *  from the detail's episode count, so Details shows the
     *  episodes and Play opens S1E1. */
    public static MetaDetails fromMovieBox(String id, String type, String name,
            String poster, String desc, String year, String genres,
            String rating, int episodes) {
        MetaDetails d = new MetaDetails();
        d.id = id;
        d.type = type;
        d.name = name;
        d.poster = poster;
        d.background = poster;
        d.description = desc != null ? desc : "";
        d.releaseInfo = year != null ? year : "";
        d.genres = genres != null ? genres : "";
        d.rating = rating != null ? rating : "";
        d.episodeCount = episodes;
        if ("series".equals(type) && episodes > 0) {
            for (int n = 1; n <= episodes; n++) {
                VideoInfo vi = new VideoInfo();
                vi.id = id + ":1:" + n;
                vi.title = "Episode " + n;
                vi.season = 1;
                vi.episode = n;
                d.videos.add(vi);
            }
        }
        return d;
    }
}
