package com.patrisius.cahaya.ui.player;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns raw subtitle bytes into a Java String regardless of how the file was
 * encoded. Addon subtitles are frequently Windows-1252 / 1256 / 1251 rather
 * than UTF-8, which mpv would render as mojibake. Strategy (cheap version of
 * NuvioTV's SubtitleCharsetDetector): BOMs, then UTF-16 without BOM, then
 * strict UTF-8, then a legacy code page chosen from the subtitle's language.
 * Pure Java, unit-tested.
 */
public final class SubtitleDecoder {
    private SubtitleDecoder() {}

    public static String decode(byte[] b, String lang) {
        if (b == null || b.length == 0) return "";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF)
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        Charset u16 = guessUtf16WithoutBom(b);
        if (u16 != null) return new String(b, u16);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(b, fallbackCharset(lang));
        }
    }

    /** Mostly-ASCII UTF-16 has a NUL in every other byte. */
    private static Charset guessUtf16WithoutBom(byte[] b) {
        int n = Math.min(b.length, 400), even = 0, odd = 0;
        for (int i = 0; i < n; i++) if (b[i] == 0) { if ((i & 1) == 0) even++; else odd++; }
        if (n >= 8 && odd > n / 4 && even == 0) return StandardCharsets.UTF_16LE;
        if (n >= 8 && even > n / 4 && odd == 0) return StandardCharsets.UTF_16BE;
        return null;
    }

    /** Legacy code page most likely for a subtitle in this language. */
    public static Charset fallbackCharset(String lang) {
        String c = LangUtil.canonical(lang).toLowerCase(Locale.ROOT);
        String name;
        switch (c) {
            case "ara": case "fas": case "urd": name = "windows-1256"; break;
            case "heb": name = "windows-1255"; break;
            case "rus": case "ukr": case "bul": case "srp": case "bel": case "mkd": name = "windows-1251"; break;
            case "ell": name = "windows-1253"; break;
            case "tur": case "aze": name = "windows-1254"; break;
            case "pol": case "ces": case "slk": case "hun": case "ron": case "hrv": case "slv": case "sqi":
                name = "windows-1250"; break;
            case "tha": name = "windows-874"; break;
            case "vie": name = "windows-1258"; break;
            case "zho": name = "GB18030"; break;
            case "jpn": name = "Shift_JIS"; break;
            case "kor": name = "EUC-KR"; break;
            default: name = "windows-1252";
        }
        try { return Charset.forName(name); } catch (Exception e) { return Charset.forName("ISO-8859-1"); }
    }

    /** File extension for the content so mpv picks the right demuxer even when the URL has none. */
    public static String guessExtension(String text) {
        String head = text.length() > 600 ? text.substring(0, 600) : text;
        String t = head.trim();
        if (t.startsWith("WEBVTT")) return ".vtt";
        if (head.contains("[Script Info]") || head.contains("[V4+ Styles]")) return ".ass";
        return ".srt";
    }
}
