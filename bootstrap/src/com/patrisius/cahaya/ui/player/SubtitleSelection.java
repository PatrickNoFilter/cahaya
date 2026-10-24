package com.patrisius.cahaya.ui.player;

/**
 * What the user (or auto-select) chose. The subtitle picker's tick is derived
 * from this one value only, never from mpv's track ids or "sid", so it cannot
 * disagree with what the UI just did.
 */
public final class SubtitleSelection {
    public enum Kind { OFF, EMBEDDED, ONLINE }

    public final Kind kind;
    public final int embeddedId;       // valid for EMBEDDED
    public final String onlineUrl;     // valid for ONLINE

    private SubtitleSelection(Kind k, int id, String url) { kind = k; embeddedId = id; onlineUrl = url; }

    public static final SubtitleSelection OFF = new SubtitleSelection(Kind.OFF, -1, null);

    public static SubtitleSelection embedded(int id) { return new SubtitleSelection(Kind.EMBEDDED, id, null); }

    public static SubtitleSelection online(String url) { return new SubtitleSelection(Kind.ONLINE, -1, url); }

    public boolean isOff() { return kind == Kind.OFF; }

    public boolean isEmbedded(int id) { return kind == Kind.EMBEDDED && embeddedId == id; }

    public boolean isOnline(String url) { return kind == Kind.ONLINE && onlineUrl != null && onlineUrl.equals(url); }

    @Override public String toString() {
        return kind == Kind.OFF ? "OFF" : kind == Kind.EMBEDDED ? "EMBEDDED#" + embeddedId : "ONLINE " + onlineUrl;
    }
}
