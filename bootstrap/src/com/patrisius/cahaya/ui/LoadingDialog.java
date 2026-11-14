package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Animated "finding streams" dialog (v1.42.0). The old loading
 * state was a bare text dialog with no motion, so the app looked
 * frozen while 65 providers were being queried. This one pairs
 * the message with a spinning indeterminate ProgressBar (tinted
 * the app accent) so the wait reads as activity, not a hang.
 * Shared by every stream-finding flow — Details Play, Playing
 * History resume, and the player's Next episode — so they all
 * look and behave the same. Non-cancelable, like before; the
 * caller still dismisses it with progress.dismiss().
 */
public class LoadingDialog {

    /** Show the animated loading dialog. Returns it for dismiss(). */
    public static AlertDialog show(Activity activity, String message) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(activity, 20);
        row.setPadding(pad, pad, pad, pad);

        ProgressBar spinner = new ProgressBar(activity, null,
                android.R.attr.progressBarStyle);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(
                Color.parseColor("#2196F3")));
        row.addView(spinner);

        TextView msg = new TextView(activity);
        msg.setText(message);
        msg.setTextSize(16);
        msg.setTextColor(Color.WHITE);
        msg.setPadding(dp(activity, 16), 0, 0, 0);
        row.addView(msg);

        AlertDialog dlg = new AlertDialog.Builder(activity)
                .setView(row)
                .setCancelable(false)
                .create();
        dlg.show();
        return dlg;
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}
