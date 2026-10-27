package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;

/**
 * Shared wheel behavior for every on-screen HUD editor.
 */
public final class HudScale {
    private double remainder;

    public static double clamp(double value) {
        return Settings.clampStep(value);
    }

    public double scroll(double scale, double delta) {
        if (!Double.isFinite(delta)) return scale;
        remainder += delta;
        int steps = (int) remainder;
        remainder -= steps;
        return clamp(scale + steps * .1);
    }
}
