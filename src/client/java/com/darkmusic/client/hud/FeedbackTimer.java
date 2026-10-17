package com.darkmusic.client.hud;

/**
 * One second at full visibility followed by a three-quarter-second fade.
 */
public final class FeedbackTimer {
    private long shown;
    private boolean visible;

    public void clear() {
        visible = false;
    }

    public void show(long now) {
        shown = now;
        visible = true;
    }

    public double opacity(long now) {
        if (!visible) return 0;
        double elapsed = Math.max(0, (now - shown) / 1e9);
        return elapsed <= 1 ? 1 : Math.clamp(1 - (elapsed - 1) / .75, 0, 1);
    }
}
