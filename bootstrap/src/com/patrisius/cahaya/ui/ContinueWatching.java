package com.patrisius.cahaya.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks recently watched videos with resume positions.
 * Stored in SharedPreferences as JSON.
 */
public class ContinueWatching {
    private static final String PREFS = "continue_watching";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 20;

    public static class Item {
        public String videoId;
        public String title;
        public String type;
        public String poster;
        public double position; // seconds
        public double duration; // seconds
        public long timestamp; // millis

        public double progress() {
            return duration > 0 ? position / duration : 0;
        }

        /** Finished when playback reached (or passed) the last 30s. */
        public boolean isFinished() {
            return duration > 0 && position >= duration - 30;
        }

        public String remainingText() {
            if (duration <= 0) return "";
            if (isFinished()) return "Selesai";
            long remaining = (long) (duration - position);
            if (remaining < 60) return "Sisa " + remaining + " dtk";
            long mins = remaining / 60;
            if (mins < 60) return "Sisa " + mins + " mnt";
            long hours = mins / 60;
            return "Sisa " + hours + " j " + (mins % 60) + " mnt";
        }
    }

    public static void save(Context ctx, String videoId, String title, String type,
                           String poster, double position, double duration) {
        if (videoId == null || videoId.isEmpty()) return;
        // Don't save if at the very start.
        if (position < 30) return;
        // v1.1.0: finished titles are KEPT, not dropped — a finished
        // watch stays in Playing History (badge "Selesai") so the
        // user can replay it. Cap the position at the duration.
        if (duration > 0 && position > duration) position = duration;

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Item> items = load(ctx); // already deduped (see load)

        // v1.5.1: one entry per title. A newer episode of the same
        // series replaces the older one; recording an older episode
        // leaves the stored newer entry untouched and adds nothing.
        String itemType = type != null ? type : "movie";
        String key = titleKey(videoId, itemType);
        int[] ep = episodeOf(videoId);
        for (Item it : new ArrayList<>(items)) {
            if (!itemType.equals(it.type)) continue;
            if (!key.equals(titleKey(it.videoId, it.type))) continue;
            if (videoId.equals(it.videoId)) continue; // re-save below
            int[] oldEp = episodeOf(it.videoId);
            if (ep != null && oldEp != null && compareEp(ep, oldEp) < 0) {
                return; // older episode: keep the newer stored entry
            }
            items.remove(it); // newer (or same) episode: replace
        }

        // Remove existing entry for this video
        items.removeIf(it -> videoId.equals(it.videoId));

        // Add new entry at front
        Item item = new Item();
        item.videoId = videoId;
        item.title = title != null ? title : "Unknown";
        item.type = type != null ? type : "movie";
        item.poster = poster;
        item.position = position;
        item.duration = duration;
        item.timestamp = System.currentTimeMillis();
        items.add(0, item);

        // Trim to max
        while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);

        // Save
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("videoId", it.videoId);
                o.put("title", it.title);
                o.put("type", it.type);
                o.put("poster", it.poster != null ? it.poster : "");
                o.put("position", it.position);
                o.put("duration", it.duration);
                o.put("timestamp", it.timestamp);
                arr.put(o);
            }
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void remove(Context ctx, String videoId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Item> items = load(ctx);
        items.removeIf(it -> videoId.equals(it.videoId));
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("videoId", it.videoId);
                o.put("title", it.title);
                o.put("type", it.type);
                o.put("poster", it.poster != null ? it.poster : "");
                o.put("position", it.position);
                o.put("duration", it.duration);
                o.put("timestamp", it.timestamp);
                arr.put(o);
            }
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static List<Item> load(Context ctx) {
        List<Item> items = new ArrayList<>();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ITEMS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.videoId = o.optString("videoId");
                it.title = o.optString("title");
                it.type = o.optString("type");
                it.poster = o.optString("poster");
                if (it.poster.isEmpty()) it.poster = null;
                it.position = o.optDouble("position");
                it.duration = o.optDouble("duration");
                it.timestamp = o.optLong("timestamp");
                items.add(it);
            }
        } catch (Exception ignored) {}
        // v1.5.1: collapse legacy duplicates (same title recorded
        // under several episodes) so the row shows one card per title.
        return dedupe(items);
    }

    /** Grouping key: one entry per title. Series group by series id
     *  (video id minus the trailing :season:episode, parsed from the
     *  right so tmdb: ids work too); movies group by their own id. */
    private static String titleKey(String videoId, String type) {
        if (videoId == null) return "";
        if (!"series".equals(type)) return videoId;
        String[] parts = videoId.split(":");
        if (parts.length < 3) return videoId;
        try {
            Integer.parseInt(parts[parts.length - 2]);
            Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return videoId;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++) {
            if (i > 0) sb.append(":");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** [season, episode] for a series video id, or null. */
    private static int[] episodeOf(String videoId) {
        if (videoId == null) return null;
        String[] parts = videoId.split(":");
        if (parts.length < 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[parts.length - 2]),
                    Integer.parseInt(parts[parts.length - 1])};
        } catch (Exception e) {
            return null;
        }
    }

    private static int compareEp(int[] a, int[] b) {
        if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
        return Integer.compare(a[1], b[1]);
    }

    /** Keep, per title key, the newest episode (ties: the first, i.e.
     *  most recently saved, since the list is newest-first). */
    private static List<Item> dedupe(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            String key = titleKey(it.videoId, it.type);
            int found = -1;
            for (int i = 0; i < out.size(); i++) {
                Item o = out.get(i);
                if (!it.type.equals(o.type)) continue;
                if (!key.equals(titleKey(o.videoId, o.type))) continue;
                found = i;
                break;
            }
            if (found < 0) {
                out.add(it);
                continue;
            }
            Item kept = out.get(found);
            int[] ep = episodeOf(it.videoId);
            int[] keptEp = episodeOf(kept.videoId);
            if (ep != null && keptEp != null && compareEp(ep, keptEp) > 0) {
                out.set(found, it);
            }
            // Else the kept entry is newer or equal — drop this one.
        }
        return out;
    }
}
