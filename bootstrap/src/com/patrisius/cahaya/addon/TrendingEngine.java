package com.patrisius.cahaya.addon;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Own trending lists (v1.2.0): merged from the keyless sources validated
 * by live crawling on 2026-10-26, instead of any single addon's catalog.
 *
 * Rows: korean, japanese, chinese, hollywood, hollywoodMovies.
 *
 * Scoring: frequency first, then rank (Borda-style rank sum), with a
 * weekly bonus — a title on a weekly/current source outranks an
 * all-time-popular title with the same source count. Score =
 * count * 1000 + weeklyCount * 100 - rankSum.
 *
 * Sources (every fetch is fail-soft: one dead source must never blank
 * a row):
 *  - TMDB addon catalogs (plain JSON): tmdb.trending (series+movie),
 *    tmdb.language genre=Korean / genre=Japanese (all-time popular,
 *    NOT weekly). No TMDB Mandarin: that list is donghua/variety, not
 *    dramas, so it would pollute the Chinese row.
 *  - K-Drama Crush addon catalogs (plain JSON): K-Dramas (Latest),
 *    top_kdramas_2025 (This Week — was empty on 2026-10-26, kept
 *    anyway), J-Dramas (Japanese Latest).
 *  - Douban public JSON (no key): j/search_subjects sort=recommend
 *    for tags 韩剧 (Korean), 日剧 (Japanese), 国产剧 (Chinese).
 *  - Netflix Tudum top-10 pages (server-rendered HTML; titles appear
 *    in chart order inside top10-card-logo img alt text, with a
 *    poster URL in the same tag): south-korea/tv, japan/tv,
 *    hong-kong/tv, taiwan/tv, united-states/tv, united-states/movies.
 *  - IMDb charts (best effort — IMDb bot-blocks some IPs, so a
 *    failure here is normal and silent): moviemeter, tvmeter via
 *    /chart/tv/, and the Korean-drama chart tv/in0000209.
 *  - AniList GraphQL trending anime (best effort — AniList 403s some
 *    datacenter IPs; the box's residential IP may fare better).
 *
 * Deliberately NOT sources in v1: Rotten Tomatoes (its movie page is
 * theatrical listings, not a popularity ranking, and its TV page
 * shows no sort label), MyDramaList top lists (rating-sorted
 * all-time backfill, HTML parsing is fragile — can be added later),
 * iQIYI/Youku/Tencent hot lists (no public API).
 *
 * Title matching: titles are normalized (lowercase, punctuation and
 * spaces stripped, CJK kept, season suffixes stripped, a small alias
 * table for the title variants proven different across sources, e.g.
 * IMDb "Doctor X: Age of the White Mafia" = Netflix "Doctor X:
 * Mafia in White"). A small forced-row table keeps known crossovers
 * in their home row (e.g. Apothecary Diaries is Japanese even when
 * it charts in Korea). Hong Kong / Taiwan Tudum entries only count
 * toward the Chinese row when the same title is also in Douban's
 * domestic list — otherwise Korean/Japanese chart-toppers would
 * pollute the Chinese row.
 *
 * Clicking a trending card does NOT open Details by id: crawled
 * titles carry no Cinemeta id. MainActivity resolves the title
 * through the existing Cinemeta live search instead (see
 * openTrendingItem). Trending item ids are synthetic
 * ("trending_" + canonical key, no colon so heroBaseId() keeps them
 * whole); a failed /meta lookup for them fails silently in the hero.
 *
 * Cache: SharedPreferences "trending_cache", 12 hours. A fresh cache
 * applies instantly and refreshes in the background (hero-refresh
 * pattern); a failed refresh keeps the cache.
 */
public class TrendingEngine {

    public static final String[] ROW_KEYS = {
            "korean", "japanese", "chinese", "hollywood", "hollywoodMovies"
    };
    public static final String[] ROW_TITLES = {
            "Trending Korean", "Trending Japanese", "Trending Chinese",
            "Trending Hollywood", "Trending Hollywood Movies"
    };

    private static final String PREFS = "trending_cache";
    private static final long CACHE_MS = 12L * 3600L * 1000L;
    private static final String TMDB = "https://tmdb.elfhosted.com";
    private static final String KCRUSH = "https://kdrama-binge.vercel.app";
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** One ranked title from one source. */
    private static class Entry {
        String display;
        String poster;
        int sourcePriority;
        Entry(String d, String p, int pri) {
            display = d; poster = p; sourcePriority = pri;
        }
    }

    /** One source list: ordered entries, its default row, weekly flag. */
    private static class SourceList {
        String defaultRow;
        boolean weekly;
        int priority;
        List<Entry> entries = new ArrayList<>();
        SourceList(String row, boolean w, int pri) {
            defaultRow = row; weekly = w; priority = pri;
        }
    }

    /** Accumulator for one merged title inside one row. */
    private static class Acc {
        String canonical;
        String display;
        String poster;
        int displayPriority = 99;
        int count;
        int weeklyCount;
        int rankSum;
        Acc(String c) { canonical = c; }
    }

    // ---------------- normalization ----------------

    /** Normalize for matching: fullwidth-colon prefix kept only for
     *  Douban's "Doctor X：..." style (prefix is the real title);
     *  lowercase; keep letters/digits (CJK included); strip a leading
     *  "the"; strip season suffixes; apply the alias table. */
    static String normalize(String title) {
        if (title == null) return "";
        String t = title.trim();
        int fw = t.indexOf('：');
        if (fw > 0) t = t.substring(0, fw).trim();
        t = t.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        t = sb.toString();
        if (t.startsWith("the") && t.length() > 3) t = t.substring(3);
        t = t.replaceAll("(1st|2nd|3rd|[0-9]+th)?season[0-9]*$", "");
        t = t.replaceAll("limitedseries$", "");
        return alias(t);
    }

    /** Proven title variants that must merge to one canonical key. */
    private static String alias(String norm) {
        if (norm.startsWith("laidoff")) return "laidoffmage";
        switch (norm) {
            case "doctorxageofthewhitemafia":
            case "doctorxmafiainwhite":
                return "doctorx";
            case "kusuriyano":
                return "apothecarydiaries";
            case "hotarunoyomeiri":
                return "fireflywedding";
            case "百日谎言":
            case "100daysoflies":
                return "100daysofdeception";
            case "嫁金钗":
                return "wherethemaskends";
            case "无可替代":
                return "irreplaceable";
            case "余红旧事":
                return "theoldstoryofyuhong";
            case "非自然死亡":
                return "unnatural";
            default:
                return norm;
        }
    }

    /** Canonical key -> home row, for titles that chart outside it. */
    private static String forcedRow(String canonical) {
        switch (canonical) {
            case "doctorx":
            case "takechargeofmyheart":
            case "below":
            case "100daysofdeception":
            case "loveindisguise":
            case "finaltable":
            case "teachyoualesson":
                return "korean";
            case "apothecarydiaries":
            case "fireflywedding":
            case "laidoffmage":
                return "japanese";
            case "wherethemaskends":
            case "irreplaceable":
                return "chinese";
            default:
                return null;
        }
    }

    // ---------------- fetching ----------------

    private static String fetch(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", CHROME_UA);
        c.setRequestProperty("Accept", "*/*");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    /** Stremio addon catalog JSON -> full items (id, type, name, poster). */
    private static List<CatalogItem> fetchCatalogItems(String base, String type,
            String id, String extra) {
        List<CatalogItem> out = new ArrayList<>();
        try {
            String url = base + "/catalog/" + type + "/" + id;
            if (extra != null && !extra.isEmpty()) url += "/" + extra;
            url += ".json";
            JSONObject root = new JSONObject(fetch(url));
            JSONArray metas = root.optJSONArray("metas");
            if (metas == null) return out;
            for (int i = 0; i < metas.length(); i++) {
                JSONObject m = metas.getJSONObject(i);
                String itemId = m.optString("id", "");
                String name = m.optString("name", "");
                if (itemId.isEmpty() || name.isEmpty()) continue;
                out.add(new CatalogItem(itemId, m.optString("type", type),
                        name, m.optString("poster", null)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Stremio addon catalog JSON -> ordered (name, poster) entries. */
    private static List<Entry> addonCatalog(String base, String type,
            String id, String extra, int priority) {
        List<Entry> out = new ArrayList<>();
        for (CatalogItem it : fetchCatalogItems(base, type, id, extra)) {
            out.add(new Entry(it.name, it.poster, priority));
        }
        return out;
    }

    /** Douban hot domestic list for one tag (Chinese tag text). */
    private static List<Entry> douban(String tag, int priority) {
        List<Entry> out = new ArrayList<>();
        try {
            String url = "https://movie.douban.com/j/search_subjects?type=tv&tag="
                    + URLEncoder.encode(tag, "UTF-8")
                    + "&sort=recommend&page_limit=20&page_start=0";
            JSONObject root = new JSONObject(fetch(url));
            JSONArray subs = root.optJSONArray("subjects");
            if (subs == null) return out;
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.getJSONObject(i);
                String name = s.optString("title", "");
                if (name.isEmpty()) continue;
                out.add(new Entry(name, s.optString("cover", null), priority));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final Pattern TUDUM_CARD = Pattern.compile(
            "data-uia=\"top10-card-logo\"[\\s\\S]{0,1200}?src=\"([^\"]+)\"[^>]*alt=\"([^\"]+)\"");

    /** Tudum top-10 page -> titles in chart order, with posters. */
    private static List<Entry> tudum(String region, String kind, int priority) {
        List<Entry> out = new ArrayList<>();
        try {
            String body = fetch("https://www.netflix.com/tudum/top10/"
                    + region + "/" + kind);
            Matcher m = TUDUM_CARD.matcher(body);
            Set<String> seen = new HashSet<>();
            while (m.find() && out.size() < 10) {
                String alt = m.group(2);
                if (alt.endsWith(": Limited Series")) {
                    alt = alt.substring(0, alt.length() - ": Limited Series".length());
                }
                String key = normalize(alt);
                if (key.isEmpty() || !seen.add(key)) continue;
                out.add(new Entry(alt, m.group(1), priority));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final Pattern IMDB_TITLE = Pattern.compile(
            "\"titleText\":\\s*\\{\\s*\"text\":\\s*\"([^\"]+)\"");

    /** IMDb chart page -> titles in chart order (best effort). */
    private static List<Entry> imdb(String path, int priority) {
        List<Entry> out = new ArrayList<>();
        try {
            String body = fetch("https://www.imdb.com/chart/" + path + "/");
            Matcher m = IMDB_TITLE.matcher(body);
            Set<String> seen = new HashSet<>();
            while (m.find() && out.size() < 20) {
                String name = m.group(1);
                String key = normalize(name);
                if (key.isEmpty() || !seen.add(key)) continue;
                out.add(new Entry(name, null, priority));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** AniList trending anime (best effort). */
    private static List<Entry> anilist(int priority) {
        List<Entry> out = new ArrayList<>();
        try {
            String query = "{\"query\": \"query { Page(page: 1, perPage: 20) "
                    + "{ media(type: ANIME, sort: TRENDING_DESC) "
                    + "{ title { romaji english } coverImage { large } } } }\"}";
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://graphql.anilist.co").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", CHROME_UA);
            OutputStream os = c.getOutputStream();
            os.write(query.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return out;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONArray media = new JSONObject(sb.toString())
                    .getJSONObject("data").getJSONObject("Page")
                    .optJSONArray("media");
            if (media == null) return out;
            for (int i = 0; i < media.length(); i++) {
                JSONObject m = media.getJSONObject(i);
                JSONObject t = m.optJSONObject("title");
                if (t == null) continue;
                String name = t.optString("english", "");
                if (name.isEmpty()) name = t.optString("romaji", "");
                if (name.isEmpty()) continue;
                JSONObject cover = m.optJSONObject("coverImage");
                out.add(new Entry(name,
                        cover != null ? cover.optString("large", null) : null,
                        priority));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------------- merge ----------------

    private interface Supplier {
        List<Entry> get();
    }

    private static Map<String, List<CatalogItem>> merge(List<SourceList> sources) {
        // Chinese-row gate: the canonical keys Douban's domestic list
        // actually contains, so HK/TW Tudum entries only count when
        // Douban agrees (see class doc).
        Set<String> doubanChinese = new HashSet<>();
        for (SourceList sl : sources) {
            if (!"chinese".equals(sl.defaultRow)) continue;
            for (Entry e : sl.entries) doubanChinese.add(normalize(e.display));
        }
        Map<String, Map<String, Acc>> rows = new LinkedHashMap<>();
        for (String k : ROW_KEYS) rows.put(k, new LinkedHashMap<>());
        for (SourceList sl : sources) {
            for (int rank = 0; rank < sl.entries.size(); rank++) {
                Entry e = sl.entries.get(rank);
                String key = normalize(e.display);
                if (key.isEmpty()) continue;
                String forced = forcedRow(key);
                String target;
                if (forced != null) {
                    target = forced;
                } else if ("chinese".equals(sl.defaultRow)
                        && sl.priority == 0) {
                    // Tudum HK/TW (priority 0): only with Douban agree.
                    if (!doubanChinese.contains(key)) continue;
                    target = "chinese";
                } else {
                    target = sl.defaultRow;
                }
                Map<String, Acc> row = rows.get(target);
                if (row == null) continue;
                Acc acc = row.get(key);
                if (acc == null) {
                    acc = new Acc(key);
                    row.put(key, acc);
                }
                acc.count++;
                if (sl.weekly) acc.weeklyCount++;
                acc.rankSum += (rank + 1);
                if (sl.priority < acc.displayPriority) {
                    acc.display = e.display;
                    acc.displayPriority = sl.priority;
                    if (e.poster != null && !e.poster.isEmpty()) {
                        acc.poster = e.poster;
                    }
                } else if (acc.poster == null && e.poster != null
                        && !e.poster.isEmpty()) {
                    acc.poster = e.poster;
                }
            }
        }
        Map<String, List<CatalogItem>> out = new LinkedHashMap<>();
        for (String k : ROW_KEYS) {
            List<Acc> accs = new ArrayList<>(rows.get(k).values());
            Collections.sort(accs, new Comparator<Acc>() {
                @Override
                public int compare(Acc a, Acc b) {
                    int sa = a.count * 1000 + a.weeklyCount * 100 - a.rankSum;
                    int sb = b.count * 1000 + b.weeklyCount * 100 - b.rankSum;
                    if (sa != sb) return sb - sa;
                    return a.display.compareToIgnoreCase(b.display);
                }
            });
            List<CatalogItem> items = new ArrayList<>();
            String type = "hollywoodMovies".equals(k) ? "movie" : "series";
            // v1.4.0: keep 60 candidates per row, not 20 — the
            // resolve filter drops unresolvable titles and lower
            // ranks move up to fill the row.
            for (int i = 0; i < accs.size() && i < 60; i++) {
                Acc a = accs.get(i);
                items.add(new CatalogItem("trending_" + a.canonical,
                        type, a.display, a.poster));
            }
            out.put(k, items);
        }
        return out;
    }

    // ---------------- cache + entry point ----------------

    private static Map<String, List<CatalogItem>> loadCache(Context ctx) {
        try {
            String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("rows", null);
            if (json == null) return null;
            JSONObject root = new JSONObject(json);
            long ts = root.optLong("ts", 0);
            if (System.currentTimeMillis() - ts > CACHE_MS) return null;
            Map<String, List<CatalogItem>> out = new LinkedHashMap<>();
            for (String k : ROW_KEYS) {
                List<CatalogItem> items = new ArrayList<>();
                JSONArray arr = root.optJSONArray(k);
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        items.add(new CatalogItem(
                                o.optString("id", ""),
                                o.optString("t", "series"),
                                o.optString("n", "?"),
                                o.optString("p", null)));
                    }
                }
                out.put(k, items);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveCache(Context ctx,
            Map<String, List<CatalogItem>> rows) {
        try {
            JSONObject root = new JSONObject();
            root.put("ts", System.currentTimeMillis());
            for (String k : ROW_KEYS) {
                JSONArray arr = new JSONArray();
                for (CatalogItem it : rows.get(k)) {
                    JSONObject o = new JSONObject();
                    o.put("id", it.id);
                    o.put("t", it.type);
                    o.put("n", it.name);
                    if (it.poster != null) o.put("p", it.poster);
                    arr.put(o);
                }
                root.put(k, arr);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("rows", root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Fetch trending rows (cached-first, background refresh).
     *  Callback runs on the main thread. onError only when there is
     *  no cache and the fetch produced nothing at all. */
    public static void loadRows(final Context ctx,
            final AddonManager.Callback<Map<String, List<CatalogItem>>> cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Map<String, List<CatalogItem>> cached = loadCache(ctx);
        final Runnable fetchTask = new Runnable() {
            @Override
            public void run() {
                Map<String, List<CatalogItem>> rows = fetchAllParallel();
                int total = 0;
                for (List<CatalogItem> l : rows.values()) total += l.size();
                if (total == 0) {
                    if (cached == null) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError(new Exception("no trending data"));
                            }
                        });
                    }
                    return;
                }
                saveCache(ctx, rows);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(rows);
                    }
                });
            }
        };
        if (cached != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    cb.onResult(cached);
                }
            });
            new Thread(fetchTask).start();
        } else {
            new Thread(fetchTask).start();
        }
    }

    /** All source fetches in parallel (8 threads, 75s overall cap);
     *  every failure degrades to an empty list for that source. */
    private static Map<String, List<CatalogItem>> fetchAllParallel() {
        final List<SourceList> sources = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch latch = new CountDownLatch(20);
        // The fetchAll() list is rebuilt here with per-source tasks so
        // a slow source cannot stall the rest.
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(task(sources, latch, "hollywood", true, 1, new Supplier() {
            public List<Entry> get() {
                return addonCatalog(TMDB, "series", "tmdb.trending", null, 1);
            }
        }));
        tasks.add(task(sources, latch, "hollywoodMovies", true, 1, new Supplier() {
            public List<Entry> get() {
                return addonCatalog(TMDB, "movie", "tmdb.trending", null, 1);
            }
        }));
        tasks.add(task(sources, latch, "korean", false, 1, new Supplier() {
            public List<Entry> get() {
                return addonCatalog(TMDB, "series", "tmdb.language", "genre=Korean", 1);
            }
        }));
        tasks.add(task(sources, latch, "japanese", false, 1, new Supplier() {
            public List<Entry> get() {
                return addonCatalog(TMDB, "series", "tmdb.language", "genre=Japanese", 1);
            }
        }));
        tasks.add(task(sources, latch, "korean", true, 2, new Supplier() {
            public List<Entry> get() {
                return addonCatalog(KCRUSH, "series", "K-Dramas", null, 2);
            }
        }));
        tasks.add(task(sources, latch, "korean", true, 2, new Supplier() {
            public List<Entry> get() {
                return addonCatalog(KCRUSH, "series", "top_kdramas_2025", null, 2);
            }
        }));
        tasks.add(task(sources, latch, "japanese", true, 2, new Supplier() {
            public List<Entry> get() {
                return addonCatalog(KCRUSH, "series", "J-Dramas", null, 2);
            }
        }));
        tasks.add(task(sources, latch, "korean", true, 5, new Supplier() {
            public List<Entry> get() { return douban("韩剧", 5); }
        }));
        tasks.add(task(sources, latch, "japanese", true, 5, new Supplier() {
            public List<Entry> get() { return douban("日剧", 5); }
        }));
        tasks.add(task(sources, latch, "chinese", true, 5, new Supplier() {
            public List<Entry> get() { return douban("国产剧", 5); }
        }));
        tasks.add(task(sources, latch, "korean", true, 0, new Supplier() {
            public List<Entry> get() { return tudum("south-korea", "tv", 0); }
        }));
        tasks.add(task(sources, latch, "japanese", true, 0, new Supplier() {
            public List<Entry> get() { return tudum("japan", "tv", 0); }
        }));
        tasks.add(task(sources, latch, "chinese", true, 0, new Supplier() {
            public List<Entry> get() { return tudum("hong-kong", "tv", 0); }
        }));
        tasks.add(task(sources, latch, "chinese", true, 0, new Supplier() {
            public List<Entry> get() { return tudum("taiwan", "tv", 0); }
        }));
        tasks.add(task(sources, latch, "hollywood", true, 0, new Supplier() {
            public List<Entry> get() { return tudum("united-states", "tv", 0); }
        }));
        tasks.add(task(sources, latch, "hollywoodMovies", true, 0, new Supplier() {
            public List<Entry> get() { return tudum("united-states", "movies", 0); }
        }));
        tasks.add(task(sources, latch, "hollywoodMovies", true, 3, new Supplier() {
            public List<Entry> get() { return imdb("moviemeter", 3); }
        }));
        tasks.add(task(sources, latch, "hollywood", true, 3, new Supplier() {
            public List<Entry> get() { return imdb("tv", 3); }
        }));
        tasks.add(task(sources, latch, "korean", true, 3, new Supplier() {
            public List<Entry> get() { return imdb("tv/in0000209", 3); }
        }));
        tasks.add(task(sources, latch, "japanese", true, 4, new Supplier() {
            public List<Entry> get() { return anilist(4); }
        }));
        for (Runnable t : tasks) pool.execute(t);
        try {
            latch.await(75, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        return merge(sources);
    }

    private static Runnable task(final List<SourceList> sources,
            final CountDownLatch latch, final String row,
            final boolean weekly, final int priority, final Supplier s) {
        return new Runnable() {
            @Override
            public void run() {
                SourceList sl = new SourceList(row, weekly, priority);
                try {
                    sl.entries = s.get();
                } catch (Exception ignored) {
                    sl.entries = new ArrayList<>();
                }
                synchronized (sources) {
                    sources.add(sl);
                }
                latch.countDown();
            }
        };
    }

    // ---------------- resolve + filter (v1.4.0) ----------------

    private static final String SYNC_PREFS = "trending_sync";
    /** Canonical keys that resolved nowhere, with the time they were
     *  last tried. Re-tried after NEG_MS so a title added to
     *  Cinemeta/TMDB later can still join the list. */
    private static final String NEG_PREFS = "trending_sync_neg";
    private static final long NEG_MS = 24L * 60 * 60 * 1000;

    /** Clean a display name for a Cinemeta title search (same
     *  stripping MainActivity's click path uses). */
    private static String searchQuery(String display) {
        String q = display;
        q = q.replaceAll(":\\s*Season\\s*\\d+\\s*$", "");
        q = q.replaceAll(":\\s*Limited Series\\s*$", "");
        return q.trim();
    }

    private static CatalogItem loadSyncCache(Context ctx, String canonical) {
        try {
            String json = ctx.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                    .getString(canonical, null);
            if (json == null) return null;
            JSONObject o = new JSONObject(json);
            return new CatalogItem(o.optString("id", ""), o.optString("t", "series"),
                    o.optString("n", "?"), o.optString("p", null));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveSyncCache(Context ctx, String canonical,
            CatalogItem item) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", item.id);
            o.put("t", item.type);
            o.put("n", item.name);
            if (item.poster != null) o.put("p", item.poster);
            ctx.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(canonical, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** A row of crawled candidates, filtered to the titles that
     *  actually resolve (v1.4.0). Rows are emitted in ROW_KEYS order
     *  as each finishes; onDone fires after the last row. Callbacks
     *  run on the main thread. A row keeps at most the first 20
     *  resolvable titles in rank order — unresolvable titles are
     *  dropped and lower-ranked titles move up to fill the row. */
    public interface RowListener {
        void onRow(String key, List<CatalogItem> resolved);
        void onDone();
    }

    public static String titleForKey(String key) {
        for (int i = 0; i < ROW_KEYS.length; i++) {
            if (ROW_KEYS[i].equals(key)) return ROW_TITLES[i];
        }
        return key;
    }

    public static void resolveRows(final Context ctx,
            final Map<String, List<CatalogItem>> rows,
            final RowListener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (String key : ROW_KEYS) {
                    final List<CatalogItem> cands = rows.get(key);
                    if (cands == null) continue;
                    final String type = "hollywoodMovies".equals(key)
                            ? "movie" : "series";
                    final CatalogItem[] out = new CatalogItem[cands.size()];
                    ExecutorService pool = Executors.newFixedThreadPool(8);
                    final CountDownLatch latch =
                            new CountDownLatch(cands.size());
                    for (int i = 0; i < cands.size(); i++) {
                        final int idx = i;
                        final CatalogItem it = cands.get(i);
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    out[idx] = resolveOne(ctx, it, type);
                                } finally {
                                    latch.countDown();
                                }
                            }
                        });
                    }
                    try {
                        latch.await();
                    } catch (InterruptedException ignored) {
                    }
                    pool.shutdown();
                    final List<CatalogItem> resolved = new ArrayList<>();
                    for (CatalogItem r : out) {
                        if (r == null) continue;
                        resolved.add(r);
                        if (resolved.size() >= 20) break;
                    }
                    final String rowKey = key;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onRow(rowKey, resolved);
                        }
                    });
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onDone();
                    }
                });
            }
        }).start();
    }

    /** Resolve one crawled title to a metadata entry with picture
     *  AND info, or null (dropped from the row). Order: cached
     *  resolution, Cinemeta exact, TMDB exact (unambiguous only),
     *  then — only for displays containing CJK — a first-result
     *  search on the raw CJK title (TMDB first, then Cinemeta),
     *  which is how a Chinese-titled entry finds its English title.
     *  Every fresh hit must carry a poster and pass a /meta fetch;
     *  failures are negative-cached for NEG_MS. */
    private static CatalogItem resolveOne(Context ctx, CatalogItem item,
            String type) {
        if (item.id == null || !item.id.startsWith("trending_")) return null;
        String canonical = item.id.substring("trending_".length());
        CatalogItem cached = loadSyncCache(ctx, canonical);
        if (cached != null && !cached.id.isEmpty()
                && cached.poster != null && !cached.poster.isEmpty()) {
            return cached;
        }
        if (negFresh(ctx, canonical)) return null;
        String query = searchQuery(item.name);
        String normDisplay = normalize(item.name);
        CatalogItem found = searchCinemeta(query, canonical, normDisplay, type);
        if (found != null && found.poster != null && !found.poster.isEmpty()
                && metaExists(AddonManager.DEFAULT_ADDON, found.type, found.id)) {
            saveSyncCache(ctx, canonical, found);
            return found;
        }
        found = searchTmdb(query, canonical, normDisplay, type);
        if (found != null && found.poster != null && !found.poster.isEmpty()
                && metaExists(AddonManager.TMDB_ADDON, found.type, found.id)) {
            saveSyncCache(ctx, canonical, found);
            return found;
        }
        if (hasCjk(item.name)) {
            found = firstCjkHit(AddonManager.TMDB_ADDON, "tmdb.search",
                    item.name, type);
            if (found != null
                    && metaExists(AddonManager.TMDB_ADDON, found.type, found.id)) {
                saveSyncCache(ctx, canonical, found);
                return found;
            }
            found = firstCjkHit(AddonManager.DEFAULT_ADDON, "top",
                    item.name, type);
            if (found != null
                    && metaExists(AddonManager.DEFAULT_ADDON, found.type, found.id)) {
                saveSyncCache(ctx, canonical, found);
                return found;
            }
        }
        saveNeg(ctx, canonical);
        return null;
    }

    private static boolean negFresh(Context ctx, String canonical) {
        try {
            long ts = ctx.getSharedPreferences(NEG_PREFS, Context.MODE_PRIVATE)
                    .getLong(canonical, 0);
            return ts > 0 && System.currentTimeMillis() - ts < NEG_MS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void saveNeg(Context ctx, String canonical) {
        try {
            ctx.getSharedPreferences(NEG_PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(canonical, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** True when the addon's /meta endpoint returns a meta object
     *  for this id — the "info" half of the filter. */
    private static boolean metaExists(String base, String type, String id) {
        try {
            JSONObject root = new JSONObject(
                    fetch(base + "/meta/" + type + "/" + id + ".json"));
            return root.optJSONObject("meta") != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(s.charAt(i));
            if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || b == Character.UnicodeBlock.HIRAGANA
                    || b == Character.UnicodeBlock.KATAKANA
                    || b == Character.UnicodeBlock.HANGUL_SYLLABLES) {
                return true;
            }
        }
        return false;
    }

    /** First search result with a usable poster for a raw CJK query,
     *  preferred type first, then the other type. No name matching:
     *  the query itself is the foreign title, and the addon's
     *  relevance ranking is the match. */
    private static CatalogItem firstCjkHit(String base, String catalogId,
            String query, String preferredType) {
        try {
            String extra = "search=" + URLEncoder.encode(query, "UTF-8");
            String other = "series".equals(preferredType) ? "movie" : "series";
            for (String t : new String[]{preferredType, other}) {
                for (CatalogItem it : fetchCatalogItems(base, t, catalogId, extra)) {
                    if (it.poster != null && !it.poster.isEmpty()
                            && !it.poster.endsWith("null")) {
                        return it;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Cinemeta title search, preferred type first, then the other
     *  type. Returns the first result whose normalized name equals
     *  the canonical key or the normalized display name, else null. */
    private static CatalogItem searchCinemeta(String query, String canonical,
            String normalizedDisplay, String preferredType) {
        try {
            String extra = "search=" + URLEncoder.encode(query, "UTF-8");
            List<CatalogItem> first =
                    fetchCatalogItems("https://v3-cinemeta.strem.io",
                            preferredType, "top", extra);
            CatalogItem hit = exactHit(first, canonical, normalizedDisplay);
            if (hit != null) return hit;
            String other = "series".equals(preferredType) ? "movie" : "series";
            List<CatalogItem> second =
                    fetchCatalogItems("https://v3-cinemeta.strem.io",
                            other, "top", extra);
            return exactHit(second, canonical, normalizedDisplay);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static CatalogItem exactHit(List<CatalogItem> results,
            String canonical, String normalizedDisplay) {
        for (CatalogItem it : results) {
            String norm = normalize(it.name);
            if (norm.equals(canonical) || norm.equals(normalizedDisplay)) {
                return it;
            }
        }
        return null;
    }

    /** TMDB-addon alternative (v1.3.1): title search on both types,
     *  preferred first. Returns a hit ONLY when exactly one
     *  exact-name match exists in total — ambiguity means keep the
     *  crawled card. A poster value ending in "null" (the addon's
     *  broken empty-poster marker) is treated as no poster. */
    private static CatalogItem searchTmdb(String query, String canonical,
            String normalizedDisplay, String preferredType) {
        try {
            String extra = "search=" + URLEncoder.encode(query, "UTF-8");
            String other = "series".equals(preferredType) ? "movie" : "series";
            List<CatalogItem> hits = new ArrayList<>();
            for (String t : new String[]{preferredType, other}) {
                for (CatalogItem it : fetchCatalogItems(AddonManager.TMDB_ADDON,
                        t, "tmdb.search", extra)) {
                    String norm = normalize(it.name);
                    if (norm.equals(canonical) || norm.equals(normalizedDisplay)) {
                        if (it.poster != null && it.poster.endsWith("null")) {
                            it = new CatalogItem(it.id, it.type, it.name, null);
                        }
                        hits.add(it);
                    }
                }
            }
            return hits.size() == 1 ? hits.get(0) : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
