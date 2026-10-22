package com.patrisius.cahaya.torrent;

import android.content.Context;
import android.util.Log;

import org.libtorrent4j.FileStorage;
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
    private static final long READY_BYTES = 8L * 1024 * 1024; // start playback after this much
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
    private int fileIndex;
    private TorrentHandle handle;

    public synchronized void start(Context ctx, String magnetUri, int fileIdx,
                                   Callback cb) {
        // Stop any previous torrent first — otherwise orphaned sessions
        // keep downloading in the background and split the bandwidth.
        stopActive();
        stopped = false;
        synchronized (TorrentStreamer.class) {
            active = this;
        }
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
            // Run fetch in a thread so we can show elapsed time (it blocks).
            final byte[][] result = new byte[1][];
            Thread fetchThread = new Thread(() -> {
                try {
                    result[0] = session.fetchMagnet(magnetUri, META_TIMEOUT_S, tmp);
                } catch (Exception e) {
                    result[0] = null;
                }
            });
            fetchThread.start();
            long fetchStart = System.currentTimeMillis();
            while (fetchThread.isAlive()) {
                if (stopped) {
                    fetchThread.interrupt();
                    return;
                }
                long elapsed = (System.currentTimeMillis() - fetchStart) / 1000;
                progress(cb, "Fetching metadata… (" + elapsed + "s)", 1,
                        "asking peers & trackers");
                try {
                    fetchThread.join(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            byte[] torrentBytes = result[0];
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
            fileIndex = idx;
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

            // Time-critical pieces (Nuvio mechanism): mark the head pieces
            // with deadline=0 so libtorrent actively prioritizes them across
            // all peers. More effective than sequential mode for streaming.
            pieceLen = ti.pieceLength();
            long off = fs.fileOffset(idx);
            firstPiece = (int) (off / pieceLen);
            lastPiece = (int) ((off + targetSize - 1) / pieceLen);
            handle = h;

            int totalPieces = lastPiece - firstPiece + 1;
            // Head-only preload: first 8 pieces at flat deadline=0.
            // Focuses 100% of startup bandwidth on the beginning.
            // NO tail downloading — mpv fetches MKV cues/moov on-demand via HTTP range.
            int headN = Math.min(8, totalPieces);
            for (int p = firstPiece; p < firstPiece + headN; p++) {
                try {
                    h.setPieceDeadline(p, 0);
                    h.piecePriority(p, Priority.TOP_PRIORITY);
                } catch (Exception ignored) {
                }
            }
            // Readahead: next 30 pieces at lower priority (will be adjusted as playback progresses)
            int readAheadN = Math.min(30, totalPieces - headN);
            for (int p = firstPiece + headN; p < firstPiece + headN + readAheadN; p++) {
                try {
                    h.piecePriority(p, Priority.DEFAULT);
                } catch (Exception ignored) {
                }
            }

            httpServer = new HttpFileServer();
            String httpUrl = httpServer.start(targetFile, targetSize, fileName,
                    new HttpFileServer.RangeAvailable() {
                        @Override
                        public boolean isAvailable(long s, long e) {
                            return isRangeAvailable(s, e);
                        }
                        @Override
                        public void prioritizeRange(long s, long e) {
                            prioritizePiecesForRange(s, e);
                        }
                    });

            long need = Math.min(READY_BYTES, targetSize);
            boolean ready = false;
            long lastReport = 0;
            long lastFlush = 0;
            long startTime = System.currentTimeMillis();
            while (!stopped) {
                // Flush libtorrent's memory cache to disk so the HTTP server
                // (which reads the file) sees the same bytes havePiece reports.
                long now0 = System.currentTimeMillis();
                if (now0 - lastFlush > 2000) {
                    lastFlush = now0;
                    try {
                        h.flushCache();
                    } catch (Exception ignored) {
                    }
                }
                long done = downloadedContiguous();
                // Percent is progress toward the ready threshold (what the user
                // waits for), not the whole file.
                int pct = need > 0 ? (int) Math.min(100, done * 100 / need) : 0;
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
                if (!ready && done > 0) {
                    // Start mpv as soon as we have ANY data.
                    // mpv handles buffering internally.
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
            int n = 0;
            for (int p = firstPiece; p <= lastPiece; p++) {
                if (h.havePiece(p)) n++;
                else break;
            }
            return Math.min((long) n * pieceLen, targetSize);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Check if the last 2 pieces (MKV seek table) are downloaded. */
    private boolean hasTailPieces() {
        TorrentHandle h = handle;
        if (h == null) return false;
        try {
            for (int p = Math.max(lastPiece - 1, firstPiece); p <= lastPiece; p++) {
                if (!h.havePiece(p)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Check whether bytes [start, end] of our file are on disk. */
    private boolean isRangeAvailable(long start, long end) {
        TorrentHandle h = handle;
        if (h == null || pieceLen <= 0) return false;
        try {
            // Flush so havePiece matches what's readable from disk.
            h.flushCache();
            // Correct: (fileOffset + start) / pieceLen, not firstPiece + start/pieceLen
            // (integer division makes those different when file doesn't start on piece boundary)
            long fileOff = 0;
            try {
                fileOff = h.torrentFile().files().fileOffset(fileIndex);
            } catch (Exception ignored) {}
            int p0 = (int) ((fileOff + start) / pieceLen);
            int p1 = (int) ((fileOff + end) / pieceLen);
            if (p0 < firstPiece) p0 = firstPiece;
            if (p1 > lastPiece) p1 = lastPiece;
            for (int p = p0; p <= p1; p++) {
                if (!h.havePiece(p)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Prioritize pieces for a requested byte range (e.g., mpv fetching MKV cues).
     * Sets deadline=0 (needed now) and TOP_PRIORITY.
     */
    private void prioritizePiecesForRange(long start, long end) {
        TorrentHandle h = handle;
        if (h == null || pieceLen <= 0) return;
        try {
            long fileOff = 0;
            try {
                fileOff = h.torrentFile().files().fileOffset(fileIndex);
            } catch (Exception ignored) {}
            int p0 = (int) ((fileOff + start) / pieceLen);
            int p1 = (int) ((fileOff + end) / pieceLen);
            if (p0 < firstPiece) p0 = firstPiece;
            if (p1 > lastPiece) p1 = lastPiece;
            for (int p = p0; p <= p1; p++) {
                try {
                    h.setPieceDeadline(p, 0);
                    h.piecePriority(p, Priority.TOP_PRIORITY);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** Check if the file starts with a valid video container header. */
    private boolean hasValidHeader(java.io.File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] magic = new byte[4];
            if (raf.read(magic) != 4) return false;
            // MKV: 1A 45 DF A3, MP4: ....ftyp, AVI: RIFF
            boolean mkv = (magic[0] == 0x1A && magic[1] == 0x45
                    && magic[2] == (byte) 0xDF && magic[3] == (byte) 0xA3);
            boolean mp4 = (magic[0] == 0x00 && magic[1] == 0x00);
            boolean avi = (magic[0] == 'R' && magic[1] == 'I'
                    && magic[2] == 'F' && magic[3] == 'F');
            return mkv || mp4 || avi;
        } catch (Exception e) {
            return false;
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
