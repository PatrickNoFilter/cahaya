package com.patrisius.cahaya.torrent;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny local HTTP server that serves a single growing file with Range
 * support, so MediaPlayer can stream a torrent while it downloads.
 *
 * If the player asks for bytes that aren't downloaded yet, the handler
 * waits (up to WAIT_MS) for them to arrive instead of failing.
 */
public class HttpFileServer {
    private static final String TAG = "HttpFileServer";
    private static final long WAIT_MS = 45000;
    private static final long POLL_MS = 500;

    private ServerSocket server;
    private ExecutorService pool;
    private volatile File file;
    private volatile long fileSize;
    private volatile String contentType = "video/mp4";
    private volatile BytesAvailable bytesAvailable;
    private int port;

    /** Reports how many bytes from the start of the file are playable. */
    public interface BytesAvailable {
        long get();
    }

    /** Starts on 127.0.0.1, first free port 8901-8910. Returns base URL. */
    public String start(File file, long fileSize, String fileName,
                        BytesAvailable bytesAvailable) throws Exception {
        this.file = file;
        this.fileSize = fileSize;
        this.bytesAvailable = bytesAvailable;
        this.contentType = mimeFor(fileName);
        for (int p = 8901; p <= 8910; p++) {
            try {
                server = new ServerSocket(p, 8,
                        java.net.InetAddress.getByName("127.0.0.1"));
                port = p;
                break;
            } catch (Exception ignored) {
            }
        }
        if (server == null) {
            throw new Exception("no free local port");
        }
        pool = Executors.newCachedThreadPool();
        final ServerSocket srv = server;
        pool.execute(() -> {
            while (!srv.isClosed()) {
                try {
                    Socket s = srv.accept();
                    pool.execute(() -> handle(s));
                } catch (Exception e) {
                    break;
                }
            }
        });
        return "http://127.0.0.1:" + port + "/";
    }

    public void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(60000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream()));
            String reqLine = in.readLine();
            if (reqLine == null) return;
            boolean isHead = reqLine.startsWith("HEAD");
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                    range = line.substring(6).trim();
                }
            }

            long start = 0, end = fileSize - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                try {
                    start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        end = Math.min(Long.parseLong(parts[1].trim()), fileSize - 1);
                    }
                    partial = true;
                } catch (Exception ignored) {
                }
            }
            if (start >= fileSize) {
                sendStatus(s.getOutputStream(), 416, 0, 0, 0);
                return;
            }

            // Wait for the requested bytes to be downloaded.
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                long have = bytesAvailable.get();
                if (have > end) break;
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            long have = bytesAvailable.get();
            if (have <= start) {
                sendStatus(s.getOutputStream(), 408, 0, 0, 0);
                return;
            }
            end = Math.min(end, have - 1);

            OutputStream out = s.getOutputStream();
            sendStatus(out, partial ? 206 : 200, start, end, fileSize);
            if (isHead) return;

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(start);
                byte[] buf = new byte[64 * 1024];
                long remaining = end - start + 1;
                while (remaining > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                }
                out.flush();
            }
        } catch (Exception e) {
            Log.d(TAG, "handler: " + e.getMessage());
        }
    }

    private void sendStatus(OutputStream out, int code, long start, long end,
                            long total) throws Exception {
        String status = code == 206 ? "206 Partial Content"
                : code == 200 ? "200 OK"
                : code == 416 ? "416 Range Not Satisfiable" : "408 Request Timeout";
        long len = (code == 200 || code == 206) ? (end - start + 1) : 0;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        if (code == 206) {
            h.append("Content-Range: bytes ").append(start).append("-")
                    .append(end).append("/").append(total).append("\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        out.flush();
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".ts") || n.endsWith(".m2ts")) return "video/mp2t";
        return "video/mp4";
    }
}
