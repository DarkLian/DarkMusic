package com.darkmusic.client.gui;

/**
 * Shared alpha composition for spectrum faces, peak markers, trails, and glow.
 */
public final class SpectrumAppearance {
    private SpectrumAppearance() {
    }

    public static int opacity(int color, double opacity) {
        double value = Double.isFinite(opacity) ? Math.clamp(opacity, 0, 1) : 1;
        return color & 0xffffff | (int) Math.round((color >>> 24) * value) << 24;
    }

    public static int alpha(int color, int alpha) {
        return opacity(color, alpha / 255.0);
    }

    public static boolean detachedPeak(float peak, float live, float height) {
        return (peak - live) * height > 2;
    }
}
