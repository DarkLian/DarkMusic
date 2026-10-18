package com.darkmusic.client.hud;

import com.darkmusic.client.gui.SpectrumCurve;

/**
 * Fixed local proportions shared by the renderer, preview, and layout editor.
 */
public record CircularHudLayout(int width, int height, float centerX, float centerY, int titleY, int artistY) {
    public static final float RING_INNER = 59, ICON_RADIUS = RING_INNER, RING_OUTER = 62, SPECTRUM_HEIGHT = 18, FEATHER = .65f;

    public static CircularHudLayout of(boolean spectrum) {
        return of(spectrum, true);
    }

    public static CircularHudLayout of(boolean spectrum, boolean text) {
        int radius = (int) Math.ceil(RING_OUTER + (spectrum ? SPECTRUM_HEIGHT : 0) + FEATHER + 1);
        int title = radius * 2 + 9;
        return new CircularHudLayout(176, text ? title + 28 : radius * 2, 88, radius, title, title + 16);
    }

    public static float iconZoom(float[] levels, int bands) {
        int bass = Math.max(1, bands / 4);
        float energy = 0;
        for (int i = 0; i < bass; i++) energy += Float.isFinite(levels[i]) ? Math.clamp(levels[i], 0, 1) : 0;
        return 1 + .045f * energy / bass;
    }

    public static float waveHeight(float[] levels, int bands, double turn, com.darkmusic.client.config.Settings.SpectrumStyle style) {
        if (style == com.darkmusic.client.config.Settings.SpectrumStyle.FILLED_SPIKE) {
            double cell = (turn - Math.floor(turn)) * bands;
            int index = (int) cell;
            double triangle = 1 - Math.abs(2 * (cell - index) - 1);
            return magnitude(levels, bands, (index + .5) / bands) * (float) triangle * SPECTRUM_HEIGHT;
        }
        return magnitude(levels, bands, turn) * SPECTRUM_HEIGHT;
    }

    public static double progress(double position, double duration, boolean preview) {
        return Double.isFinite(position) && Double.isFinite(duration) && duration > 0 ? Math.clamp(position / duration, 0, 1) : preview ? .4 : 0;
    }

    /**
     * Mirror the frequency range around the ring to avoid a bass-to-treble discontinuity.
     */
    public static float magnitude(float[] levels, int bands, double turn) {
        double wrapped = turn - Math.floor(turn);
        double frequency = 1 - Math.abs(2 * wrapped - 1), step = 1.0 / (bands - 1);
        return .5f * SpectrumCurve.sample(levels, bands, frequency)
            + .25f * SpectrumCurve.sample(levels, bands, frequency - step)
            + .25f * SpectrumCurve.sample(levels, bands, frequency + step);
    }


}
