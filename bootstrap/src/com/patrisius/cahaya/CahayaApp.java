package com.patrisius.cahaya;

import android.app.Application;

import com.patrisius.cahaya.addon.AddonManager;

/** v0.1 bootstrap application class. */
public class CahayaApp extends Application {

    private AddonManager addonManager;

    @Override
    public void onCreate() {
        super.onCreate();
        addonManager = new AddonManager(this);
    }

    public AddonManager addons() {
        return addonManager;
    }
}
