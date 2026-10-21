package com.patrisius.cahaya.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.addon.StreamItem;
import com.patrisius.cahaya.addon.SubtitleItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v0.3 plugin manager: installs native plugins from a base URL by fetching
 * {base}/plugin.json, stores them in SharedPreferences, and queries their
 * /streams and /subtitles HTTP endpoints. Plugins are plain web services;
 * no code is downloaded or executed on the device.
 */
public class PluginManager {

    public interface Callback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    private final SharedPreferences prefs;
    private final AddonManager addons; // reuses its thread pool + fetch

    public PluginManager(Context ctx, AddonManager addons) {
        this.prefs = ctx.getSharedPreferences("cahaya_plugins", Context.MODE_PRIVATE);
        this.addons = addons;
    }

    public List<Plugin> plugins() {
        List<Plugin> out = new ArrayList<>();
        for (String url : prefs.getStringSet("urls", new HashSet<String>())) {
            Plugin p = new Plugin(url);
            p.enabled = prefs.getBoolean("en_" + url, true);
            try {
                JSONObject m = new JSONObject(prefs.getString("meta_" + url, "{}"));
                p.name = m.optString("name", url);
                p.version = m.optString("version", "?");
                p.description = m.optString("description", "");
            } catch (Exception ignored) {
            }
            out.add(p);
        }
        return out;
    }

    /** Fetches {base}/plugin.json and stores the plugin. */
    public void install(final String baseUrl, final Callback<Plugin> cb) {
        final String base = baseUrl.replaceAll("/+$", "");
        addons.fetchTextAsync(base + "/plugin.json", new AddonManager.Callback<String>() {
            @Override
            public void onResult(String body) {
                try {
                    JSONObject m = new JSONObject(body);
                    if (!m.has("name")) {
                        throw new Exception("plugin.json has no name");
                    }
                    Set<String> urls = new HashSet<>(prefs.getStringSet("urls", new HashSet<String>()));
                    urls.add(base);
                    prefs.edit()
                            .putStringSet("urls", urls)
                            .putBoolean("en_" + base, true)
                            .putString("meta_" + base, body)
                            .apply();
                    Plugin p = new Plugin(base);
                    p.name = m.optString("name", base);
                    p.version = m.optString("version", "?");
                    p.description = m.optString("description", "");
                    cb.onResult(p);
                } catch (Exception e) {
                    cb.onError(e);
                }
            }

            @Override
            public void onError(Exception e) {
                cb.onError(e);
            }
        });
    }

    public void setEnabled(String baseUrl, boolean enabled) {
        prefs.edit().putBoolean("en_" + baseUrl, enabled).apply();
    }

    public void remove(String baseUrl) {
        Set<String> urls = new HashSet<>(prefs.getStringSet("urls", new HashSet<String>()));
        urls.remove(baseUrl);
        prefs.edit()
                .putStringSet("urls", urls)
                .remove("en_" + baseUrl)
                .remove("meta_" + baseUrl)
                .apply();
    }

    /** GET {base}/streams?type=..&id=.. -> {streams:[{url,title}]}. 404 -> empty. */
    public void pluginStreams(final String baseUrl, String type, String id,
                              final Callback<List<StreamItem>> cb) {
        try {
            String q = baseUrl + "/streams?type=" + URLEncoder.encode(type, "UTF-8")
                    + "&id=" + URLEncoder.encode(id, "UTF-8");
            queryList(q, "streams", baseUrl, cb);
        } catch (Exception e) {
            cb.onError(e);
        }
    }

    /** GET {base}/subtitles?type=..&id=.. -> {subtitles:[{url,lang}]}. 404 -> empty. */
    public void pluginSubtitles(final String baseUrl, String type, String id,
                                final Callback<List<SubtitleItem>> cb) {
        try {
            String q = baseUrl + "/subtitles?type=" + URLEncoder.encode(type, "UTF-8")
                    + "&id=" + URLEncoder.encode(id, "UTF-8");
            querySubs(q, baseUrl, cb);
        } catch (Exception e) {
            cb.onError(e);
        }
    }

    private void queryList(String url, String arrayKey, final String source,
                           final Callback<List<StreamItem>> cb) {
        addons.fetchTextAsync(url, new AddonManager.Callback<String>() {
            @Override
            public void onResult(String body) {
                try {
                    List<StreamItem> out = new ArrayList<>();
                    JSONArray arr = new JSONObject(body).optJSONArray(arrayKey);
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.getJSONObject(i);
                            String u = s.optString("url", "");
                            if (u.isEmpty()) {
                                continue;
                            }
                            String t = s.optString("title", s.optString("name", "Stream"));
                            out.add(new StreamItem(u, t, source));
                        }
                    }
                    cb.onResult(out);
                } catch (Exception e) {
                    cb.onError(e);
                }
            }

            @Override
            public void onError(Exception e) {
                if (is404(e)) {
                    cb.onResult(new ArrayList<StreamItem>());
                } else {
                    cb.onError(e);
                }
            }
        });
    }

    private void querySubs(String url, final String source,
                           final Callback<List<SubtitleItem>> cb) {
        addons.fetchTextAsync(url, new AddonManager.Callback<String>() {
            @Override
            public void onResult(String body) {
                try {
                    List<SubtitleItem> out = new ArrayList<>();
                    JSONArray arr = new JSONObject(body).optJSONArray("subtitles");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.getJSONObject(i);
                            String u = s.optString("url", "");
                            if (u.isEmpty()) {
                                continue;
                            }
                            String lang = s.optString("lang", "?");
                            out.add(new SubtitleItem(u, lang,
                                    s.optString("label", lang + " · " + source)));
                        }
                    }
                    cb.onResult(out);
                } catch (Exception e) {
                    cb.onError(e);
                }
            }

            @Override
            public void onError(Exception e) {
                if (is404(e)) {
                    cb.onResult(new ArrayList<SubtitleItem>());
                } else {
                    cb.onError(e);
                }
            }
        });
    }

    private boolean is404(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("HTTP 404");
    }
}
