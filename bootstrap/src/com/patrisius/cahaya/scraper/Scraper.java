package com.patrisius.cahaya.scraper;

import com.patrisius.cahaya.addon.StreamItem;

import java.util.List;

/**
 * An in-app (3rd-way) scraper: Java code running inside Cahaya, no
 * JavaScript engine, no external server, no plugin HTTP service.
 *
 * Contract, adapted from the Nuvio scraper contract found by REA:
 * Nuvio providers take a TMDB id and look the title/year up via TMDB.
 * Cahaya already knows the title/year from Cinemeta meta, so this
 * interface takes title/year directly — no TMDB key is needed, and
 * no third-party key is copied in.
 *
 * type is "movie" or "series". For series, season/episode are the
 * episode to find (0/0 = movie, or series without a chosen episode).
 * Callbacks run on the main thread. An empty list means "no match",
 * not an error; onError means the source itself failed (dead site,
 * network error) and the caller should just skip this scraper.
 */
public interface Scraper {
    interface Callback {
        void onResult(List<StreamItem> streams);
        void onError(Exception e);
    }

    String name();

    void getStreams(String title, int year, String type,
                    int season, int episode, Callback cb);
}
