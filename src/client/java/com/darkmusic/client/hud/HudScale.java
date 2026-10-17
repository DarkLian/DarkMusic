package com.darkmusic.client.hud;

/**
 * Shared wheel behavior for every on-screen HUD editor.
 */
public final class HudScale {
    private double remainder;

    public static double clamp(double value) {
        return Double.isFinite(value) ? Math.round(Math.clamp(value, .5, 3) * 10) / 10.0 : 1;
    }

    public double scroll(double scale, double delta) {
        if (!Double.isFinite(delta)) return scale;
        remainder += delta;
        int steps = (int) remainder;
        remainder -= steps;
        return clamp(scale + steps * .1);
    }
}
