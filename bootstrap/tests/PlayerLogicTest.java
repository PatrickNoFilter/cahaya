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


        // ---- CueParser: SRT
        String srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i> &amp; welcome\r\n\r\n"
                + "2\r\n00:00:04,000 --> 00:00:06,000\r\n{\\an8}Top line\r\nSecond line\r\n\r\n"
                + "3\r\n00:01:02,5 --> 00:01:04,000\r\nShort fraction\r\n";
        List<SubtitleCue> c = CueParser.parse(srt);
        eq("srt count", 3, c.size());
        eq("srt start ms", 1000L, c.get(0).startMs);
        eq("srt end ms", 3500L, c.get(0).endMs);
        eq("srt html + entity cleaned", "Hello & welcome", c.get(0).text);
        eq("srt brace tag stripped, 2 lines kept", "Top line\nSecond line", c.get(1).text);
        eq("srt 1-digit fraction = 500ms", 62500L, c.get(2).startMs);
        eq("textAt inside", "Hello & welcome", CueParser.textAt(c, 2000, false));
        eq("textAt start inclusive", "Hello & welcome", CueParser.textAt(c, 1000, false));
        eq("textAt end exclusive", "", CueParser.textAt(c, 3500, false));
        eq("textAt gap", "", CueParser.textAt(c, 3700, false));
        eq("textAt before first", "", CueParser.textAt(c, 500, false));
        eq("textAt after last", "", CueParser.textAt(c, 999999, false));
        eq("textAt negative", "", CueParser.textAt(c, -5, false));
        eq("textAt null list", "", CueParser.textAt(null, 100, false));
        eq("srt missing blank line between cues",
                2, CueParser.parse("1\n00:00:01,000 --> 00:00:02,000\nA\n2\n00:00:03,000 --> 00:00:04,000\nB\n").size());
        eq("srt garbage -> empty, not null", 0, CueParser.parse("not a subtitle file").size());
        eq("null -> empty", 0, CueParser.parse(null).size());

        // ---- CueParser: overlapping cues are joined, order independent of file order
        List<SubtitleCue> ov = CueParser.parse("1\n00:00:05,000 --> 00:00:09,000\nB\n\n2\n00:00:01,000 --> 00:00:07,000\nA\n");
        eq("sorted by start", 1000L, ov.get(0).startMs);
        eq("overlap joined", "A\nB", CueParser.textAt(ov, 6000, false));
        eq("overlap tail", "B", CueParser.textAt(ov, 8000, false));

        // ---- CueParser: VTT
        String vtt = "WEBVTT\n\nNOTE a comment\n\nintro\n00:01.000 --> 00:02.500 align:start\n<v Bob>Hi <c.yellow>there</c>\n\n"
                + "01:00:00.000 --> 01:00:01.000\n<00:00:00.500>Late\n";
        List<SubtitleCue> v = CueParser.parse(vtt);
        eq("vtt count", 2, v.size());
        eq("vtt mm:ss start", 1000L, v.get(0).startMs);
        eq("vtt cue settings ignored, tags stripped", "Hi there", v.get(0).text);
        eq("vtt hh:mm:ss", 3_600_000L, v.get(1).startMs);
        eq("vtt inline timestamp stripped", "Late", v.get(1).text);

        // ---- CueParser: ASS
        String ass = "[Script Info]\nTitle: x\n\n[V4+ Styles]\nFormat: Name\nStyle: Default\n\n[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Hello, world{\\i0}\\Nline two\n"
                + "Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\\p1}m 0 0 l 10 10{\\p0}\n"
                + "Comment: 0,0:00:07.00,0:00:08.00,Default,,0,0,0,,ignored\n";
        List<SubtitleCue> ac = CueParser.parse(ass);
        eq("ass count (drawing + comment skipped)", 1, ac.size());
        eq("ass start cs->ms", 1500L, ac.get(0).startMs);
        eq("ass commas in text kept, tags stripped, \\N newline", "Hello, world\nline two", ac.get(0).text);

        // ---- SDH filter
        eq("sdh brackets", "Hello", CueParser.stripSdh("[door slams] Hello"));
        eq("sdh paren + speaker", "Run!", CueParser.stripSdh("JOHN: (panting) Run!"));
        eq("sdh music line dropped", "Real line", CueParser.stripSdh("♪ la la ♪\nReal line"));
        eq("sdh all removed -> empty", "", CueParser.stripSdh("[music]"));
        eq("sdh leaves normal text", "Hello there", CueParser.stripSdh("Hello there"));
        List<SubtitleCue> sd = CueParser.parse("1\n00:00:01,000 --> 00:00:02,000\n[music]\n");
        eq("textAt strip -> empty", "", CueParser.textAt(sd, 1500, true));
        eq("textAt no strip keeps", "[music]", CueParser.textAt(sd, 1500, false));

        // ---- SubtitleSelection (drives the tick)
        eq("off is off", true, SubtitleSelection.OFF.isOff());
        eq("embedded match", true, SubtitleSelection.embedded(3).isEmbedded(3));
        eq("embedded other id", false, SubtitleSelection.embedded(3).isEmbedded(4));
        eq("embedded is not off", false, SubtitleSelection.embedded(3).isOff());
        eq("embedded never matches online", false, SubtitleSelection.embedded(3).isOnline("http://x/1"));
        eq("online match", true, SubtitleSelection.online("http://x/1").isOnline("http://x/1"));
        eq("online other url", false, SubtitleSelection.online("http://x/1").isOnline("http://x/2"));
        eq("online never matches embedded id", false, SubtitleSelection.online("http://x/1").isEmbedded(1));
        eq("off matches nothing", false, SubtitleSelection.OFF.isEmbedded(0) || SubtitleSelection.OFF.isOnline("http://x/1"));

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
