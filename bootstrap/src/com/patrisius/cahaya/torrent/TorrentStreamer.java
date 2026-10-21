package com.patrisius.cahaya.torrent;

import android.content.Context;
import android.util.Log;

import org.libtorrent4j.FileStorage;
import org.libtorrent4j.PieceIndexBitfield;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Streams a torrent straight into the player, Nuvio-style:
 * magnet -> metadata -> sequential download of one file ->
 * local HTTP server -> MediaPlayer.
 *
 * One active streamer at a time; stop() tears the session down.
 */
public class TorrentStreamer {
    private static final String TAG = "TorrentStreamer";
    private static final long READY_BYTES = 20L * 1024 * 1024; // start playback after this much
    private static final int META_TIMEOUT_S = 120;

    public interface Callback {
        void onProgress(String stage, int percent, String detail);
        void onReady(String httpUrl, String fileName);
        void onError(String msg);
    }

    private static TorrentStreamer active;

    public static synchronized void stopActive() {
        if (active != null) {
            active.stop();
            active = null;
        }
    }

    private volatile boolean stopped;
    private Thread worker;
    private SessionManager session;
    private HttpFileServer httpServer;
    private File targetFile;
    private long targetSize;
    private long pieceLen;
    private int firstPiece;
    private int lastPiece;
    private TorrentHandle handle;

    public synchronized void start(Context ctx, String magnetUri, int fileIdx,
                                   Callback cb) {
        stop();
        synchronized (TorrentStreamer.class) {
            active = this;
        }
        stopped = false;
        Context app = ctx.getApplicationContext();
        worker = new Thread(() -> run(app, magnetUri, fileIdx, cb),
                "torrent-streamer");
        worker.start();
    }

    public synchronized void stop() {
        stopped = true;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
            session = null;
        }
        synchronized (TorrentStreamer.class) {
            if (active == this) active = null;
        }
    }

    private void run(Context ctx, String magnetUri, int fileIdx, Callback cb) {
        try {
            progress(cb, "Starting torrent engine…", 0, "");
            session = new SessionManager();
            session.start();

            File dir = new File(ctx.getCacheDir(), "torrents");
            dir.mkdirs();
            File tmp = new File(ctx.getCacheDir(), "torrents-tmp");
            tmp.mkdirs();

            progress(cb, "Fetching metadata…", 1,
                    "asking peers & trackers");
            byte[] torrentBytes = session.fetchMagnet(magnetUri, META_TIMEOUT_S, tmp);
            if (stopped) return;
            if (torrentBytes == null || torrentBytes.length == 0) {
                cb.onError("No peers answered for this torrent");
                return;
            }

            TorrentInfo ti = new TorrentInfo(torrentBytes);
            FileStorage fs = ti.files();
            int n = ti.numFiles();
            int idx = (fileIdx >= 0 && fileIdx < n) ? fileIdx
                    : largestVideo(fs, n);
            String relPath = fs.filePath(idx);
            targetSize = fs.fileSize(idx);
            targetFile = new File(dir, relPath);
            String fileName = new File(relPath).getName();

            progress(cb, "Starting download…", 2, fileName);
            session.download(ti, dir);

            TorrentHandle h = null;
            for (int i = 0; i < 30 && !stopped; i++) {
                try {
                    h = session.find(ti.infoHash());
                } catch (Exception ignored) {
                }
                if (h != null) break;
                sleep(1000);
            }
            if (stopped) return;
            if (h == null) {
                cb.onError("Torrent did not start");
                return;
            }

            // Only our file; everything else ignored.
            Priority[] prios = new Priority[n];
            Arrays.fill(prios, Priority.IGNORE);
            prios[idx] = Priority.TOP_PRIORITY;
            h.prioritizeFiles(prios);

            // Sequential pieces across our file's range.
            pieceLen = ti.pieceLength();
            long off = fs.fileOffset(idx);
            firstPiece = (int) (off / pieceLen);
            lastPiece = (int) ((off + targetSize - 1) / pieceLen);
            h.setSequentialRange(firstPiece, lastPiece);
            handle = h;

            httpServer = new HttpFileServer();
            String httpUrl = httpServer.start(targetFile, targetSize, fileName,
                    () -> downloadedContiguous());

            long need = Math.min(READY_BYTES, targetSize);
            boolean ready = false;
            long lastReport = 0;
            while (!stopped) {
                long done = downloadedContiguous();
                int pct = targetSize > 0 ? (int) (done * 100 / targetSize) : 0;
                long now = System.currentTimeMillis();
                if (now - lastReport > 1500) {
                    lastReport = now;
                    String detail;
                    try {
                        TorrentStatus st = h.status();
                        detail = fmtMB(done) + " / " + fmtMB(targetSize)
                                + " · " + fmtRate(st.downloadRate());
                    } catch (Exception e) {
                        detail = fmtMB(done) + " / " + fmtMB(targetSize);
                    }
                    progress(cb, ready ? "Downloading…" : "Buffering…", pct, detail);
                }
                if (!ready && done >= need) {
                    ready = true;
                    cb.onReady(httpUrl, fileName);
                }
                if (targetSize > 0 && done >= targetSize) break;
                sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "streamer failed", e);
            if (!stopped) cb.onError("Torrent error: " + e.getMessage());
        }
    }

    /**
     * Bytes verifiably downloaded from the START of our file: counts
     * contiguous HAVE pieces. (File.length() lies on sparse files.)
     */
    private long downloadedContiguous() {
        TorrentHandle h = handle;
        if (h == null || pieceLen <= 0) return 0;
        try {
            TorrentStatus st = h.status(TorrentHandle.QUERY_PIECES);
            PieceIndexBitfield bf = st.pieces();
            int n = 0;
            for (int p = firstPiece; p <= lastPiece; p++) {
                if (bf.getBit(p)) n++;
                else break;
            }
            return Math.min((long) n * pieceLen, targetSize);
        } catch (Exception e) {
            return 0;
        }
    }

    private void progress(Callback cb, String stage, int pct, String detail) {
        try {
            cb.onProgress(stage, pct, detail);
        } catch (Exception ignored) {
        }
    }

    private static int largestVideo(FileStorage fs, int n) {
        int best = 0;
        long bestSize = -1;
        for (int i = 0; i < n; i++) {
            String p = fs.filePath(i).toLowerCase(Locale.US);
            boolean video = p.endsWith(".mp4") || p.endsWith(".mkv")
                    || p.endsWith(".avi") || p.endsWith(".webm")
                    || p.endsWith(".mov") || p.endsWith(".ts")
                    || p.endsWith(".m2ts");
            long s = fs.fileSize(i);
            if (video && s > bestSize) {
                bestSize = s;
                best = i;
            }
        }
        if (bestSize < 0) { // no video ext: largest file
            for (int i = 0; i < n; i++) {
                long s = fs.fileSize(i);
                if (s > bestSize) {
                    bestSize = s;
                    best = i;
                }
            }
        }
        return best;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fmtMB(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String fmtRate(int bps) {
        if (bps >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%d KB/s", bps / 1024);
    }
}
