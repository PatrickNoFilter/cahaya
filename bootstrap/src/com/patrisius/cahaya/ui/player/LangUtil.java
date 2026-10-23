package com.patrisius.cahaya.ui.player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Subtitle language helpers. Addons report ISO 639-2 codes ("eng", "fre"),
 * mpv reports whatever the container says ("en", "eng", "und"), and users
 * think in names. Everything is normalised to the ISO 639-2/T 3-letter code.
 * Pure Java (no android.*) so it is unit-testable on a plain JVM.
 */
public final class LangUtil {
    private LangUtil() {}

    /** ISO 639-2/B (bibliographic) codes that java.util.Locale does not know -> /T. */
    private static final Map<String, String> B_TO_T = new HashMap<>();
    static {
        String[] p = {"fre","fra","ger","deu","chi","zho","dut","nld","gre","ell","cze","ces",
                "rum","ron","slo","slk","may","msa","per","fas","alb","sqi","arm","hye","baq","eus",
                "bur","mya","geo","kat","ice","isl","mac","mkd","mao","mri","tib","bod","wel","cym"};
        for (int i = 0; i < p.length; i += 2) B_TO_T.put(p[i], p[i + 1]);
    }

    /** "en" / "EN" / "eng" / "fre" -> canonical 3-letter lowercase; "" for unknown. */
    public static String canonical(String lang) {
        if (lang == null) return "";
        String c = lang.trim().toLowerCase(Locale.ROOT);
        int cut = c.indexOf('-');            // pt-BR -> pt
        if (cut < 0) cut = c.indexOf('_');
        if (cut > 0) c = c.substring(0, cut);
        if (c.isEmpty() || c.equals("?") || c.equals("und") || c.equals("unk")) return "";
        if (B_TO_T.containsKey(c)) c = B_TO_T.get(c);
        try {
            return new Locale(c).getISO3Language();   // 2-letter -> 3-letter; 3-letter passes through
        } catch (Exception e) {
            return c;
        }
    }

    public static boolean matches(String a, String b) {
        String ca = canonical(a), cb = canonical(b);
        return !ca.isEmpty() && ca.equals(cb);
    }

    /** Human name in English: "eng" -> "English". Falls back to the upper-cased code. */
    public static String display(String lang) {
        String c = canonical(lang);
        if (c.isEmpty()) return "Unknown";
        String name = new Locale(c).getDisplayLanguage(Locale.ENGLISH);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(c)) {
            // Some 3-letter codes ("fra", "deu") get no name from Locale; retry via the 2-letter code.
            String two = iso3To2().get(c);
            if (two != null) name = new Locale(two).getDisplayLanguage(Locale.ENGLISH);
        }
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(c)) return c.toUpperCase(Locale.ROOT);
        return name;
    }

    private static Map<String, String> iso3To2;

    private static synchronized Map<String, String> iso3To2() {
        if (iso3To2 == null) {
            Map<String, String> m = new HashMap<>();
            for (String two : Locale.getISOLanguages()) {
                try { m.put(new Locale(two).getISO3Language(), two); } catch (Exception ignored) {}
            }
            iso3To2 = m;
        }
        return iso3To2;
    }
}
