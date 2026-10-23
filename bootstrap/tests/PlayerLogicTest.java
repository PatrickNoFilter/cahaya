import com.patrisius.cahaya.addon.SubtitleItem;
import com.patrisius.cahaya.ui.player.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-JVM tests for the player's pure logic (no Android, no JUnit needed).
 * Run: ./run_tests.sh    Exit code 0 = all passed.
 */
public class PlayerLogicTest {
    static int passed, failed;

    static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">"); }
    }

    public static void main(String[] a) throws Exception {
        // ---- LangUtil
        eq("en->eng", "eng", LangUtil.canonical("en"));
        eq("EN->eng", "eng", LangUtil.canonical("EN"));
        eq("eng->eng", "eng", LangUtil.canonical("eng"));
        eq("fre->fra (639-2/B)", "fra", LangUtil.canonical("fre"));
        eq("fr->fra", "fra", LangUtil.canonical("fr"));
        eq("ger->deu", "deu", LangUtil.canonical("ger"));
        eq("chi->zho", "zho", LangUtil.canonical("chi"));
        eq("pt-BR->por", "por", LangUtil.canonical("pt-BR"));
        eq("id->ind", "ind", LangUtil.canonical("id"));
        eq("und empty", "", LangUtil.canonical("und"));
        eq("? empty", "", LangUtil.canonical("?"));
        eq("null empty", "", LangUtil.canonical(null));
        eq("matches id/ind", true, LangUtil.matches("id", "ind"));
        eq("matches en/fre false", false, LangUtil.matches("en", "fre"));
        eq("unknown never matches unknown", false, LangUtil.matches("und", "und"));
        eq("display eng", "English", LangUtil.display("eng"));
        eq("display ind", "Indonesian", LangUtil.display("ind"));
        eq("display fre", "French", LangUtil.display("fre"));
        eq("display und", "Unknown", LangUtil.display("und"));
        eq("display junk no crash", true, LangUtil.display("zzz").length() > 0);

        // ---- ScrubRates
        eq("scrub 0", 10_000L, ScrubRates.stepMsForKeyRepeat(0));
        eq("scrub 2", 10_000L, ScrubRates.stepMsForKeyRepeat(2));
        eq("scrub 3", 20_000L, ScrubRates.stepMsForKeyRepeat(3));
        eq("scrub 8", 30_000L, ScrubRates.stepMsForKeyRepeat(8));
        eq("scrub 15", 60_000L, ScrubRates.stepMsForKeyRepeat(15));
        eq("scrub negative clamps", 10_000L, ScrubRates.stepMsForKeyRepeat(-4));
        eq("delta back", -20_000L, ScrubRates.deltaMs(3, false));
        eq("delta fwd", 20_000L, ScrubRates.deltaMs(3, true));

        // ---- TimeFmt
        eq("clock 0", "0:00", TimeFmt.clock(0));
        eq("clock 83", "1:23", TimeFmt.clock(83));
        eq("clock 3600", "1:00:00", TimeFmt.clock(3600));
        eq("clock 5025", "1:23:45", TimeFmt.clock(5025));
        eq("clock neg", "0:00", TimeFmt.clock(-5));
        eq("clock nan", "0:00", TimeFmt.clock(Double.NaN));
        eq("delay 0", "0.0 s", TimeFmt.delay(0));
        eq("delay +1.5", "+1.5 s", TimeFmt.delay(1500));
        eq("delay -0.3", "-0.3 s", TimeFmt.delay(-300));

        // ---- SubtitleRanker
        List<SubtitleItem> in = new ArrayList<>();
        in.add(new SubtitleItem("http://x/1", "fre", "fre · a"));
        in.add(new SubtitleItem("http://x/2", "eng", "eng · a"));
        in.add(new SubtitleItem("http://x/2", "eng", "eng · dup"));   // duplicate URL
        in.add(new SubtitleItem("http://x/3", "ind", "ind · a"));
        in.add(new SubtitleItem("", "eng", "empty url"));
        in.add(new SubtitleItem("http://x/4", "en", "en · b"));
        List<SubtitleItem> r = SubtitleRanker.rank(in, "eng");
        eq("rank size (dupe + empty dropped)", 4, r.size());
        eq("rank preferred first", "http://x/2", r.get(0).url);
        eq("rank preferred keeps order, 2-letter matches", "http://x/4", r.get(1).url);
        eq("rank rest alphabetical (French < Indonesian)", "http://x/1", r.get(2).url);
        eq("bestFor", "http://x/2", SubtitleRanker.bestFor(r, "eng").url);
        eq("bestFor none", null, SubtitleRanker.bestFor(r, "jpn"));

        // ---- SubtitleDecoder
        eq("utf8 plain", "café", SubtitleDecoder.decode("café".getBytes(StandardCharsets.UTF_8), "eng"));
        byte[] bom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "héllo".getBytes(StandardCharsets.UTF_8));
        eq("utf8 BOM stripped", "héllo", SubtitleDecoder.decode(bom, "eng"));
        eq("cp1252 fallback", "café", SubtitleDecoder.decode("café".getBytes(Charset.forName("windows-1252")), "fra"));
        String ar = "مرحبا بالعالم";
        eq("cp1256 arabic by lang", ar, SubtitleDecoder.decode(ar.getBytes(Charset.forName("windows-1256")), "ara"));
        String ru = "Привет мир";
        eq("cp1251 russian by lang", ru, SubtitleDecoder.decode(ru.getBytes(Charset.forName("windows-1251")), "rus"));
        byte[] u16 = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "1\n00:00:01,000 --> 00:00:02,000\nHi".getBytes(StandardCharsets.UTF_16LE));
        eq("utf16le BOM", "1\n00:00:01,000 --> 00:00:02,000\nHi", SubtitleDecoder.decode(u16, "eng"));
        eq("utf16le no BOM", "WEBVTT hello world", SubtitleDecoder.decode("WEBVTT hello world".getBytes(StandardCharsets.UTF_16LE), "eng"));
        eq("empty", "", SubtitleDecoder.decode(new byte[0], "eng"));
        eq("ext vtt", ".vtt", SubtitleDecoder.guessExtension("WEBVTT\n\n00:01.000 --> 00:02.000\nHi"));
        eq("ext ass", ".ass", SubtitleDecoder.guessExtension("[Script Info]\nTitle: x"));
        eq("ext srt", ".srt", SubtitleDecoder.guessExtension("1\n00:00:01,000 --> 00:00:02,000\nHi"));

        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static byte[] concat(byte[] x, byte[] y) {
        byte[] o = new byte[x.length + y.length];
        System.arraycopy(x, 0, o, 0, x.length);
        System.arraycopy(y, 0, o, x.length, y.length);
        return o;
    }
}
