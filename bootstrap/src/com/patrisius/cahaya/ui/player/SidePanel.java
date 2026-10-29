package com.patrisius.cahaya.ui.player;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.patrisius.cahaya.R;
import com.patrisius.cahaya.ui.PhoneUi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Right-hand panel used for subtitle / audio / speed pickers (the same pattern
 * NuvioTV uses for its track and style side panels). Rows are D-pad focusable;
 * focus is locked inside the panel until it is closed with Back.
 */
public class SidePanel extends FrameLayout {
    private final float dp;
    private final TextView titleView;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final List<Row> rows = new ArrayList<>();
    private Row toFocus;

    public SidePanel(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PlayerTheme.SURFACE);
        bg.setCornerRadii(new float[]{20 * dp, 20 * dp, 0, 0, 0, 0, 20 * dp, 20 * dp});
        setBackground(bg);
        setVisibility(GONE);
        setClickable(true);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (20 * dp), (int) (24 * dp), (int) (20 * dp), (int) (16 * dp));
        titleView = new TextView(c);
        titleView.setTextColor(PlayerTheme.TEXT);
        titleView.setTextSize(22);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding((int) (8 * dp), 0, 0, (int) (12 * dp));
        col.addView(titleView);
        scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        addView(col, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Start (or restart) building the panel content. Keeps the scroll position stable on rebuilds. */
    public void begin(String title) {
        titleView.setText(title);
        list.removeAllViews();
        rows.clear();
        toFocus = null;
    }

    public void section(String text) {
        TextView t = new TextView(getContext());
        t.setText(text.toUpperCase(java.util.Locale.ROOT));
        t.setTextColor(PlayerTheme.TEXT_DIM);
        t.setTextSize(12);
        t.setLetterSpacing(0.1f);
        t.setPadding((int) (8 * dp), (int) (16 * dp), 0, (int) (6 * dp));
        list.addView(t);
    }

    /** Non-focusable informational line (e.g. "Searching addons…"). */
    public TextView note(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(PlayerTheme.TEXT_DIM);
        t.setTextSize(14);
        t.setPadding((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
        list.addView(t);
        return t;
    }

    public Row row(String label, String detail, boolean selected, Runnable onClick) {
        Row r = new Row(getContext(), label, detail, selected, null, null, onClick);
        add(r, selected);
        return r;
    }

    /** Left/right adjusts (step -1/+1); Enter runs {@code activate} if given. */
    public Row stepper(String label, Supplier<CharSequence> value, IntConsumer step, Runnable activate) {
        Row r = new Row(getContext(), label, null, false, value, step, activate);
        add(r, false);
        return r;
    }

    private void add(Row r, boolean preferFocus) {
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        rows.add(r);
        if (preferFocus || toFocus == null) toFocus = r;
    }

    /** Index of the row that currently has focus, or -1. Lets a rebuild put focus back where it was. */
    public int focusedIndex() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).hasFocus()) return i;
        return -1;
    }

    public void end() { end(-1); }

    /** Finish building: lock focus inside the panel; focus {@code restoreIndex}, else the selected / first row. */
    public void end(int restoreIndex) {
        if (restoreIndex >= 0 && restoreIndex < rows.size()) toFocus = rows.get(restoreIndex);
        for (Row r : rows) {
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        if (!rows.isEmpty()) {
            rows.get(0).setNextFocusUpId(rows.get(0).getId());
            Row last = rows.get(rows.size() - 1);
            last.setNextFocusDownId(last.getId());
        }
        final Row target = toFocus;
        // v1.7.2: when the panel is still hidden (just opened),
        // don't request focus yet — the ScrollView would scroll to
        // the row while the panel is still sliding in, making the
        // list jump mid-animation. show() focuses after the slide.
        if (target != null && getVisibility() == VISIBLE) post(target::requestFocus);
    }

    public void show() {
        if (getVisibility() == VISIBLE) return;
        setVisibility(VISIBLE);
        setTranslationX(getWidth() > 0 ? getWidth() : 420 * dp);
        animate().translationX(0).setDuration(180).withEndAction(() -> {
            if (getVisibility() == VISIBLE && toFocus != null) toFocus.requestFocus();
        }).start();
    }

    public void hide() {
        setVisibility(GONE);
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    /** One focusable row: label (+ detail) on the left, check mark / stepper value on the right. */
    public static class Row extends LinearLayout {
        private final TextView labelV, detailV, valueV;
        private final ImageView check, chevL, chevR;
        private final IntConsumer step;
        private final Supplier<CharSequence> value;
        private final Runnable onClick;
        private final boolean phoneMode;
        private final GradientDrawable bg = new GradientDrawable();

        Row(Context c, String label, String detail, boolean selected,
            Supplier<CharSequence> value, IntConsumer step, Runnable onClick) {
            super(c);
            float dp = c.getResources().getDisplayMetrics().density;
            this.step = step; this.value = value; this.onClick = onClick;
            this.phoneMode = PhoneUi.isPhone(c);
            setId(View.generateViewId());
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
            bg.setCornerRadius(12 * dp);
            setBackground(bg);
            setFocusable(true);
            setClickable(true);
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.bottomMargin = (int) (4 * dp);
            setLayoutParams(lp);

            LinearLayout text = new LinearLayout(c);
            text.setOrientation(VERTICAL);
            labelV = new TextView(c);
            labelV.setText(label);
            labelV.setTextSize(17);
            labelV.setSingleLine(true);
            labelV.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labelV.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            text.addView(labelV);
            detailV = new TextView(c);
            detailV.setTextSize(12);
            detailV.setSingleLine(true);
            detailV.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (detail == null || detail.isEmpty()) detailV.setVisibility(GONE); else detailV.setText(detail);
            text.addView(detailV);
            addView(text, new LayoutParams(0, -2, 1f));

            int ic = (int) (18 * dp);
            chevL = icon(c, R.drawable.ic_player_chev_left, ic);
            valueV = new TextView(c);
            valueV.setTextSize(16);
            valueV.setGravity(Gravity.CENTER);
            valueV.setMinWidth((int) (84 * dp));
            chevR = icon(c, R.drawable.ic_player_chev_right, ic);
            check = icon(c, R.drawable.ic_player_check, (int) (20 * dp));
            if (value != null) {
                addView(chevL); addView(valueV); addView(chevR);
                valueV.setText(value.get());
                // v1.7.2: chevrons are tappable — on touch there is
                // no D-pad Left/Right, so this is the way to step a
                // value on a phone.
                chevL.setClickable(true);
                chevL.setOnClickListener(v -> { if (step != null) { step.accept(-1); refreshValue(); } });
                chevR.setClickable(true);
                chevR.setOnClickListener(v -> { if (step != null) { step.accept(1); refreshValue(); } });
            } else {
                addView(check);
                check.setVisibility(selected ? VISIBLE : INVISIBLE);
            }
            // v1.7.2: a stepper with no activate action steps forward
            // on tap (Colour, Edge, Opacity, Auto-select). Steppers
            // with an activate (Size/Height reset) keep it — use the
            // chevrons to step those.
            setOnClickListener(v -> {
                if (this.onClick != null) this.onClick.run();
                else if (this.step != null) { this.step.accept(1); refreshValue(); }
            });
            paint();
        }

        private static ImageView icon(Context c, int res, int px) {
            ImageView i = new ImageView(c);
            i.setImageResource(res);
            i.setLayoutParams(new LayoutParams(px, px));
            return i;
        }

        public void refreshValue() { if (value != null) valueV.setText(value.get()); }

        @Override
        protected void onFocusChanged(boolean gain, int dir, Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            paint();
        }

        private void paint() {
            boolean f = isFocused();
            bg.setColor(f ? PlayerTheme.ROW_FOCUS : PlayerTheme.ROW_IDLE);
            int fg = f ? PlayerTheme.ROW_ON_FOCUS : PlayerTheme.TEXT;
            int dim = f ? 0x99000000 : PlayerTheme.TEXT_DIM;
            labelV.setTextColor(fg);
            detailV.setTextColor(dim);
            valueV.setTextColor(fg);
            check.setColorFilter(f ? PlayerTheme.ROW_ON_FOCUS : PlayerTheme.ACCENT);
            chevL.setColorFilter(fg);
            chevR.setColorFilter(fg);
            // v1.7.2: on touch there is no focus, so steppers would
            // never show their chevrons — always show them on phones.
            int vis = (step != null && (f || phoneMode)) ? VISIBLE : INVISIBLE;
            chevL.setVisibility(vis);
            chevR.setVisibility(vis);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e) {
            if (step != null && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                step.accept(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                refreshValue();
                return true;
            }
            return super.onKeyDown(keyCode, e);
        }
    }
}
