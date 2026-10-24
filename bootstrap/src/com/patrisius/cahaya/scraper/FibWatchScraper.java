package com.patrisius.cahaya.scraper;

import com.patrisius.cahaya.addon.StreamItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 3rd-way in-app port of the FibWatch Nuvio scraper (clean-room:
 * written from the flow REA observed, not copied from the JS file).
 *
 * Flow: domain list -> base (fallback fibwatch.art) -> search by
 * title -> best title/year match -> detail page -> video-id ->
 * resolution_switcher -> direct .m3u8/.mp4/.mkv links. Series go
 * through episodes.php to find the SxxEyy page first.
 *
 * Differences from the JS original, all deliberate:
 * - No TMDB call and no TMDB key: Cahaya supplies title/year from
 *   Cinemeta meta, so the TMDB step is skipped entirely.
 * - No Cheerio: HTML is scanned with regexes (fragile by nature —
 *   if the site changes its markup, search returns empty and the
 *   caller just sees no FibWatch streams, never a crash).
 * - Provider headers (Referer) cannot be carried through Cahaya's
 *   StreamItem today, so returned links are played as-is; links
 *   that strictly require a Referer may fail in the player.
 */
public class FibWatchScraper implements Scraper {

    private static final String DEFAULT_BASE = "https://fibwatch.art";
    private static final String DOMAIN_LIST =
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0.0.0 Safari/537.36";

    @Override
    public String name() {
        return "FibWatch";
    }

    private static Map<String, String> headers(String base, String referer,
                                               boolean xhr) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", referer != null ? referer : base + "/");
        h.put("Cookie", "pop_up_18=yes");
        if (xhr) {
            h.put("X-Requested-With", "XMLHttpRequest");
        }
        return h;
    }

    private static String absolute(String base, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        try {
            return new URL(new URL(base), value).toString();
        } catch (Exception e) {
            return value;
        }
    }

    private static String stripTags(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .trim();
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static class SearchResult {
        String url;
        String title;
    }

    // ---- step 1: base domain -------------------------------------------

    @Override
    public void getStreams(final String title, final int year,
                           final String type, final int season,
                           final int episode, final Callback cb) {
        final boolean isSeries = "series".equals(type) && season > 0 && episode > 0;
        ScraperHttp.get(DOMAIN_LIST, headers(DEFAULT_BASE, null, false),
                new ScraperHttp.Callback() {
                    @Override
                    public void onResult(String body) {
                        String base = DEFAULT_BASE;
                        try {
                            String v = new JSONObject(body)
                                    .optString("fibwatch", "");
                            if (!v.isEmpty()) {
                                base = v;
                            }
                        } catch (Exception ignored) {
                        }
                        search(base, title, year, isSeries, season, episode, cb);
                    }

                    @Override
                    public void onError(Exception e) {
                        // Domain list is best-effort; fall back to default.
                        search(DEFAULT_BASE, title, year, isSeries,
                                season, episode, cb);
                    }
                });
    }

    // ---- step 2: search + best match ------------------------------------

    private void search(final String base, final String title, final int year,
                        final boolean isSeries, final int season,
                        final int episode, final Callback cb) {
        String url;
        try {
            url = base + "/search?keyword="
                    + URLEncoder.encode(title, "UTF-8") + "&page_id=1";
        } catch (Exception e) {
            cb.onError(e);
            return;
        }
        ScraperHttp.get(url, headers(base, null, false),
                new ScraperHttp.Callback() {
                    @Override
                    public void onResult(String html) {
                        List<SearchResult> results = parseSearch(html, base);
                        SearchResult best = bestMatch(results, title, year);
                        if (best == null) {
                            cb.onResult(new ArrayList<StreamItem>());
                            return;
                        }
                        if (isSeries) {
                            episodePage(base, best.url, season, episode,
                                    title, cb);
                        } else {
                            extractPageStreams(base, best.url, best.title, cb);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        cb.onError(e);
                    }
                });
    }

    private List<SearchResult> parseSearch(String html, String base) {
        List<SearchResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Find each video-thumb card, then the first link + title
        // within the following chunk (tolerant of inner markup).
        Matcher m = Pattern.compile("video-thumb",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            int start = m.start();
            int end = Math.min(html.length(), start + 3000);
            String chunk = html.substring(start, end);
            Matcher a = Pattern.compile(
                    "<a[^>]+href=\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE).matcher(chunk);
            if (!a.find()) {
                continue;
            }
            String url = absolute(base, a.group(1));
            if (url.isEmpty() || !seen.add(url)) {
                continue;
            }
            String name = "";
            Matcher p = Pattern.compile(
                    "<p[^>]*hptag[^>]*>(.*?)</p>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(chunk);
            if (p.find()) {
                name = stripTags(p.group(1));
            }
            if (name.isEmpty()) {
                Matcher img = Pattern.compile(
                        "<img[^>]+alt=\"([^\"]*)\"",
                        Pattern.CASE_INSENSITIVE).matcher(chunk);
                if (img.find()) {
                    name = stripTags(img.group(1));
                }
            }
            if (name.isEmpty()) {
                continue;
            }
            SearchResult r = new SearchResult();
            r.url = url;
            r.title = name;
            out.add(r);
        }
        return out;
    }

    private SearchResult bestMatch(List<SearchResult> results,
                                   String title, int year) {
        String target = normalize(title);
        SearchResult best = null;
        double bestScore = 0;
        for (SearchResult item : results) {
            String candidate = normalize(item.title);
            double score = candidate.equals(target) ? 2
                    : (candidate.contains(target) || target.contains(candidate)
                            ? 1 : 0);
            Matcher ym = Pattern.compile("\\b(19|20)\\d{2}\\b")
                    .matcher(item.title);
            if (year > 0 && ym.find()) {
                try {
                    int y = Integer.parseInt(ym.group());
                    score += (y == year) ? 0.3 : -0.2;
                } catch (Exception ignored) {
                }
            }
            if (score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return bestScore >= 1 ? best : null;
    }

    // ---- step 3a: series episode page -----------------------------------

    private void episodePage(final String base, final String seriesUrl,
                             final int season, final int episode,
                             final String title, final Callback cb) {
        ScraperHttp.get(seriesUrl, headers(base, null, false),
                new ScraperHttp.Callback() {
                    @Override
                    public void onResult(String html) {
                        String videoId = findVideoId(html);
                        if (videoId.isEmpty()) {
                            cb.onResult(new ArrayList<StreamItem>());
                            return;
                        }
                        String url;
                        try {
                            url = base + "/ajax/episodes.php?video_id="
                                    + URLEncoder.encode(videoId, "UTF-8");
                        } catch (Exception e) {
                            cb.onError(e);
                            return;
                        }
                        final String seriesTitle = title;
                        ScraperHttp.get(url, headers(base, seriesUrl, true),
                                new ScraperHttp.Callback() {
                                    @Override
                                    public void onResult(String body) {
                                        String epUrl = findEpisodeUrl(
                                                body, base, season, episode);
                                        if (epUrl == null || epUrl.isEmpty()) {
                                            cb.onResult(
                                                    new ArrayList<StreamItem>());
                                            return;
                                        }
                                        extractPageStreams(base, epUrl,
                                                seriesTitle + " S" + season
                                                        + "E" + episode, cb);
                                    }

                                    @Override
                                    public void onError(Exception e) {
                                        cb.onError(e);
                                    }
                                });
                    }

                    @Override
                    public void onError(Exception e) {
                        cb.onError(e);
                    }
                });
    }

    private String findEpisodeUrl(String body, String base,
                                  int season, int episode) {
        // JSON form first: { episodes|data|items: [{ display, title, url }] }
        try {
            JSONObject root = new JSONObject(body);
            JSONArray items = null;
            for (String key : new String[]{"episodes", "data", "items"}) {
                if (root.has(key) && root.get(key) instanceof JSONArray) {
                    items = root.getJSONArray(key);
                    break;
                }
            }
            if (items != null) {
                String want = String.format("s%02de%02d", season, episode);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    String text = (it.optString("display", "") + " "
                            + it.optString("title", "")).toLowerCase();
                    if (text.contains(want)) {
                        String u = it.optString("url", "");
                        if (!u.isEmpty()) {
                            return absolute(base, u);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // HTML fallback: links whose text/title carries SxxEyy.
        String want = String.format("s%02de%02d", season, episode);
        Matcher a = Pattern.compile(
                "<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(body);
        while (a.find()) {
            String text = stripTags(a.group(2)).toLowerCase();
            if (text.contains(want)) {
                return absolute(base, a.group(1));
            }
        }
        return null;
    }

    // ---- step 3b: detail page -> direct streams -------------------------

    private String findVideoId(String html) {
        Matcher m1 = Pattern.compile(
                "id=\"video-id\"[^>]*value=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m1.find()) {
            return m1.group(1);
        }
        Matcher m2 = Pattern.compile(
                "value=\"([^\"]+)\"[^>]*id=\"video-id\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m2.find()) {
            return m2.group(1);
        }
        return "";
    }

    private void extractPageStreams(final String base, final String pageUrl,
                                    final String title, final Callback cb) {
        ScraperHttp.get(pageUrl, headers(base, null, false),
                new ScraperHttp.Callback() {
                    @Override
                    public void onResult(String html) {
                        String pageTitle = title;
                        Matcher og = Pattern.compile(
                                "<meta[^>]*property=\"og:title\"[^>]*content=\"([^\"]+)\"",
                                Pattern.CASE_INSENSITIVE).matcher(html);
                        if (og.find() && !og.group(1).isEmpty()) {
                            pageTitle = stripTags(og.group(1));
                        }
                        String videoId = findVideoId(html);
                        if (videoId.isEmpty()) {
                            // Direct VIDEO_URL fallback.
                            List<StreamItem> out = new ArrayList<>();
                            Matcher v = Pattern.compile(
                                    "var\\s+VIDEO_URL\\s*=\\s*[\"']([^\"']+)[\"']",
                                    Pattern.CASE_INSENSITIVE).matcher(html);
                            if (v.find()) {
                                String u = absolute(base, v.group(1));
                                if (u.startsWith("http")) {
                                    out.add(new StreamItem(u,
                                            "FibWatch Auto", base));
                                }
                            }
                            cb.onResult(out);
                            return;
                        }
                        String url;
                        try {
                            url = base + "/ajax/resolution_switcher.php?video_id="
                                    + URLEncoder.encode(videoId, "UTF-8");
                        } catch (Exception e) {
                            cb.onError(e);
                            return;
                        }
                        final String finalTitle = pageTitle;
                        ScraperHttp.get(url, headers(base, pageUrl, true),
                                new ScraperHttp.Callback() {
                                    @Override
                                    public void onResult(String body) {
                                        cb.onResult(parseStreamLinks(
                                                body, base, finalTitle));
                                    }

                                    @Override
                                    public void onError(Exception e) {
                                        cb.onError(e);
                                    }
                                });
                    }

                    @Override
                    public void onError(Exception e) {
                        cb.onError(e);
                    }
                });
    }

    private List<StreamItem> parseStreamLinks(String body, String base,
                                              String title) {
        List<StreamItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> urls = new ArrayList<>();
        boolean jsonOk = false;
        try {
            Object root = body.trim().startsWith("[")
                    ? new JSONArray(body) : new JSONObject(body);
            walkJson(root, urls);
            jsonOk = true;
        } catch (Exception ignored) {
        }
        if (!jsonOk) {
            Matcher m = Pattern.compile(
                    "(https?:)?\\\\?/\\\\?[\"']?([^\\s\"'<>]+\\.(?:m3u8|mp4|mkv)(?:\\?[^\\s\"'<>]*)?)",
                    Pattern.CASE_INSENSITIVE).matcher(body);
            while (m.find()) {
                urls.add(m.group(2).replace("\\/", "/"));
            }
        }
        for (String raw : urls) {
            String u = absolute(base, raw);
            if (!u.startsWith("http") || !seen.add(u)) {
                continue;
            }
            out.add(new StreamItem(u,
                    "FibWatch " + qualityFrom(u), base));
        }
        return out;
    }

    private void walkJson(Object o, List<String> urls) {
        if (o == null) {
            return;
        }
        if (o instanceof String) {
            String s = (String) o;
            if (s.matches("(?i)^(https?:)?//.*\\.(m3u8|mp4|mkv)(\\?.*)?$")) {
                urls.add(s);
            }
            return;
        }
        if (o instanceof JSONArray) {
            JSONArray arr = (JSONArray) o;
            for (int i = 0; i < arr.length(); i++) {
                walkJson(arr.opt(i), urls);
            }
            return;
        }
        if (o instanceof JSONObject) {
            JSONObject obj = (JSONObject) o;
            JSONArray names = obj.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                walkJson(obj.opt(names.optString(i)), urls);
            }
        }
    }

    private String qualityFrom(String url) {
        Matcher m = Pattern.compile("(2160p|1080p|720p|480p|360p|4k)",
                Pattern.CASE_INSENSITIVE).matcher(url);
        return m.find() ? m.group(1) : "Auto";
    }
}
