package com.patrisius.cahaya.web;

import com.patrisius.cahaya.addon.AddonManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tiny dependency-free HTTP server that lets a phone on the same LAN install
 * addons on this TV: it serves a small form page and an /install endpoint.
 * Runs only while the "add via phone" dialog is open.
 */
public class AddonWebServer {

    public interface Installer {
        void install(String url, AddonManager.Callback<String> cb);
    }

    private final Installer installer;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    private int port;

    public AddonWebServer(Installer installer) {
        this.installer = installer;
    }

    /** Starts on the first free port in 8081..8090. Returns the bound port. */
    public synchronized int start() throws Exception {
        for (int p = 8081; p <= 8090; p++) {
            try {
                server = new ServerSocket(p);
                port = p;
                break;
            } catch (Exception ignored) {
                // try next
            }
        }
        if (server == null) {
            throw new Exception("no free port 8081-8090");
        }
        running = true;
        thread = new Thread(this::loop, "cahaya-web");
        thread.setDaemon(true);
        thread.start();
        return port;
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
        }
        server = null;
    }

    public int getPort() {
        return port;
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = server.accept();
                new Thread(() -> handle(s), "cahaya-web-req").start();
            } catch (Exception e) {
                if (running) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = in.readLine();
            // consume headers
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                // ignore
            }
            String body;
            int code = 200;
            if (line != null && line.startsWith("GET /install")) {
                String url = queryParam(line, "url");
                body = installPage(url);
            } else if (line != null && (line.startsWith("GET / ") || line.startsWith("GET /?"))) {
                body = formPage();
            } else {
                code = 404;
                body = "<h1>404</h1>";
            }
            byte[] bytes = body.getBytes("UTF-8");
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String queryParam(String requestLine, String name) {
        try {
            int q = requestLine.indexOf('?');
            if (q < 0) {
                return null;
            }
            int sp = requestLine.indexOf(' ', q);
            String qstr = sp > 0 ? requestLine.substring(q + 1, sp) : requestLine.substring(q + 1);
            for (String pair : qstr.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), "UTF-8").equals(name)) {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String formPage() {
        return "<!doctype html><html><head><meta name=viewport "
                + "content='width=device-width,initial-scale=1'>"
                + "<title>Cahaya — install addon</title>"
                + "<style>body{font-family:sans-serif;background:#14161A;color:#fff;"
                + "margin:0;padding:24px}h1{font-size:20px}input{width:100%;box-sizing:border-box;"
                + "padding:12px;font-size:16px;border-radius:8px;border:1px solid #444;"
                + "background:#1E2126;color:#fff}button{margin-top:12px;width:100%;padding:14px;"
                + "font-size:18px;border-radius:8px;border:0;background:#4FC3F7;color:#000;"
                + "font-weight:bold}</style></head><body>"
                + "<h1>\u2600\uFE0F Cahaya — install addon</h1>"
                + "<p>Paste the addon's base URL. Its manifest.json is fetched to verify it.</p>"
                + "<form action=/install method=get>"
                + "<input name=url placeholder='https://\u2026' autocomplete=off>"
                + "<button>Install on TV</button></form></body></html>";
    }

    private String installPage(String url) {
        String safe = url == null ? "" : escape(url);
        if (url == null || url.trim().isEmpty()) {
            return "<!doctype html><html><body style='font-family:sans-serif'>"
                    + "<h1>No URL given</h1><a href=/>Back</a></body></html>";
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        final Exception[] error = new Exception[1];
        installer.install(url.trim(), new AddonManager.Callback<String>() {
            @Override
            public void onResult(String name) {
                result[0] = name;
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                error[0] = e;
                latch.countDown();
            }
        });
        try {
            latch.await(25, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        String msg;
        if (result[0] != null) {
            msg = "<h1>Installed \u2705</h1><p>" + escape(result[0])
                    + " is now on your TV.</p>";
        } else if (error[0] != null) {
            msg = "<h1>Install failed</h1><p>" + escape(String.valueOf(error[0].getMessage()))
                    + "</p>";
        } else {
            msg = "<h1>Still working\u2026</h1><p>Check the TV screen.</p>";
        }
        return "<!doctype html><html><head><meta name=viewport "
                + "content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;background:#14161A;color:#fff;padding:24px'>"
                + msg + "<p style='color:#888'>" + safe + "</p>"
                + "<a href=/>Install another</a></body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
