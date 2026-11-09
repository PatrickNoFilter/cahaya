package com.patrisius.cahaya.addon;

/** One entry of an addon catalog response. */
public class CatalogItem {
    public final String id;
    public final String type;
    public final String name;
    public final String poster;
    // v1.26.4: MovieBox detail fallback — synopsis/genres/rating/
    // episode count from the MovieBox app-API detail, attached by
    // getHomeRows for titles no catalog resolves. Null/0 when absent.
    public final String mbDesc;
    public final String mbGenres;
    public final String mbRating;
    public final int mbYear;
    public final int mbEpisodes;

    public CatalogItem(String id, String type, String name, String poster) {
        this(id, type, name, poster, null, null, null, 0, 0);
    }

    public CatalogItem(String id, String type, String name, String poster,
            String mbDesc, String mbGenres, String mbRating,
            int mbYear, int mbEpisodes) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.poster = poster;
        this.mbDesc = mbDesc;
        this.mbGenres = mbGenres;
        this.mbRating = mbRating;
        this.mbYear = mbYear;
        this.mbEpisodes = mbEpisodes;
    }

    /** True when the item carries MovieBox detail metadata (the
     *  fallback for titles Cinemeta/TMDB cannot resolve). */
    public boolean hasMbDetail() {
        return (mbDesc != null && !mbDesc.isEmpty()) || mbEpisodes > 0
                || (mbRating != null && !mbRating.isEmpty());
    }
}
