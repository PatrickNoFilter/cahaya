/*
 * v1.19.0: JNI bridge to the app's bundled FFmpeg (libavformat/libavcodec)
 * for embedded BITMAP subtitle decoding (PGS, VobSub, DVB, XSUB).
 *
 * The box's MediaExtractor lists these tracks but yields no sample data,
 * and mpv cannot render them on vo=mediacodec_embed — so we decode them
 * here with libavcodec and hand ARGB bitmaps to Java.
 *
 * Links against the libavformat.so / libavcodec.so / libavutil.so already
 * shipped in the APK (same FFmpeg 8.0 build as libmpv).
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/dict.h>
#include <libavutil/avutil.h>

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    int stream_idx;
} Decoder;

static char *jstr_to_utf(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (!c) return NULL;
    char *d = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return d;
}

/* Parse "K: V\nK2: V2" into an AVDictionary for the http protocol. */
static void parse_headers(JNIEnv *env, jstring jheaders, AVDictionary **dict) {
    char *h = jstr_to_utf(env, jheaders);
    if (!h || !*h) { free(h); return; }
    // Build the CRLF-joined header block ffmpeg's http protocol wants.
    size_t cap = strlen(h) * 2 + 64;
    char *block = malloc(cap);
    block[0] = 0;
    char *save = NULL;
    for (char *line = strtok_r(h, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        while (*line == ' ' || *line == '\t') line++;
        if (!*line) continue;
        strncat(block, line, cap - strlen(block) - 1);
        strncat(block, "\r\n", cap - strlen(block) - 1);
    }
    av_dict_set(dict, "headers", block, 0);
    free(block);
    free(h);
}

JNIEXPORT jlong JNICALL
Java_com_patrisius_cahaya_ui_player_FfmpegSubs_nativeOpen(JNIEnv *env, jobject thiz,
                                                          jstring jurl, jstring jheaders) {
    char *url = jstr_to_utf(env, jurl);
    if (!url) return 0;

    Decoder *d = calloc(1, sizeof(Decoder));
    d->stream_idx = -1;

    AVDictionary *opts = NULL;
    parse_headers(env, jheaders, &opts);
    av_dict_set(&opts, "timeout", "8000000", 0);
    av_dict_set(&opts, "analyzeduration", "2000000", 0);
    av_dict_set(&opts, "probesize", "2097152", 0);

    AVFormatContext *fmt = NULL;
    if (avformat_open_input(&fmt, url, NULL, &opts) < 0) {
        av_dict_free(&opts);
        free(url);
        free(d);
        return 0;
    }
    av_dict_free(&opts);
    free(url);

    if (avformat_find_stream_info(fmt, NULL) < 0) {
        avformat_close_input(&fmt);
        free(d);
        return 0;
    }

    d->fmt = fmt;
    return (jlong)(intptr_t)d;
}

JNIEXPORT jstring JNICALL
Java_com_patrisius_cahaya_ui_player_FfmpegSubs_nativeListSubs(JNIEnv *env, jobject thiz, jlong h) {
    Decoder *d = (Decoder *)(intptr_t)h;
    if (!d || !d->fmt) return NULL;

    // Build a small JSON array by hand (no JSON lib in C).
    size_t cap = 1024, len = 0;
    char *out = malloc(cap);
    out[0] = '['; len = 1; out[1] = 0;

    for (unsigned i = 0; i < d->fmt->nb_streams; i++) {
        AVStream *st = d->fmt->streams[i];
        if (!st || !st->codecpar || st->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) continue;
        const AVCodecDescriptor *cd = avcodec_descriptor_get(st->codecpar->codec_id);
        const char *codec = cd ? cd->name : "unknown";
        char lang[16] = "";
        AVDictionaryEntry *e = av_dict_get(st->metadata, "language", NULL, 0);
        if (e && e->value) { strncpy(lang, e->value, sizeof(lang) - 1); }
        char title[128] = "";
        e = av_dict_get(st->metadata, "title", NULL, 0);
        if (e && e->value) { strncpy(title, e->value, sizeof(title) - 1); }
        // JSON-escape quotes in title (minimal).
        char etitle[256];
        size_t k = 0;
        for (size_t q = 0; title[q] && k < sizeof(etitle) - 2; q++) {
            if (title[q] == '"' || title[q] == '\\') etitle[k++] = '\\';
            etitle[k++] = title[q];
        }
        etitle[k] = 0;

        char item[512];
        int il = snprintf(item, sizeof(item),
                "%s{\"index\":%u,\"codec\":\"%s\",\"lang\":\"%s\",\"title\":\"%s\"}",
                len > 1 ? "," : "", i, codec, lang, etitle);
        if (len + il + 2 > cap) {
            cap = (len + il + 2) * 2;
            out = realloc(out, cap);
        }
        strcpy(out + len, item);
        len += il;
    }
    strcpy(out + len, "]");
    jstring r = (*env)->NewStringUTF(env, out);
    free(out);
    return r;
}

JNIEXPORT jboolean JNICALL
Java_com_patrisius_cahaya_ui_player_FfmpegSubs_nativeSelect(JNIEnv *env, jobject thiz,
                                                            jlong h, jint stream_idx) {
    Decoder *d = (Decoder *)(intptr_t)h;
    if (!d || !d->fmt || stream_idx < 0 || (unsigned)stream_idx >= d->fmt->nb_streams) return JNI_FALSE;

    AVStream *st = d->fmt->streams[stream_idx];
    if (!st || !st->codecpar || st->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) return JNI_FALSE;

    const AVCodec *codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) return JNI_FALSE;

    AVCodecContext *dec = avcodec_alloc_context3(codec);
    if (!dec) return JNI_FALSE;
    if (avcodec_parameters_to_context(dec, st->codecpar) < 0) {
        avcodec_free_context(&dec);
        return JNI_FALSE;
    }
    if (avcodec_open2(dec, codec, NULL) < 0) {
        avcodec_free_context(&dec);
        return JNI_FALSE;
    }

    if (d->dec) avcodec_free_context(&d->dec);
    d->dec = dec;
    d->stream_idx = stream_idx;
    return JNI_TRUE;
}

/* Composite all bitmap rects of one AVSubtitle into a single ARGB canvas. */
static int composite_bitmap(AVSubtitle *sub, int **out_argb, int *out_w, int *out_h,
                            int *out_x, int *out_y) {
    int x0 = INT32_MAX, y0 = INT32_MAX, x1 = 0, y1 = 0, n = 0;
    for (int i = 0; i < sub->num_rects; i++) {
        AVSubtitleRect *r = sub->rects[i];
        if (!r || r->type != SUBTITLE_BITMAP || !r->data[0] || r->w <= 0 || r->h <= 0) continue;
        if (r->x < x0) x0 = r->x;
        if (r->y < y0) y0 = r->y;
        if (r->x + r->w > x1) x1 = r->x + r->w;
        if (r->y + r->h > y1) y1 = r->y + r->h;
        n++;
    }
    if (!n) return 0;
    int w = x1 - x0, h = y1 - y0;
    if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return 0;

    int *argb = calloc((size_t)w * h, 4);
    if (!argb) return 0;

    for (int i = 0; i < sub->num_rects; i++) {
        AVSubtitleRect *r = sub->rects[i];
        if (!r || r->type != SUBTITLE_BITMAP || !r->data[0] || r->w <= 0 || r->h <= 0) continue;
        uint8_t *idx = r->data[0];
        uint32_t *pal = (uint32_t *)r->data[1];  // RGBA per ff_pgssub (stored as ARGB int)
        int stride = r->linesize[0] ? r->linesize[0] : r->w;
        for (int y = 0; y < r->h; y++) {
            for (int x = 0; x < r->w; x++) {
                uint8_t pi = idx[y * stride + x];
                uint32_t c = pal ? pal[pi] : 0;
                // Palette is RGBA(r,g,b,a) packed as (a<<24)|(r<<16)|(g<<8)|b — already ARGB order.
                uint8_t a = (c >> 24) & 0xFF;
                if (!a) continue;  // transparent: keep canvas
                int dx = r->x - x0 + x, dy = r->y - y0 + y;
                argb[dy * w + dx] = (int)c;
            }
        }
    }
    *out_argb = argb;
    *out_w = w; *out_h = h; *out_x = x0; *out_y = y0;
    return 1;
}

JNIEXPORT jobject JNICALL
Java_com_patrisius_cahaya_ui_player_FfmpegSubs_nativeNext(JNIEnv *env, jobject thiz, jlong h) {
    Decoder *d = (Decoder *)(intptr_t)h;
    if (!d || !d->fmt || !d->dec || d->stream_idx < 0) return NULL;

    AVPacket *pkt = av_packet_alloc();
    AVSubtitle sub;
    jobject result = NULL;

    // Read packets until we get a decodable subtitle from our stream.
    for (int tries = 0; tries < 2000; tries++) {
        int rc = av_read_frame(d->fmt, pkt);
        if (rc < 0) break;  // EOF or error
        if (pkt->stream_index != d->stream_idx) {
            av_packet_unref(pkt);
            continue;
        }
        int got = 0;
        memset(&sub, 0, sizeof(sub));
        if (avcodec_decode_subtitle2(d->dec, &sub, &got, pkt) < 0) {
            av_packet_unref(pkt);
            continue;
        }
        av_packet_unref(pkt);
        if (!got || sub.num_rects <= 0) {
            avsubtitle_free(&sub);
            continue;
        }

        // Timing: pts (AV_TIME_BASE us) + start/end display time (ms).
        // NOTE: use int64_t, not long (long is 32-bit on armeabi-v7a).
        int64_t baseMs = sub.pts != AV_NOPTS_VALUE ? sub.pts / 1000 : 0;
        int64_t startMs = baseMs + (int64_t)sub.start_display_time;
        int64_t endMs = sub.end_display_time >= UINT32_MAX / 2
                ? (int64_t)9000000000000LL : baseMs + (int64_t)sub.end_display_time;

        // Look for bitmap rects first.
        int has_bitmap = 0;
        for (int i = 0; i < sub.num_rects; i++)
            if (sub.rects[i] && sub.rects[i]->type == SUBTITLE_BITMAP) { has_bitmap = 1; break; }

        jclass cls = (*env)->FindClass(env, "com/patrisius/cahaya/ui/player/SubtitleEvent");
        if (!cls) { avsubtitle_free(&sub); break; }

        if (has_bitmap) {
            int *argb = NULL, w = 0, hh = 0, x = 0, y = 0;
            if (composite_bitmap(&sub, &argb, &w, &hh, &x, &y)) {
                jintArray jargb = (*env)->NewIntArray(env, (jsize)((size_t)w * hh));
                (*env)->SetIntArrayRegion(env, jargb, 0, (jsize)((size_t)w * hh), (jint *)argb);
                free(argb);
                jmethodID bctor = (*env)->GetMethodID(env, cls, "<init>", "(JJIIII[I)V");
                result = (*env)->NewObject(env, cls, bctor,
                        (jlong)startMs, (jlong)endMs, (jint)x, (jint)y, (jint)w, (jint)hh, jargb);
            }
        } else {
            // Text/ASS rect: concatenate.
            size_t cap = 256, len = 0;
            char *txt = malloc(cap);
            txt[0] = 0;
            for (int i = 0; i < sub.num_rects; i++) {
                AVSubtitleRect *r = sub.rects[i];
                const char *s = NULL;
                if (r->type == SUBTITLE_TEXT && r->text) s = r->text;
                else if (r->type == SUBTITLE_ASS && r->ass) s = r->ass;
                if (!s || !*s) continue;
                size_t sl = strlen(s);
                if (len + sl + 2 > cap) { cap = (len + sl + 2) * 2; txt = realloc(txt, cap); }
                if (len) { txt[len++] = '\n'; txt[len] = 0; }
                strcpy(txt + len, s);
                len += sl;
            }
            if (len) {
                jstring jtxt = (*env)->NewStringUTF(env, txt);
                jmethodID tctor = (*env)->GetMethodID(env, cls, "<init>", "(JJLjava/lang/String;)V");
                result = (*env)->NewObject(env, cls, tctor,
                        (jlong)startMs, (jlong)endMs, jtxt);
            }
            free(txt);
        }
        avsubtitle_free(&sub);
        break;
    }

    av_packet_free(&pkt);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_patrisius_cahaya_ui_player_FfmpegSubs_nativeSeek(JNIEnv *env, jobject thiz,
                                                          jlong h, jlong ms) {
    Decoder *d = (Decoder *)(intptr_t)h;
    if (!d || !d->fmt || !d->dec || d->stream_idx < 0) return JNI_FALSE;
    int64_t ts = ms * 1000;  // ms -> us (AV_TIME_BASE)
    int rc = av_seek_frame(d->fmt, -1, ts, AVSEEK_FLAG_BACKWARD);
    if (rc < 0) {
        // Fallback: seek on the subtitle stream's own time base.
        AVStream *st = d->fmt->streams[d->stream_idx];
        int64_t st_ts = av_rescale_q(ts, AV_TIME_BASE_Q, st->time_base);
        rc = av_seek_frame(d->fmt, d->stream_idx, st_ts, AVSEEK_FLAG_BACKWARD);
    }
    if (rc >= 0) avcodec_flush_buffers(d->dec);
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_patrisius_cahaya_ui_player_FfmpegSubs_nativeClose(JNIEnv *env, jobject thiz, jlong h) {
    Decoder *d = (Decoder *)(intptr_t)h;
    if (!d) return;
    if (d->dec) avcodec_free_context(&d->dec);
    if (d->fmt) avformat_close_input(&d->fmt);
    free(d);
}
