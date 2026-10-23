package com.patrisius.cahaya.ui.player;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Round icon button for the player. Idle: translucent disc, white icon.
 * Focused: solid white disc, dark icon, slight scale-up (TV focus language).
 * Active (e.g. subtitles on): accent-tinted icon.
 */
public class PlayerButton extends FrameLayout {
    private final ImageView icon;
    private final GradientDrawable bg = new GradientDrawable();
    private boolean active;
    private final int iconPx;

    public PlayerButton(Context c, int sizeDp, int iconDp, int iconRes, String description) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        iconPx = (int) (iconDp * d);
        bg.setShape(GradientDrawable.OVAL);
        setBackground(bg);
        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER);
        addView(icon, lp);
        setFocusable(true);
        setClickable(true);
        setContentDescription(description);
        setIcon(iconRes);
        setLayoutParams(new android.widget.LinearLayout.LayoutParams((int) (sizeDp * d), (int) (sizeDp * d)));
        refresh();
    }

    public void setIcon(int res) { icon.setImageResource(res); }

    public void setActive(boolean a) { active = a; refresh(); }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        animate().scaleX(gain ? 1.12f : 1f).scaleY(gain ? 1.12f : 1f).setDuration(120).start();
        refresh();
    }

    @Override
    public void setPressed(boolean p) { super.setPressed(p); refresh(); }

    private void refresh() {
        boolean f = isFocused();
        bg.setColor(f ? PlayerTheme.ROW_FOCUS : (isPressed() ? 0x33FFFFFF : 0x00000000));
        icon.setColorFilter(f ? PlayerTheme.ROW_ON_FOCUS : (active ? PlayerTheme.ACCENT : PlayerTheme.TEXT));
    }
}
