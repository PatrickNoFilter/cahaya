package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.patrisius.cahaya.addon.StreamItem;
import com.patrisius.cahaya.jsrunner.JsScraperRunner;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stream source picker (v1.5.1): streams grouped into COLUMNS, one
 * column per provider, in priority order — MovieBox first
 * (phone + TV backends merged into one column in v1.37.1), then
 * VidRock, DesiFlix, 4khdhub, Torrentio, PlayIMDb, then every
 * other Nuvio provider in its own column, then Other. Streams
 * from any other source (e.g. FibWatch) go in a trailing "Other"
 * column, only when present, so nothing playable silently
 * disappears. Shared by Details Play, the player's Next button,
 * and Playing History clicks.
 * D-pad: Up/Down moves within a column, Left/Right moves between
 * columns (same row where possible).
 * v1.40.0: center-locked carousel (TV). The focused column is
 * kept centered in the strip — pressing Right again scrolls the
 * strip left so the next column comes to the center, instead of
 * the cursor walking to the edge. Same vertically: the focused
 * option is kept centered in its column, the list scrolls under
 * it. Columns are a fixed width and the strip scrolls
 * horizontally inside a HorizontalScrollView, so any number of
 * providers fits (no more "More" bucket).
 * v1.41.0: nicer UI — the focused link gets a soft translucent
 * accent pill behind it (plus the blue text), and the focused
 * column's header turns accent blue with a thin underline bar,
 * so the active provider reads at a glance. Headers are bold.
 * v1.43.0: progressive (live) picker. The picker no longer waits
 * for every provider: the caller shows it as soon as the priority
 * providers are back, and late providers are appended live via
 * addStreams() — their column slides into the strip while the
 * picker is open. addStreams() is a no-op once the picker is
 * dismissed, so late results after a pick are dropped safely.
 * v1.5.2: options are words only (plain TextViews, no button
 * rectangles) — the focused word turns blue instead of a box
 * highlight, so selection stays visible and the list stays light.
 * v1.5.4: uniform options — every option is exactly two lines
 * (title + info), the same height and spacing in every column.
 * The old single-TextView label let a long filename wrap onto
 * both lines and swallow its info line, so columns came out
 * ragged with different heights (seen in the 1.5.3 screenshot).
 * v1.12.0: the title shows 2 lines instead of 1 (the whole link
 * text, not an ellipsis stump). When a focused title is longer
 * than 2 lines, its hidden text scrolls up automatically so the
 * full link reads without leaving the picker. Options stay a
 * uniform 3 lines (2 title + 1 info) when unfocused.
 */
public class StreamPicker {

    public interface PickListener {
        void onPick(StreamItem s);
    }

    private static class Column {
        final String header;
        final List<StreamItem> items = new ArrayList<>();

        Column(String header) {
            this.header = header;
        }
    }

    /** v1.40.0: fixed column width for the TV carousel. Three
     *  columns fit across the 95%-wide dialog; the focused one
     *  sits centered with its neighbours peeking on both sides. */
    private static final int COL_WIDTH_DP = 300;
    /** v1.40.0: height of a column's vertical list on TV. */
    private static final int COL_HEIGHT_DP = 480;
    /** v1.41.0: focused-option pill — a soft translucent accent
     *  background behind the focused link, so the cursor reads
     *  at a glance on a TV. */
    private static android.graphics.drawable.GradientDrawable focusPill() {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.parseColor("#332196F3"));
        d.setCornerRadius(24);
        return d;
    }

    /** v1.37.2: order inside a column — MovieBox (phone backend)
     *  before MovieBox TV, so the main provider sits at the top of
     *  the merged MovieBox column. Anything else keeps its place. */
    private static int providerRank(StreamItem s) {
        String src = s.source == null ? "" : s.source;
        if ("moviebox".equals(src)) return 0;
        if ("movieboxtv".equals(src)) return 1;
        return 2;
    }

    /** v1.40.0: group key for one column. MovieBox phone + TV
     *  merge into one column; Torrentio (addon URL) gets its own;
     *  every Nuvio provider gets its own column; anything else
     *  falls into "other". */
    private static String groupKey(String src) {
        if (src == null) return "other";
        if ("moviebox".equals(src) || "movieboxtv".equals(src)) return "moviebox";
        if (src.contains("torrentio")) return "torrentio";
        if (JsScraperRunner.isNuvioProvider(src)) return src;
        return "other";
    }

    /** v1.40.0: column header for a group key. */
    private static String displayName(String key) {
        if ("moviebox".equals(key)) return "MovieBox";
        if ("torrentio".equals(key)) return "Torrentio";
        if ("other".equals(key)) return "Other";
        if ("vidrock".equals(key)) return "VidRock";
        if ("desiflix".equals(key)) return "DesiFlix";
        if ("4khdhub".equals(key)) return "4khdhub";
        if ("playimdb".equals(key)) return "PlayIMDb";
        return key.substring(0, 1).toUpperCase() + key.substring(1);
    }

    /** v1.43.0: state of the currently open picker, so late
     *  providers can append their column live. Cleared on
     *  dismiss; addStreams() is a no-op when there is none. */
    private static class LivePicker {
        Activity activity;
        AlertDialog dlg;
        LinearLayout columnsRow;
        HorizontalScrollView hsv; // null on phone
        View loadingFooter; // v1.43.1: spinner + "Finding more streams…"
        List<TextView> colHeaders = new ArrayList<>();
        List<View> colUnderlines = new ArrayList<>();
        List<List<View>> colButtons = new ArrayList<>();
        Map<String, ColRef> colsByKey = new LinkedHashMap<>();
        Set<String> shownKeys = new HashSet<>();
        android.os.Handler scrollHandler;
        List<Runnable> activeScrolls = new ArrayList<>();
        PickListener listener;
        String highlightUrl;
        String highlightLabel;
        boolean phone;
        int gapPx;
    }

    /** v1.43.0: one column's live views, so late streams from the
     *  same provider append into it instead of duplicating it. */
    private static class ColRef {
        LinearLayout col;
        LinearLayout list;
        List<View> buttons = new ArrayList<>();
        ScrollView sv; // null on phone
        TextView header;
        View underline;
        int idx; // position in colButtons (for header highlight + centering)
    }

    private static LivePicker current;

    /** v1.43.0: true while a picker is open on screen. */
    public static boolean isOpen() {
        return current != null && current.dlg != null && current.dlg.isShowing();
    }

    /** v1.43.1: show or hide the loading indicator on the open
     *  picker ("Loading stream while you can choose it", spinner
     *  before the text, at the top). The caller shows it when the
     *  picker opens early (providers still running) and hides it
     *  when every provider has finished. No-op when no picker is
     *  open. Must be called on the main thread. */
    public static void setLoading(boolean loading) {
        LivePicker p = current;
        if (p == null || p.loadingFooter == null) return;
        p.loadingFooter.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** v1.43.0: append streams to the open picker, live. New
     *  providers get a fresh column (in priority order, appended
     *  at the strip's end); a provider that already has a column
     *  appends its links into it. Safe to call when no picker is
     *  open (no-op) and after a pick/dismiss (dropped). Must be
     *  called on the main thread. */
    public static void addStreams(List<StreamItem> items) {
        LivePicker p = current;
        if (p == null || p.dlg == null || !p.dlg.isShowing()) return;
        if (items == null || items.isEmpty()) return;
        if (p.activity == null || p.activity.isFinishing()) return;
        Map<String, List<StreamItem>> byKey = group(items);
        // Priority order first (ALL_NUVIO_PROVIDERS is already
        // priority-first), then Torrentio, then Other.
        List<String> order = new ArrayList<>();
        for (String prov : JsScraperRunner.ALL_NUVIO_PROVIDERS) {
            String k = groupKey(prov);
            if (!order.contains(k)) order.add(k);
        }
        if (byKey.containsKey("torrentio") && !order.contains("torrentio")) {
            order.add("torrentio");
        }
        if (byKey.containsKey("other") && !order.contains("other")) {
            order.add("other");
        }
        for (String k : order) {
            List<StreamItem> list = byKey.get(k);
            if (list == null || list.isEmpty()) continue;
            sortColumn(list);
            ColRef ref = p.colsByKey.get(k);
            if (ref == null) {
                Column c = new Column(displayName(k));
                c.items.addAll(list);
                buildColumn(p, c, k);
            } else {
                for (StreamItem s : list) {
                    View opt = buildOption(p, s, ref.idx);
                    ref.list.addView(opt);
                    ref.buttons.add(opt);
                }
            }
        }
        wireFocusNavigation(p);
    }

    /** Group a batch of streams by their column key. */
    private static Map<String, List<StreamItem>> group(List<StreamItem> streams) {
        Map<String, List<StreamItem>> byKey = new LinkedHashMap<>();
        for (StreamItem s : streams) {
            String k = groupKey(s.source);
            List<StreamItem> list = byKey.get(k);
            if (list == null) {
                list = new ArrayList<>();
                byKey.put(k, list);
            }
            list.add(s);
        }
        return byKey;
    }

    /** Seeders-desc within a column (stable: scrapers keep order);
     *  MovieBox phone-backend streams come first in the merged
     *  MovieBox column. */
    private static void sortColumn(List<StreamItem> items) {
        Collections.sort(items, (a, b) -> {
            int ra = providerRank(a), rb = providerRank(b);
            if (ra != rb) return Integer.compare(ra, rb);
            return Integer.compare(b.seeders, a.seeders);
        });
    }

    public static void show(final Activity activity, String title,
                            List<StreamItem> streams, final PickListener listener) {
        show(activity, title, streams, listener, null, null);
    }

    /** v1.24.0 (WuPlay idea): tag one stream — the one playing now
     *  ("Playing") or the one played last for this title ("Last
     *  played"). The tag is appended to the option title. */
    private static String tagTitle(StreamItem s, String hlUrl, String hlLabel) {
        if (hlUrl != null && hlLabel != null && hlUrl.equals(s.url)) {
            return s.title + "  \u2022  " + hlLabel;
        }
        return s.title;
    }

    public static void show(final Activity activity, String title,
                            List<StreamItem> streams, final PickListener listener,
                            String highlightUrl, String highlightLabel) {
        // v1.40.0: one column per provider, in priority order.
        // ALL_NUVIO_PROVIDERS is already priority-first (MovieBox,
        // VidRock, DesiFlix, 4khdhub, PlayIMDb, then the rest);
        // Torrentio follows, then Other.
        Map<String, List<StreamItem>> byKey = group(streams);
        List<String> order = new ArrayList<>();
        for (String prov : JsScraperRunner.ALL_NUVIO_PROVIDERS) {
            String k = groupKey(prov);
            if (!order.contains(k)) order.add(k);
        }
        if (byKey.containsKey("torrentio") && !order.contains("torrentio")) {
            order.add("torrentio");
        }
        if (byKey.containsKey("other") && !order.contains("other")) {
            order.add("other");
        }
        List<Column> cols = new ArrayList<>();
        List<String> colKeys = new ArrayList<>();
        for (String k : order) {
            List<StreamItem> items = byKey.get(k);
            if (items == null || items.isEmpty()) continue;
            Column c = new Column(displayName(k));
            c.items.addAll(items);
            cols.add(c);
            colKeys.add(k);
        }
        if (cols.isEmpty()) return;
        for (Column c : cols) sortColumn(c.items);

        final LivePicker p = new LivePicker();
        p.activity = activity;
        p.listener = listener;
        p.highlightUrl = highlightUrl;
        p.highlightLabel = highlightLabel;
        p.phone = PhoneUi.isPhone(activity);
        p.gapPx = dp(activity, 32);
        p.scrollHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        LinearLayout columnsRow = new LinearLayout(activity);
        columnsRow.setOrientation(p.phone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        p.columnsRow = columnsRow;
        if (!p.phone) {
            HorizontalScrollView hsv = new HorizontalScrollView(activity);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(columnsRow);
            p.hsv = hsv;
        }

        for (int i = 0; i < cols.size(); i++) {
            buildColumn(p, cols.get(i), colKeys.get(i));
        }
        wireFocusNavigation(p);

        // v1.7.0: phones stack the columns in one outer scroll
        // (capped at 75% of screen height); TVs keep the carousel
        // strip (HorizontalScrollView) with exactly gapPx between
        // columns.
        android.util.DisplayMetrics dm =
                activity.getResources().getDisplayMetrics();
        View contentView;
        if (p.phone) {
            ScrollView outer = new ScrollView(activity);
            outer.setVerticalScrollBarEnabled(false);
            columnsRow.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            outer.addView(columnsRow);
            outer.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
            outer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (dm.heightPixels * 0.75)));
            contentView = outer;
        } else {
            android.widget.FrameLayout root =
                    new android.widget.FrameLayout(activity);
            android.widget.FrameLayout.LayoutParams hsvLp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.CENTER_HORIZONTAL);
            p.hsv.setLayoutParams(hsvLp);
            root.addView(p.hsv);
            root.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
            contentView = root;
        }

        // v1.43.1: loading indicator — a spinner + text shown
        // while providers are still running, so the user can tell
        // the list is still growing. v1.43.2: moved to the TOP of
        // the picker — at the bottom it sat below the 480dp columns
        // and was never visible. Text is "Loading stream while you
        // can choose it", spinner before the text. Hidden once
        // every provider has finished (the caller calls
        // setLoading(false) in done).
        LinearLayout loadingFooter = new LinearLayout(activity);
        loadingFooter.setOrientation(LinearLayout.HORIZONTAL);
        loadingFooter.setGravity(android.view.Gravity.CENTER_VERTICAL);
        loadingFooter.setPadding(dp(activity, 4), dp(activity, 4),
                dp(activity, 4), dp(activity, 10));
        android.widget.ProgressBar spin = new android.widget.ProgressBar(
                activity, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#2196F3")));
        TextView loadingText = new TextView(activity);
        loadingText.setText("Loading stream while you can choose it");
        loadingText.setTextSize(14);
        loadingText.setTextColor(Color.parseColor("#90CAF9"));
        loadingText.setPadding(dp(activity, 8), 0, 0, 0);
        loadingFooter.addView(spin);
        loadingFooter.addView(loadingText);
        p.loadingFooter = loadingFooter;

        // Wrap the loading indicator + the columns area in one
        // vertical layout for the dialog — indicator on top so it
        // is always visible, columns below it.
        LinearLayout contentRoot = new LinearLayout(activity);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.addView(loadingFooter);
        contentRoot.addView(contentView);

        final AlertDialog[] dlgHolder = new AlertDialog[1];
        AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(contentRoot)
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> {
                    for (Runnable r : p.activeScrolls) p.scrollHandler.removeCallbacks(r);
                    p.activeScrolls.clear();
                    if (current == p) current = null;
                })
                .create();
        p.dlg = dlg;
        dlgHolder[0] = dlg;
        current = p;
        dlg.show();
        // Near-full-width dialog, so the columns get real room
        // instead of the default narrow box.
        try {
            int w = activity.getResources().getDisplayMetrics().widthPixels;
            if (dlg.getWindow() != null) {
                dlg.getWindow().setLayout((int) (w * 0.95),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {}
        // Focus the first stream so D-pad works immediately.
        if (!p.colButtons.isEmpty() && !p.colButtons.get(0).isEmpty()) {
            final View first = p.colButtons.get(0).get(0);
            first.post(() -> first.requestFocus());
        }
    }

    /** Build one provider column (header + underline + option
     *  list) and append it to the picker's strip. Used for the
     *  initial columns and for live-added ones. */
    private static void buildColumn(LivePicker p, Column c, String key) {
        final Activity activity = p.activity;
        final LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        if (p.phone) {
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    dp(activity, COL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
            colLp.setMargins(p.colsByKey.isEmpty() ? 0 : p.gapPx, 0, 0, 0);
            col.setLayoutParams(colLp);
        }

        TextView h = new TextView(activity);
        h.setText(c.header);
        h.setTextSize(18);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(activity, 2));
        col.addView(h);
        p.colHeaders.add(h);
        View ul = new View(activity);
        ul.setBackgroundColor(Color.parseColor("#2196F3"));
        ul.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 2)));
        ul.setVisibility(View.INVISIBLE);
        col.addView(ul);
        p.colUnderlines.add(ul);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        ColRef ref = new ColRef();
        ref.col = col;
        ref.list = list;
        ref.header = h;
        ref.underline = ul;
        ref.idx = p.colButtons.size();
        p.colsByKey.put(key, ref);
        p.shownKeys.add(key);
        final int colIdx = ref.idx;
        for (StreamItem s : c.items) {
            View opt = buildOption(p, s, colIdx);
            list.addView(opt);
            ref.buttons.add(opt);
        }
        if (p.phone) {
            col.addView(list);
        } else {
            ScrollView sv = new ScrollView(activity);
            sv.setVerticalScrollBarEnabled(false);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, COL_HEIGHT_DP)));
            sv.addView(list);
            ref.sv = sv;
            col.addView(sv);
        }
        p.columnsRow.addView(col);
        p.colButtons.add(ref.buttons);
    }

    /** Build one option row (title + info) with its focus/click
     *  behaviour. colIdx is this option's column index, used to
     *  highlight the active column header. */
    private static View buildOption(final LivePicker p, final StreamItem s,
                                    final int colIdx) {
        final Activity activity = p.activity;
        // v1.5.4: one option layout for every column — a title
        // line plus an info line, both single-line and
        // ellipsized, so every option is exactly the same height
        // and rows align across columns. Words only, no button
        // rectangle; focus shows as blue text (see the focus
        // listener).
        LinearLayout opt = new LinearLayout(activity);
        opt.setOrientation(LinearLayout.VERTICAL);
        opt.setId(View.generateViewId());
        opt.setPadding(dp(activity, 10), dp(activity, 6),
                dp(activity, 10), dp(activity, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(activity, 6));
        opt.setLayoutParams(lp);
        // v1.12.0: title wraps to 2 lines (was 1) so the whole
        // link text shows; the info line stays 1. Every option
        // is still the same height (2+1).
        final TextView titleV = new TextView(activity);
        titleV.setText(tagTitle(s, p.highlightUrl, p.highlightLabel));
        titleV.setTextSize(16);
        titleV.setTextColor(Color.WHITE);
        titleV.setMaxLines(2);
        titleV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final TextView subV = new TextView(activity);
        subV.setText(sub(s));
        subV.setTextSize(13);
        subV.setTextColor(Color.parseColor("#B0B5BA"));
        subV.setMaxLines(1);
        subV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        opt.addView(titleV);
        opt.addView(subV);
        opt.setFocusable(true);
        opt.setClickable(true);
        final Runnable[] scrollTask = new Runnable[1];
        opt.setOnFocusChangeListener((v, focused) -> {
            titleV.setTextColor(focused
                    ? Color.parseColor("#2196F3") : Color.WHITE);
            subV.setTextColor(focused
                    ? Color.parseColor("#90CAF9")
                    : Color.parseColor("#B0B5BA"));
            // v1.41.0: soft pill behind the focused link + the
            // focused column's header turns accent with its
            // underline bar shown.
            v.setBackground(focused ? focusPill() : null);
            if (focused) setActiveColumn(p.colHeaders, p.colUnderlines, colIdx);
            // v1.12.0: when the cursor lands on a title longer
            // than its 2 lines, the hidden text scrolls up
            // automatically (pausing at each end); unfocusing
            // snaps it back to the top.
            if (scrollTask[0] != null) {
                p.scrollHandler.removeCallbacks(scrollTask[0]);
                p.activeScrolls.remove(scrollTask[0]);
                scrollTask[0] = null;
            }
            titleV.scrollTo(0, 0);
            if (focused) {
                // v1.40.0: center-lock — the focused column stays
                // centered in the strip and the focused option
                // stays centered in its column; the lists scroll
                // under the cursor. Posted so it runs after the
                // platform's own minimal scroll-to-visible.
                ColRef ref = refForColumn(p, colIdx);
                centerColumn(p.hsv, ref != null ? ref.col : null);
                centerOption(ref != null ? ref.sv : null, v);
                titleV.setEllipsize(null);
                int lines = wrappedLines(titleV);
                if (lines > 2) {
                    final int lineH = titleV.getLineHeight();
                    final int maxY = (lines - 2) * lineH;
                    final int[] y = {0};
                    final int[] dir = {1};
                    final Runnable[] self = new Runnable[1];
                    self[0] = () -> {
                        y[0] += dir[0] * lineH;
                        if (y[0] >= maxY) { y[0] = maxY; dir[0] = -1; }
                        else if (y[0] <= 0) { y[0] = 0; dir[0] = 1; }
                        titleV.scrollTo(0, y[0]);
                        p.scrollHandler.postDelayed(self[0],
                                (y[0] == 0 || y[0] == maxY) ? 1200 : 700);
                    };
                    scrollTask[0] = self[0];
                    p.activeScrolls.add(self[0]);
                    p.scrollHandler.postDelayed(self[0], 800);
                }
            } else {
                titleV.setEllipsize(
                        android.text.TextUtils.TruncateAt.END);
            }
        });
        opt.setOnClickListener(v -> {
            if (p.dlg != null) p.dlg.dismiss();
            p.listener.onPick(s);
        });
        return opt;
    }

    /** The ColRef for a column index (colButtons order). */
    private static ColRef refForColumn(LivePicker p, int colIdx) {
        for (ColRef r : p.colsByKey.values()) {
            if (r.idx == colIdx) return r;
        }
        return null;
    }

    /** Left/Right between columns, same row where possible (TV only). */
    private static void wireFocusNavigation(LivePicker p) {
        if (p.phone) return;
        for (int i = 0; i < p.colButtons.size(); i++) {
            List<View> btns = p.colButtons.get(i);
            List<View> left = i > 0 ? p.colButtons.get(i - 1) : null;
            List<View> right = i + 1 < p.colButtons.size()
                    ? p.colButtons.get(i + 1) : null;
            for (int j = 0; j < btns.size(); j++) {
                if (left != null) {
                    btns.get(j).setNextFocusLeftId(
                            left.get(Math.min(j, left.size() - 1)).getId());
                }
                if (right != null) {
                    btns.get(j).setNextFocusRightId(
                            right.get(Math.min(j, right.size() - 1)).getId());
                }
            }
        }
    }

    /** v1.41.0: mark one column as the active (centered) one —
     *  its header turns accent blue and its underline bar shows;
     *  the rest go back to white with the bar hidden. */
    private static void setActiveColumn(List<TextView> headers,
                                        List<View> underlines, int active) {
        for (int i = 0; i < headers.size(); i++) {
            boolean on = (i == active);
            headers.get(i).setTextColor(on
                    ? Color.parseColor("#2196F3") : Color.WHITE);
            if (i < underlines.size()) {
                underlines.get(i).setVisibility(
                        on ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    /** v1.40.0: scroll the strip so the focused column sits in the
     *  center of the viewport. Clamped at both ends, so the first
     *  and last columns rest at the strip's edges. Posted so it
     *  runs after layout and after the platform's own minimal
     *  scroll-to-visible. */
    private static void centerColumn(HorizontalScrollView hsv, View column) {
        if (hsv == null || column == null) return;
        hsv.post(() -> {
            if (hsv.getWidth() == 0 || column.getWidth() == 0) return;
            if (hsv.getChildCount() == 0) return;
            int colCenter = column.getLeft() + column.getWidth() / 2;
            int targetX = colCenter - hsv.getWidth() / 2;
            int maxX = Math.max(0,
                    hsv.getChildAt(0).getWidth() - hsv.getWidth());
            targetX = Math.max(0, Math.min(targetX, maxX));
            hsv.smoothScrollTo(targetX, 0);
        });
    }

    /** v1.40.0: scroll a column so the focused option sits in the
     *  center of its viewport. Clamped at both ends, so the first
     *  and last options rest at the list's edges. Posted so it
     *  runs after layout and after the platform's own minimal
     *  scroll-to-visible. */
    private static void centerOption(ScrollView sv, View option) {
        if (sv == null || option == null) return;
        sv.post(() -> {
            if (sv.getHeight() == 0 || option.getHeight() == 0) return;
            if (sv.getChildCount() == 0) return;
            int optCenter = option.getTop() + option.getHeight() / 2;
            int targetY = optCenter - sv.getHeight() / 2;
            int maxY = Math.max(0,
                    sv.getChildAt(0).getHeight() - sv.getHeight());
            targetY = Math.max(0, Math.min(targetY, maxY));
            sv.smoothScrollTo(0, targetY);
        });
    }

    /** Info line under an option's title: seeders/size/host for
     *  torrents, host otherwise. */
    private static String sub(StreamItem s) {
        String sub = hostOf(s.source);
        if (s.isTorrent()) {
            StringBuilder sb = new StringBuilder();
            if (s.seeders >= 0) {
                sb.append("👤 ").append(s.seeders).append(" seeders");
            }
            if (s.size != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(s.size);
            }
            if (sb.length() > 0) sb.append(" · ");
            sb.append(sub);
            sub = sb.toString();
        }
        return sub;
    }

    /** How many lines the title text needs at the view's current
     *  width (titles are single-line source text; wrapping makes
     *  the lines). Used to decide whether the focused auto-scroll
     *  is needed. */
    private static int wrappedLines(TextView tv) {
        CharSequence t = tv.getText();
        if (t == null || t.length() == 0) return 1;
        int avail = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
        if (avail <= 0) return 1;
        float w = tv.getPaint().measureText(t.toString());
        int lines = (int) Math.ceil(w / avail);
        int nl = 1;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') nl++;
        return Math.max(lines, nl);
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}
