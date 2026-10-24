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

        public String remainingText() {
            if (duration <= 0) return "";
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
        // Don't save if at the very start or very end
        if (position < 30 || (duration > 0 && position > duration - 30)) {
            // If at end, remove from list
            if (duration > 0 && position > duration - 30) {
                remove(ctx, videoId);
            }
            return;
        }

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Item> items = load(ctx);

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
        return items;
    }
}
