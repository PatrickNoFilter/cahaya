package com.patrisius.cahaya.ui.player;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1.18.0: pre-playback stream probe.
 * v1.19.1: stream-level HDR10+ scan added. The v1.18.0 probe only checked the
 *          container (KEY_HDR10_PLUS_INFO), which MISSED the 2026-11-01 crash
 *          file: the container said no HDR10+, but the decoder log showed
 *          hdr10-plus-info inside the HEVC stream SEI. This pass now also
 *          reads video samples and hunts SEI NAL units for the SMPTE ST
 *          2094-40 HDR10+ T.35 marker, in both Annex-B and AVCC framing.
 *
 * The box's firmware MediaCodec dies with a native SIGSEGV in
 * ACodec::getHDR10PlusInfo on HDR10+ content (tombstone 2026-11-01
 * 08:27, while playing an HEVC file carrying hdr10-plus-info). That is
 * not fixable from the app — but it IS avoidable: probe for HDR10+
 * before loadfile and fall back to software decoding.
 *
 * The same pass censuses subtitle tracks (mime list) for the
 * embedded-bitmap-subtitle work.
 *
 * All failures fail OPEN (assume no HDR10+): a missed detection just
 * means today's behavior.
 */
public class HdrProbe {

    public static class Result {
        public boolean hdr10plus = false;
        /** "container" (KEY_HDR10_PLUS_INFO) or "stream-sei" (T.35 in SEI). */
        public String hdr10plusPath = null;
        public String videoMime = null;
        public String videoFormat = null;   // full MediaFormat dump, for the log
        public List<String> subTracks = new ArrayList<>();
    }

    // HDR10+ SEI: user_data_registered_itu_t_t35 (payloadType 4),
    // country 0xB5, terminal provider 0x003C, provider-oriented 0x0001,
    // application_identifier 4 (SMPTE ST 2094-40), application_mode 1.
    private static final int[] HDR10PLUS_T35_HEAD = {
            0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01 };

    // HEVC NAL types carrying SEI.
    private static final int HEVC_PREFIX_SEI = 39;
    private static final int HEVC_SUFFIX_SEI = 40;
    // AVC NAL type carrying SEI.
    private static final int AVC_SEI = 6;

    /** Probe with a hard timeout; never throws, never blocks past timeoutMs. */
    public static Result probe(String url, Map<String, String> headers, long timeoutMs) {
        final Result[] out = new Result[1];
        Thread t = new Thread(() -> out[0] = probeNow(url, headers), "hdr-probe");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
        return out[0] != null ? out[0] : new Result();
    }

    private static Result probeNow(String url, Map<String, String> headers) {
        Result r = new Result();
        MediaExtractor ex = new MediaExtractor();
        try {
            if (headers == null || headers.isEmpty()) {
                ex.setDataSource(url);
            } else {
                ex.setDataSource(url, headers);
            }
            int n = ex.getTrackCount();
            int videoIdx = -1;
            for (int i = 0; i < n; i++) {
                MediaFormat f;
                try {
                    f = ex.getTrackFormat(i);
                } catch (Throwable t) {
                    continue;
                }
                String mime;
                try {
                    mime = f.getString(MediaFormat.KEY_MIME);
                } catch (Throwable t) {
                    continue;
                }
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    if (videoIdx < 0) {
                        videoIdx = i;
                        r.videoMime = mime;
                    }
                    try {
                        r.videoFormat = f.toString();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (f.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)) {
                            r.hdr10plus = true;
                            r.hdr10plusPath = "container";
                        }
                    } catch (Throwable ignored) {
                    }
                } else if (mime.startsWith("subtitle/") || mime.startsWith("text/")) {
                    String lang = "";
                    try {
                        lang = f.getString(MediaFormat.KEY_LANGUAGE);
                    } catch (Throwable ignored) {
                    }
                    r.subTracks.add(mime + "/" + (lang == null ? "" : lang));
                }
            }
            // v1.19.1: container check can miss (2026-11-01 crash file did).
            // Scan the video samples themselves for the HDR10+ SEI marker.
            if (!r.hdr10plus && videoIdx >= 0
                    && ("video/hevc".equals(r.videoMime) || "video/avc".equals(r.videoMime))) {
                try {
                    if (scanStreamForHdr10Plus(ex, videoIdx, r.videoMime)) {
                        r.hdr10plus = true;
                        r.hdr10plusPath = "stream-sei";
                    }
                } catch (Throwable ignored) {
                    // fail open
                }
            }
        } catch (Throwable t) {
            // fail open
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /**
     * Read encoded video samples and look for an HDR10+ T.35 SEI.
     * Bounded: at most MAX_SAMPLES samples / BYTE_BUDGET bytes.
     */
    private static boolean scanStreamForHdr10Plus(MediaExtractor ex, int trackIdx, String mime) {
        final boolean isHevc = "video/hevc".equals(mime);
        final long BYTE_BUDGET = 4L * 1024 * 1024;
        final int MAX_SAMPLES = 300;
        ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
        long read = 0;
        try {
            ex.selectTrack(trackIdx);
            for (int s = 0; s < MAX_SAMPLES && read < BYTE_BUDGET; s++) {
                buf.clear();
                int size;
                try {
                    size = ex.readSampleData(buf, 0);
                } catch (Throwable t) {
                    break;
                }
                if (size <= 0) break;
                read += size;
                byte[] b = new byte[size];
                try {
                    buf.get(b);
                } catch (Throwable t) {
                    break;
                }
                if (sampleHasHdr10Plus(b, isHevc)) return true;
                try {
                    ex.advance();
                } catch (Throwable t) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                ex.unselectTrack(trackIdx);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Dispatch on sample framing: Annex-B start codes or AVCC length prefixes. */
    static boolean sampleHasHdr10Plus(byte[] b, boolean isHevc) {
        if (b == null || b.length < 8) return false;
        if (b[0] == 0 && b[1] == 0 && (b[2] == 1 || (b[2] == 0 && b[3] == 1))) {
            return annexBScan(b, isHevc);
        }
        return avccScan(b, isHevc);
    }

    /** Scan Annex-B (start-code delimited) NAL units for an HDR10+ SEI. */
    static boolean annexBScan(byte[] b, boolean isHevc) {
        int i = 0;
        int len = b.length;
        while (i + 4 < len) {
            int sc;
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                sc = 3;
            } else if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) {
                sc = 4;
            } else {
                i++;
                continue;
            }
            int h = i + sc;
            int nalType;
            int hdrLen;
            if (isHevc) {
                if (h + 2 > len) break;
                nalType = (b[h] >> 1) & 0x3F;
                hdrLen = 2;
            } else {
                nalType = b[h] & 0x1F;
                hdrLen = 1;
            }
            boolean isSei = isHevc
                    ? (nalType == HEVC_PREFIX_SEI || nalType == HEVC_SUFFIX_SEI)
                    : (nalType == AVC_SEI);
            int body = h + hdrLen;
            int next = findNextStartCode(b, body);
            if (isSei && seiHasHdr10Plus(b, body, next)) return true;
            i = next;
        }
        return false;
    }

    /** Scan AVCC (4-byte length-prefixed) NAL units for an HDR10+ SEI. */
    static boolean avccScan(byte[] b, boolean isHevc) {
        int pos = 0;
        int len = b.length;
        while (pos + 4 < len) {
            int nalLen = ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                    | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            if (nalLen <= 0 || nalLen > len - pos - 4) break;
            int h = pos + 4;
            int nalType;
            int hdrLen;
            if (isHevc) {
                if (h + 2 > len) break;
                nalType = (b[h] >> 1) & 0x3F;
                hdrLen = 2;
            } else {
                nalType = b[h] & 0x1F;
                hdrLen = 1;
            }
            boolean isSei = isHevc
                    ? (nalType == HEVC_PREFIX_SEI || nalType == HEVC_SUFFIX_SEI)
                    : (nalType == AVC_SEI);
            if (isSei && seiHasHdr10Plus(b, h + hdrLen, pos + 4 + nalLen)) return true;
            pos += 4 + nalLen;
        }
        return false;
    }

    private static int findNextStartCode(byte[] b, int from) {
        int len = b.length;
        for (int i = from; i + 3 < len; i++) {
            if (b[i] == 0 && b[i + 1] == 0
                    && (b[i + 2] == 1 || (b[i + 2] == 0 && b[i + 3] == 1))) {
                return i;
            }
        }
        return len;
    }

    /**
     * Parse SEI messages in one SEI NAL body; true if any carries the
     * HDR10+ user_data_registered_itu_t_t35 marker.
     */
    static boolean seiHasHdr10Plus(byte[] b, int off, int end) {
        int p = off;
        while (p < end) {
            int payloadType = 0;
            while (p < end && (b[p] & 0xFF) == 0xFF) {
                payloadType += 255;
                p++;
            }
            if (p >= end) break;
            payloadType += b[p++] & 0xFF;
            int payloadSize = 0;
            while (p < end && (b[p] & 0xFF) == 0xFF) {
                payloadSize += 255;
                p++;
            }
            if (p >= end) break;
            payloadSize += b[p++] & 0xFF;
            if (payloadType == 4 && p + HDR10PLUS_T35_HEAD.length <= end
                    && p + payloadSize <= end) {
                boolean match = true;
                for (int k = 0; k < HDR10PLUS_T35_HEAD.length; k++) {
                    if ((b[p + k] & 0xFF) != HDR10PLUS_T35_HEAD[k]) {
                        match = false;
                        break;
                    }
                }
                if (match) return true;
            }
            p += payloadSize;
        }
        return false;
    }
}
