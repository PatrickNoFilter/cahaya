package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.patrisius.cahaya.addon.StreamItem;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stream source picker (v1.5.1): streams grouped into COLUMNS by
 * source, in fixed order — PlayIMDb, Torrentio, 4khdhub — instead of
 * one flat list. Streams from any other source (e.g. FibWatch) go in
 * a trailing "Other" column, only when present, so nothing playable
 * silently disappears. Shared by Details Play, the player's Next
 * button, and Playing History clicks.
 * D-pad: Up/Down moves within a column, Left/Right moves between
 * columns (same row where possible). Each column scrolls vertically
 * on its own, so long Torrentio lists stay reachable; the whole
 * strip scrolls horizontally when a 4th column is present. The
 * dialog dismisses itself on pick.
 * v1.5.2: options are words only (plain TextViews, no button
 * rectangles) — the focused word turns blue instead of a box
 * highlight, so selection stays visible and the list stays light.
 * v1.5.3: columns condensed as tightly as possible — equal-weight
 * columns in a near-full-width dialog, so all 3 (or 4) columns are
 * visible at once with no horizontal scrolling.
 * v1.5.4: uniform options — every option is exactly two lines
 * (title + info), the same height and spacing in every column.
 * The old single-TextView label let a long filename wrap onto
 * both lines and swallow its info line, so columns came out
 * ragged with different heights (seen in the 1.5.3 screenshot).
 * v1.5.5: columns sized to their content (capped at an equal
 * share) with exactly the same gap between every pair of
 * columns. Equal-weight columns left wide phantom gaps after
 * short-content columns (PlayIMDb), which read as unequal
 * spacing next to long-content columns (seen in the 1.5.4
 * screenshot: PlayIMDb→Torrentio looked wider than
 * Torrentio→4khdhub). The row is centered, so leftover space
 * splits equally to both edges.
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

    /** Group key: 0 = PlayIMDb, 1 = Torrentio, 2 = 4khdhub, 3 = other. */
    private static int groupOf(StreamItem s) {
        String src = s.source == null ? "" : s.source;
        if ("playimdb".equals(src)) return 0;
        if (src.contains("torrentio")) return 1;
        if ("4khdhub".equals(src)) return 2;
        return 3;
    }

    public static void show(final Activity activity, String title,
                            List<StreamItem> streams, final PickListener listener) {
        Column[] cols = new Column[]{
                new Column("PlayIMDb"), new Column("Torrentio"),
                new Column("4khdhub"), new Column("Other"),
        };
        for (StreamItem s : streams) cols[groupOf(s)].items.add(s);
        // Seeders-desc within each column (stable: scrapers keep order).
        for (Column c : cols) {
            Collections.sort(c.items,
                    (a, b) -> Integer.compare(b.seeders, a.seeders));
        }

        // v1.5.5: measure each column's content, size the column to
        // it (capped at an equal share), and put exactly the same
        // gap between every pair of columns (see class comment).
        List<Column> shown = new ArrayList<>();
        for (Column c : cols) {
            if (!c.items.isEmpty()) shown.add(c);
        }
        if (shown.isEmpty()) return;
        // v1.7.0: on phones the columns stack vertically in one
        // scrollable dialog, so no content measuring or gap math.
        boolean phone = PhoneUi.isPhone(activity);
        android.util.DisplayMetrics dm =
                activity.getResources().getDisplayMetrics();
        List<Integer> colWidths = new ArrayList<>();
        int gapPx = dp(activity, 32);
        if (!phone) {
            android.graphics.Paint titlePaint = new android.graphics.Paint();
            titlePaint.setTextSize(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, 16, dm));
            android.graphics.Paint subPaint = new android.graphics.Paint();
            subPaint.setTextSize(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, 13, dm));
            android.graphics.Paint headerPaint = new android.graphics.Paint();
            headerPaint.setTextSize(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, 18, dm));
            int availPx = (int) (dm.widthPixels * 0.95) - dp(activity, 96);
            int capPx = availPx / shown.size();
            for (Column c : shown) {
                float w = measure(headerPaint, c.header);
                for (StreamItem s : c.items) {
                    w = Math.max(w, measure(titlePaint, s.title));
                    w = Math.max(w, measure(subPaint, sub(s)));
                }
                colWidths.add(Math.min((int) Math.ceil(w) + dp(activity, 8), capPx));
            }
            int sumW = 0;
            for (int w : colWidths) sumW += w;
            if (sumW + (shown.size() - 1) * gapPx > availPx) {
                gapPx = dp(activity, 16);
                if (sumW + (shown.size() - 1) * gapPx > availPx) {
                    float scale = (float) (availPx - (shown.size() - 1) * gapPx) / sumW;
                    for (int i = 0; i < colWidths.size(); i++) {
                        colWidths.set(i, (int) (colWidths.get(i) * scale));
                    }
                }
            }
        }

        final AlertDialog[] dlgHolder = new AlertDialog[1];
        LinearLayout columnsRow = new LinearLayout(activity);
        columnsRow.setOrientation(phone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        List<List<View>> colButtons = new ArrayList<>();
        for (int ci = 0; ci < shown.size(); ci++) {
            Column c = shown.get(ci);
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            if (phone) {
                col.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                        colWidths.get(ci), ViewGroup.LayoutParams.WRAP_CONTENT);
                colLp.setMargins(ci == 0 ? 0 : gapPx, 0, 0, 0);
                col.setLayoutParams(colLp);
            }

            TextView h = new TextView(activity);
            h.setText(c.header);
            h.setTextSize(18);
            h.setTextColor(Color.WHITE);
            h.setPadding(0, 0, 0, dp(activity, 6));
            col.addView(h);

            LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            List<View> btns = new ArrayList<>();
            for (final StreamItem s : c.items) {
                // v1.5.4: one option layout for every column — a
                // title line plus an info line, both single-line
                // and ellipsized, so every option is exactly the
                // same height and rows align across columns.
                // Words only, no button rectangle; focus shows as
                // blue text (see the focus listener).
                LinearLayout opt = new LinearLayout(activity);
                opt.setOrientation(LinearLayout.VERTICAL);
                opt.setId(View.generateViewId());
                opt.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(activity, 6));
                opt.setLayoutParams(lp);
                final TextView titleV = new TextView(activity);
                titleV.setText(s.title);
                titleV.setTextSize(16);
                titleV.setTextColor(Color.WHITE);
                titleV.setMaxLines(1);
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
                opt.setOnFocusChangeListener((v, focused) -> {
                    titleV.setTextColor(focused
                            ? Color.parseColor("#2196F3") : Color.WHITE);
                    subV.setTextColor(focused
                            ? Color.parseColor("#90CAF9")
                            : Color.parseColor("#B0B5BA"));
                });
                opt.setOnClickListener(v -> {
                    if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                    listener.onPick(s);
                });
                list.addView(opt);
                btns.add(opt);
            }
            if (phone) {
                col.addView(list);
            } else {
                ScrollView sv = new ScrollView(activity);
                sv.setVerticalScrollBarEnabled(false);
                sv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 480)));
                sv.addView(list);
                col.addView(sv);
            }
            columnsRow.addView(col);
            colButtons.add(btns);
        }
        // Left/Right between columns, same row where possible (TV only).
        if (!phone) for (int i = 0; i < colButtons.size(); i++) {
            List<View> btns = colButtons.get(i);
            List<View> left = i > 0 ? colButtons.get(i - 1) : null;
            List<View> right = i + 1 < colButtons.size()
                    ? colButtons.get(i + 1) : null;
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

        // v1.7.0: phones stack the columns in one outer scroll
        // (capped at 75% of screen height); TVs keep the centered
        // side-by-side row with exactly gapPx between columns.
        View contentView;
        if (phone) {
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
            android.widget.FrameLayout.LayoutParams rowLp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.CENTER_HORIZONTAL);
            columnsRow.setLayoutParams(rowLp);
            root.addView(columnsRow);
            root.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
            contentView = root;
        }

        AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(contentView)
                .setNegativeButton("Cancel", null)
                .create();
        dlgHolder[0] = dlg;
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
        if (!colButtons.isEmpty() && !colButtons.get(0).isEmpty()) {
            final View first = colButtons.get(0).get(0);
            first.post(() -> first.requestFocus());
        }
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

    /** Null-safe Paint.measureText (titles/hosts can be null). */
    private static float measure(android.graphics.Paint p, String t) {
        return t == null ? 0 : p.measureText(t);
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
