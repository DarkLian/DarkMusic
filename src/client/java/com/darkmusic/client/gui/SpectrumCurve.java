package com.darkmusic.client.gui;

/**
 * Bounded, monotonic interpolation of the actual frequency bands; no invented oscillation.
 */
public final class SpectrumCurve {
    private SpectrumCurve() {
    }

    public static float spike(float[] levels, int bands, double position) {
        double cell = Math.clamp(position, 0, 1) * bands;
        int band = Math.min(bands - 1, (int) cell);
        double phase = cell - band;
        return finite(levels[band]) * (float) (1 - Math.abs(2 * phase - 1));
    }

    public static float sample(float[] levels, int bands, double position) {
        double index = Math.clamp(position, 0, 1) * (bands - 1);
        int left = (int) index, right = Math.min(bands - 1, left + 1);
        double t = index - left;
        t = t * t * (3 - 2 * t);
        float a = finite(levels[left]), b = finite(levels[right]);
        return (float) (a + (b - a) * t);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0;
    }
}
