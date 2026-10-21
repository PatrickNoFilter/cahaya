package com.patrisius.cahaya.addon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.1 bootstrap addon manager: zero-dependency Stremio-protocol client.
 * HttpURLConnection + org.json (both in the Android framework), background
 * threads via ExecutorService, results posted to the main thread.
 */
public class AddonManager {

    /** Default addon installed on first run. */
    public static final String DEFAULT_ADDON = "https://v3-cinemeta.strem.io";

    public interface Callback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());

    public AddonManager(Context ctx) {
        prefs = ctx.getSharedPreferences("cahaya_addons", Context.MODE_PRIVATE);
    }

    public Set<String> addonUrls() {
        return prefs.getStringSet("urls", new HashSet<String>());
    }

    /** Installs the default addon on first run (validates its manifest). */
    public void ensureDefaults(Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                Set<String> urls = new HashSet<>(addonUrls());
                boolean fresh;
                if (!urls.contains(DEFAULT_ADDON)) {
                    fetchText(DEFAULT_ADDON + "/manifest.json"); // throws if dead
                    urls.add(DEFAULT_ADDON);
                    prefs.edit().putStringSet("urls", urls).apply();
                    fresh = true;
                } else {
                    fresh = false;
                }
                post(cb, fresh, null);
            } catch (Exception e) {
                post(cb, false, e);
            }
        });
    }

    /** GET {base}/catalog/{type}/{id}.json -> items. */
    public void catalog(String baseUrl, String type, String catalogId,
                        Callback<List<CatalogItem>> cb) {
        io.execute(() -> {
            try {
                String body = fetchText(
                        baseUrl + "/catalog/" + type + "/" + catalogId + ".json");
                JSONObject root = new JSONObject(body);
                JSONArray metas = root.optJSONArray("metas");
                List<CatalogItem> out = new ArrayList<>();
                if (metas != null) {
                    for (int i = 0; i < metas.length(); i++) {
                        JSONObject m = metas.getJSONObject(i);
                        String id = m.optString("id", m.optString("imdb_id", ""));
                        if (id.isEmpty()) {
                            continue;
                        }
                        out.add(new CatalogItem(
                                id,
                                m.optString("type", type),
                                m.optString("name", "?"),
                                m.optString("poster", null)));
                    }
                }
                post(cb, out, null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    private <T> void post(Callback<T> cb, T result, Exception e) {
        main.post(() -> {
            if (e == null) {
                cb.onResult(result);
            } else {
                cb.onError(e);
            }
        });
    }

    /** Plain GET with a Cahaya user agent. Follows redirects. */
    public static String fetchText(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Cahaya/0.1.0 (Android TV)");
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " for " + urlStr);
        }
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        r.close();
        return sb.toString();
    }
}
