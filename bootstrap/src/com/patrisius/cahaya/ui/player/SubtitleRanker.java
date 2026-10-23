package com.patrisius.cahaya.ui.player;

import com.patrisius.cahaya.addon.SubtitleItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Orders addon subtitles: preferred language first, then alphabetical by language, stable within. */
public final class SubtitleRanker {
    private SubtitleRanker() {}

    public static List<SubtitleItem> rank(List<SubtitleItem> in, final String preferredLang) {
        List<SubtitleItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SubtitleItem s : in) {                       // de-dupe by URL, keep first
            if (s.url != null && !s.url.isEmpty() && seen.add(s.url)) out.add(s);
        }
        Collections.sort(out, new Comparator<SubtitleItem>() {   // List.sort is stable
            @Override public int compare(SubtitleItem a, SubtitleItem b) {
                boolean pa = LangUtil.matches(a.lang, preferredLang);
                boolean pb = LangUtil.matches(b.lang, preferredLang);
                if (pa != pb) return pa ? -1 : 1;
                return LangUtil.display(a.lang).compareToIgnoreCase(LangUtil.display(b.lang));
            }
        });
        return out;
    }

    /** First subtitle in the preferred language, or null. Input should already be ranked. */
    public static SubtitleItem bestFor(List<SubtitleItem> ranked, String preferredLang) {
        for (SubtitleItem s : ranked) if (LangUtil.matches(s.lang, preferredLang)) return s;
        return null;
    }
}
