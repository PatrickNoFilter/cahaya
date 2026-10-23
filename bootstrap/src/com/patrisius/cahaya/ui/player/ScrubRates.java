package com.patrisius.cahaya.ui.player;

/**
 * D-pad scrub step sizes. Holding a direction key fires repeated key-down
 * events (KeyEvent.getRepeatCount()); longer holds seek in bigger steps so
 * a two-hour film is crossable without mashing the remote.
 * Same tiers as NuvioTV's PlayerScrubRates.
 */
public final class ScrubRates {
    private ScrubRates() {}

    public static final long STEP_SHORT_MS = 10_000L;
    public static final long STEP_MEDIUM_MS = 20_000L;
    public static final long STEP_LONG_MS = 30_000L;
    public static final long STEP_VERY_LONG_MS = 60_000L;

    public static long stepMsForKeyRepeat(int repeatCount) {
        int c = Math.max(0, repeatCount);
        if (c >= 15) return STEP_VERY_LONG_MS;
        if (c >= 8) return STEP_LONG_MS;
        if (c >= 3) return STEP_MEDIUM_MS;
        return STEP_SHORT_MS;
    }

    public static long deltaMs(int repeatCount, boolean forward) {
        long s = stepMsForKeyRepeat(repeatCount);
        return forward ? s : -s;
    }
}
