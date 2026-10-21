package com.patrisius.cahaya.addon;

/** One entry of an addon catalog response. */
public class CatalogItem {
    public final String id;
    public final String type;
    public final String name;
    public final String poster;

    public CatalogItem(String id, String type, String name, String poster) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.poster = poster;
    }
}
