package com.patrisius.cahaya.jsrunner;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.patrisius.cahaya.R;
import com.patrisius.cahaya.addon.StreamItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2nd way: run a Nuvio JS provider inside a headless WebView, in-app.
 *
 * Why WebView instead of a JS engine: Cahaya's bootstrap build has no
 * Gradle, so no Rhino/Graal dependency is available. WebView is a
 * framework component (android.webkit) and its V8 engine is already on
 * the box if the system WebView is present.
 *
 * The provider JS is NOT bundled in the APK or in git (upstream repo
 * ships no LICENSE). It is downloaded at runtime from the upstream
 * repo, exactly as Nuvio loads it, and cached only in the app's files
 * dir. First provider: 4khdhub (readable, cheerio-only, already
 * verified working through the Node adapter).
 *
 * Bridges the provider needs, all supplied in JS:
 *  - fetch(url, options): Java HttpURLConnection, so no CORS limits
 *    and custom headers work. Response text is passed back as a
 *    JSON-quoted string.
 *  - require('cheerio-without-node-native'): a from-scratch DOM shim
 *    (res/raw/cheerio_shim.js) covering only what 4khdhub uses.
 *  - console.log -> Android Log.
 *  - module/exports: provider code is eval'd inside __loadProvider,
 *    which supplies module, exports and require in scope.
 *
 * Fail-soft by design: WebView missing, provider download failing,
 * or any JS error just calls Callback.onError and the stream picker
 * still shows the addon / plugin / torrent / FibWatch results.
 */
public class JsScraperRunner {
    private static final String TAG = "JsScraper";
    private static final String UPSTREAM_BASE =
            "https://raw.githubusercontent.com/NuvioPlugin/All-in-One-Nuvio/refs/heads/main/providers/";
    public static final String PROVIDER_4KHDHUB = "4khdhub";
    public static final String PROVIDER_PLAYIMDB = "playimdb";
    private final String providerId;
    private final String providerUrl;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11; B866F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public interface Callback {
        void onResult(List<StreamItem> streams);
        void onError(String message);
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private WebView webView;
    private java.io.File logFile;
    private Callback pending;
    private int tmdbId;
    private String jsType;
    private int season;
    private int episode;
    private boolean finished;

    public JsScraperRunner(Activity activity, String providerId) {
        this.activity = activity;
        this.providerId = providerId;
        this.providerUrl = UPSTREAM_BASE + providerId + ".js";
    }

    /** File log: box logcat rotates in ~1 min and logd itself crashed
     *  on 2026-10-24, so diagnosis cannot rely on logcat. Every step
     *  below is also appended to getExternalFilesDir/js-scraper.log,
     *  which survives and is pullable via adb shell cat. */
    private void fileLog(String s) {
        try {
            if (logFile == null) {
                java.io.File dir = activity.getExternalFilesDir(null);
                if (dir == null) return;
                logFile = new java.io.File(dir, "js-scraper.log");
            }
            java.io.FileWriter w = new java.io.FileWriter(logFile, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    /** Runs the provider for one title. Must be called on the main thread. */
    public void run(int tmdbId, String type, int season, int episode, Callback cb) {
        fileLog("RUN " + providerId + " tmdbId=" + tmdbId + " type=" + type
                + " season=" + season + " episode=" + episode);
        this.tmdbId = tmdbId;
        this.jsType = "series".equals(type) ? "tv" : "movie";
        this.season = season;
        this.episode = episode;
        this.pending = cb;
        if (tmdbId <= 0) {
            fail("no moviedb_id in Cinemeta meta");
            return;
        }
        try {
            webView = new WebView(activity);
            fileLog("WebView created");
        } catch (Throwable t) {
            fail("WebView unavailable: " + t.getMessage());
            return;
        }
        try {
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            webView.setVisibility(View.INVISIBLE);
            webView.addJavascriptInterface(new Bridge(), "CahayaBridge");
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    injectBootstrapAndRun();
                }
            });
            // Attach 1x1 so JS runs in a real window context.
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(webView, new ViewGroup.LayoutParams(1, 1));
            webView.loadDataWithBaseURL("https://localhost/",
                    "<html><head><meta charset='utf-8'></head><body></body></html>",
                    "text/html", "UTF-8", null);
            main.postDelayed(new Runnable() {
                @Override public void run() { fail("timeout after 90s"); }
            }, 90000);
        } catch (Throwable t) {
            fail("WebView setup failed: " + t.getMessage());
        }
    }

    private void injectBootstrapAndRun() {
        io.execute(new Runnable() {
            @Override public void run() {
                final String shim = readRaw(R.raw.cheerio_shim);
                final String provider = httpGet(providerUrl);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (finished || webView == null) return;
                        if (shim == null) { fail("cheerio shim resource missing"); return; }
                        if (provider == null) { fail("provider download failed"); return; }
                        fileLog("shim bytes=" + shim.length()
                                + " provider bytes=" + provider.length());
                        webView.evaluateJavascript(BOOTSTRAP_JS, null);
                        webView.evaluateJavascript(shim, null);
                        String load = "window.__providerExports = window.__loadProvider("
                                + JSONObject.quote(provider) + ");";
                        webView.evaluateJavascript(load, null);
                        String call = "(function(){ try { var p = window.__providerExports.getStreams("
                                + tmdbId + ", " + JSONObject.quote(jsType) + ", "
                                + season + ", " + episode + ");"
                                + " Promise.resolve(p).then(function(streams){"
                                + "   try { CahayaBridge.onStreams(JSON.stringify(streams || [])); }"
                                + "   catch (e) { CahayaBridge.onError('stringify: ' + e.message); } })"
                                + " .catch(function(e){ CahayaBridge.onError(e && e.message ? e.message : String(e)); });"
                                + " } catch (e) { CahayaBridge.onError(e.message); } })();";
                        webView.evaluateJavascript(call, null);
                    }
                });
            }
        });
    }

    private static final String BOOTSTRAP_JS =
            "window.__fetchSeq = 0; window.__fetchPending = {};"
            + "window.__fetchDeliver = function(id, status, ok, bodyText) {"
            + "  var p = window.__fetchPending[id]; if (!p) return; delete window.__fetchPending[id];"
            + "  var resp = { ok: !!ok, status: status,"
            + "    text: function () { return Promise.resolve(bodyText); },"
            + "    json: function () { try { return Promise.resolve(JSON.parse(bodyText)); }"
            + "      catch (e) { return Promise.reject(e); } } };"
            + "  p.resolve(resp); };"
            + "window.__fetchReject = function(id, msg) {"
            + "  var p = window.__fetchPending[id]; if (!p) return; delete window.__fetchPending[id];"
            + "  p.reject(new Error(msg)); };"
            + "window.fetch = function(url, options) { return new Promise(function(resolve, reject) {"
            + "  var id = ++window.__fetchSeq; window.__fetchPending[id] = { resolve: resolve, reject: reject };"
            + "  try { CahayaBridge.fetch(id, String(url), JSON.stringify(options || {})); }"
            + "  catch (e) { reject(e); } }); };"
            + "window.__loadProvider = function(code) {"
            + "  var module = { exports: {} }; var exports = module.exports;"
            + "  function require(name) {"
            + "    if (name === 'cheerio-without-node-native') return window.__cheerio;"
            + "    throw new Error('unsupported require: ' + name); }"
            + "  eval(code); return module.exports; };"
            + "(function(){ console.log = function() { try { var s = [];"
            + "  for (var i = 0; i < arguments.length; i++) { try { s.push(String(arguments[i])); }"
            + "  catch (e) { s.push('[obj]'); } } CahayaBridge.log(s.join(' ')); } catch (e) {} };"
            + "  console.error = console.log; })();";

    private class Bridge {
        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, msg);
            fileLog("JS: " + msg);
        }

        @JavascriptInterface
        public void fetch(final int id, final String url, final String optionsJson) {
            io.execute(new Runnable() {
                @Override public void run() {
                    String method = "GET";
                    String body = null;
                    JSONObject headers = new JSONObject();
                    try {
                        JSONObject o = new JSONObject(optionsJson);
                        if (o.has("method")) method = o.optString("method", "GET");
                        if (o.has("headers")) headers = o.optJSONObject("headers");
                        if (o.has("body")) body = o.optString("body", null);
                    } catch (Exception ignored) { }
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestMethod(method);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent", UA);
                        if (headers != null) {
                            java.util.Iterator<String> keys = headers.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                c.setRequestProperty(k, headers.optString(k));
                            }
                        }
                        if (body != null) {
                            c.setDoOutput(true);
                            byte[] b = body.getBytes(StandardCharsets.UTF_8);
                            c.getOutputStream().write(b);
                        }
                        int status = c.getResponseCode();
                        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                        final String text = in == null ? "" : readAll(in);
                        final boolean ok = status >= 200 && status < 300;
                        fileLog("fetch " + url + " -> " + status
                                + " bytes=" + text.length());
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (finished || webView == null) return;
                                webView.evaluateJavascript(
                                        "window.__fetchDeliver(" + id + ", " + status + ", "
                                                + (ok ? "true" : "false") + ", "
                                                + JSONObject.quote(text) + ");", null);
                            }
                        });
                    } catch (final Exception e) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (finished || webView == null) return;
                                webView.evaluateJavascript(
                                        "window.__fetchReject(" + id + ", "
                                                + JSONObject.quote(String.valueOf(e.getMessage())) + ");", null);
                            }
                        });
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            });
        }

        @JavascriptInterface
        public void onStreams(final String json) {
            main.post(new Runnable() {
                @Override public void run() {
                    if (finished) return;
                    List<StreamItem> out = new ArrayList<>();
                    try {
                        JSONArray arr = new JSONArray(json);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s == null) continue;
                            String u = s.optString("url", "");
                            if (u.isEmpty()) continue;
                            String t = s.optString("title", "");
                            if (t.isEmpty()) t = s.optString("name", "Stream");
                            String q = s.optString("quality", "");
                            if (!q.isEmpty() && !t.contains(q)) t = t + " " + q;
                            StreamItem item = new StreamItem(u, t, providerId);
                            JSONObject h = s.optJSONObject("headers");
                            if (h != null) {
                                item.headers = new java.util.HashMap<>();
                                java.util.Iterator<String> keys = h.keys();
                                while (keys.hasNext()) {
                                    String k = keys.next();
                                    item.headers.put(k, h.optString(k));
                                }
                            }
                            out.add(item);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "stream JSON parse failed: " + e.getMessage());
                    }
                    fileLog("onStreams count=" + out.size());
                    final List<StreamItem> raw = out;
                    // Resolve download-page URLs to direct file links
                    // before the picker shows them: HubCloud pages and
                    // pixeldrain /u/ pages return HTML to mpv, which
                    // then ends the file silently. Unresolvable
                    // HubCloud rows are dropped, not shown as dead rows.
                    io.execute(new Runnable() {
                        @Override public void run() {
                            final List<StreamItem> resolved = resolveAll(raw);
                            fileLog("resolved count=" + resolved.size());
                            main.post(new Runnable() {
                                @Override public void run() {
                                    Callback cb = pending;
                                    finish();
                                    if (cb != null) cb.onResult(resolved);
                                }
                            });
                        }
                    });
                }
            });
        }

    private List<StreamItem> resolveAll(List<StreamItem> in) {
        final StreamItem[] results = new StreamItem[in.size()];
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(in.size());
        for (int i = 0; i < in.size(); i++) {
            final int idx = i;
            final StreamItem s = in.get(i);
            io.execute(new Runnable() {
                @Override public void run() {
                    try {
                        results[idx] = resolveOne(s);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await(45, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        List<StreamItem> out = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            if (results[i] != null) out.add(results[i]);
        }
        return out;
    }

    private StreamItem resolveOne(StreamItem s) {
        String url = s.url;
        String host = "";
        try {
            host = new URL(url).getHost().toLowerCase();
        } catch (Exception ignored) { }
        if (host.equals("pixeldrain.dev") && url.contains("/u/")) {
            String direct = url.replace("/u/", "/api/file/");
            int q = direct.indexOf('?');
            if (q >= 0) direct = direct.substring(0, q);
            fileLog("resolve pixeldrain " + url + " -> " + direct);
            return copyWithUrl(s, direct);
        }
        if (host.contains("hubcloud")) {
            String direct = followHubCloud(url);
            if (direct != null) {
                fileLog("resolve hubcloud " + url + " -> " + direct);
                return copyWithUrl(s, direct);
            }
            fileLog("drop hubcloud (unresolved) " + url);
            return null;
        }
        return s;
    }

    private StreamItem copyWithUrl(StreamItem s, String url) {
        StreamItem r = new StreamItem(url, s.title, s.source, s.fileIdx);
        r.seeders = s.seeders;
        r.size = s.size;
        r.headers = s.headers;
        return r;
    }

    private String queryParam(String url, String key) {
        try {
            String q = new URL(url).getQuery();
            if (q == null) return null;
            for (String pair : q.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8").equals(key)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Follows the HubCloud redirect chain (hubcloud -> workers.dev
     *  -> download page with a ?link= direct file URL) and returns
     *  that direct URL, or null if the chain ends in HTML. */
    private String followHubCloud(String url) {
        String cur = url;
        for (int i = 0; i < 5; i++) {
            String link = queryParam(cur, "link");
            if (link != null) return link;
            if (cur.contains("googleusercontent.com")) return cur;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Referer", cur);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303
                        || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

        @JavascriptInterface
        public void onError(final String message) {
            main.post(new Runnable() {
                @Override public void run() { fail(message); }
            });
        }
    }

    private void fail(String message) {
        if (finished) return;
        Log.d(TAG, "JS scraper failed: " + message);
        fileLog("FAIL: " + message);
        Callback cb = pending;
        finish();
        if (cb != null) cb.onError(message);
    }

    private void finish() {
        finished = true;
        if (webView != null) {
            try {
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) parent.removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) { }
            webView = null;
        }
        io.shutdownNow();
    }

    private String readRaw(int resId) {
        try {
            InputStream in = activity.getResources().openRawResource(resId);
            return readAll(in);
        } catch (Exception e) {
            Log.d(TAG, "readRaw failed: " + e.getMessage());
            return null;
        }
    }

    private String httpGet(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            return readAll(c.getInputStream());
        } catch (Exception e) {
            Log.d(TAG, "httpGet failed: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }
}
