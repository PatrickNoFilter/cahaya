package com.patrisius.cahaya.scraper;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal async HTTP GET with custom headers, for in-app scrapers. */
public class ScraperHttp {
    public interface Callback {
        void onResult(String body);
        void onError(Exception e);
    }

    private static final ExecutorService io = Executors.newFixedThreadPool(4);
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void get(final String urlStr,
                           final Map<String, String> headers,
                           final Callback cb) {
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL(urlStr).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (headers != null) {
                    for (Map.Entry<String, String> h : headers.entrySet()) {
                        c.setRequestProperty(h.getKey(), h.getValue());
                    }
                }
                int status = c.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new Exception("HTTP " + status + " for " + urlStr);
                }
                InputStream in = c.getInputStream();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
                r.close();
                final String body = sb.toString();
                main.post(() -> cb.onResult(body));
            } catch (final Exception e) {
                main.post(() -> cb.onError(e));
            }
        });
    }
}
