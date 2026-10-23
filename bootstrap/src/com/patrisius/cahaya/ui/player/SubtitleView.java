package com.patrisius.cahaya.ui.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * Draws subtitle text itself (outline / shadow / background box), because
 * vo=mediacodec_embed puts the video straight on the surface and mpv cannot
 * draw anything over it. A plain TextView can only do a shadow; this view
 * draws a real stroked outline, so every appearance setting is visible.
 */
public class SubtitleView extends View {
    public static final int EDGE_OUTLINE = 0, EDGE_SHADOW = 1, EDGE_NONE = 2;

    private final TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint stroke = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private String text = "";
    private float sizePx = 48f;
    private int color = Color.WHITE;
    private boolean bold;
    private int edge = EDGE_OUTLINE;
    private boolean background;
    private int bgOpacity = 70;
    private int maxWidthPx;

    private StaticLayout fillLayout, strokeLayout;
    private float drawDx, padX, padY;

    public SubtitleView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeMiter(2f);
        stroke.setColor(Color.BLACK);
    }

    /** Widest the text block may get, in pixels (word-wraps beyond this). */
    public void setMaxWidthPx(int px) {
        if (px != maxWidthPx) { maxWidthPx = px; requestLayout(); }
    }

    public void setText(String t) {
        String n = t == null ? "" : t;
        if (n.equals(text)) return;
        text = n;
        requestLayout();
        invalidate();
    }

    public void setStyle(float sizePx, int color, boolean bold, int edge, boolean background, int bgOpacityPct) {
        this.sizePx = sizePx;
        this.color = color;
        this.bold = bold;
        this.edge = edge;
        this.background = background;
        this.bgOpacity = Math.max(0, Math.min(100, bgOpacityPct));
        requestLayout();
        invalidate();
    }

    private StaticLayout build(TextPaint p, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.05f)
                .build();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (text.isEmpty()) {
            fillLayout = strokeLayout = null;
            setMeasuredDimension(0, 0);
            return;
        }
        Typeface tf = Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        fill.setTypeface(tf);
        fill.setTextSize(sizePx);
        fill.setColor(color);
        stroke.setTypeface(tf);
        stroke.setTextSize(sizePx);
        stroke.setStrokeWidth(Math.max(2f, sizePx * 0.13f));

        fill.clearShadowLayer();
        if (edge == EDGE_SHADOW) fill.setShadowLayer(sizePx * 0.10f, sizePx * 0.03f, sizePx * 0.06f, Color.BLACK);

        padX = (background ? 14 : 6) * density;
        padY = (background ? 6 : 3) * density;
        int avail = MeasureSpec.getSize(wSpec);
        int cap = maxWidthPx > 0 ? Math.min(maxWidthPx, avail) : avail;
        int textW = (int) Math.max(1, cap - 2 * padX);

        StaticLayout probe = build(fill, textW);
        float widest = 0f;
        for (int i = 0; i < probe.getLineCount(); i++) widest = Math.max(widest, probe.getLineWidth(i));
        int contentW = (int) Math.ceil(widest);
        fillLayout = probe;
        strokeLayout = edge == EDGE_OUTLINE ? build(stroke, textW) : null;

        // Layouts are centred inside textW; shift so the visible text sits inside the box.
        drawDx = padX - (textW - contentW) / 2f;
        int w = (int) (contentW + 2 * padX);
        int h = (int) (fillLayout.getHeight() + 2 * padY);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (fillLayout == null) return;
        if (background) {
            box.setColor(Color.argb(Math.round(255f * bgOpacity / 100f), 0, 0, 0));
            float r = 8 * density;
            c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, box);
        }
        c.save();
        c.translate(drawDx, padY);
        if (strokeLayout != null) strokeLayout.draw(c);
        fillLayout.draw(c);
        if (edge == EDGE_SHADOW) fillLayout.draw(c);     // second pass makes the shadow read on bright scenes
        c.restore();
    }
}
