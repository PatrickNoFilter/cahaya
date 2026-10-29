package com.patrisius.cahaya.ui;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;

/** Phone vs TV detection (v1.7.0): the TV box reports
 *  UI_MODE_TYPE_TELEVISION; a phone reports NORMAL. Layout
 *  branches on this, never on screen size alone, so the box
 *  keeps its exact TV layout while phones get the portrait UI. */
public final class PhoneUi {
    private PhoneUi() {}

    public static boolean isPhone(Context ctx) {
        try {
            UiModeManager um = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            return um != null && um.getCurrentModeType() != Configuration.UI_MODE_TYPE_TELEVISION;
        } catch (Throwable t) {
            return false;
        }
    }
}
