package com.darkmusic.client.audio;

import java.util.Arrays;

/**
 * Stereo-linked 8x windowed-sinc intersample detector with 64-frame lookahead.
 */
final class TruePeakLimiter {
    static final int LATENCY = 64, TAPS = 64, PHASES = 8;
    static final double CEILING = Math.pow(10, -1.0 / 20);
    private static final double[][] INTERPOLATION = interpolation();
    private final double[][] history = new double[2][128];
    private final double[] peaks = new double[128];
    private long frame;
    private double gain = 1, release;
    double left, right, estimatedPeak, reductionDb;

    void begin(double rate) {
        for (var h : history) Arrays.fill(h, 0);
        Arrays.fill(peaks, 0);
        frame = 0;
        gain = 1;
        release = 1 - Math.exp(-1 / (rate * .1));
    }

    void frame(double l, double r) {
        int index = (int) (frame & 127);
        history[0][index] = l;
        history[1][index] = r;
        double peak = Math.max(Math.abs(l), Math.abs(r));
        for (int c = 0; c < 2; c++)
            for (int phase = 1; phase < PHASES; phase++) {
                double value = 0;
                for (int j = 0; j < TAPS; j++) value += history[c][(index - j + 128) & 127] * INTERPOLATION[phase][j];
                peak = Math.max(peak, Math.abs(value));
            }
        peaks[index] = peak;
        double maximum = 0;
        for (int i = 0; i <= LATENCY; i++) maximum = Math.max(maximum, peaks[(index - i + 128) & 127]);
        double desired = maximum > 0 ? Math.min(1, CEILING * .97 / maximum) : 1;
        // The detector sees the transient ahead of the output; use that lead to
        // ramp attenuation rather than introduce an instantaneous gain step.
        gain += (desired - gain) * (desired < gain ? .2 : release);
        int out = (index - LATENCY + 128) & 127;
        left = frame >= LATENCY ? history[0][out] * gain : 0;
        right = frame >= LATENCY ? history[1][out] * gain : 0;
        estimatedPeak = peak;
        reductionDb = -20 * Math.log10(Math.max(1e-12, gain));
        frame++;
    }

    /**
     * Whether the output is real audio rather than the initial delay's silence.
     */
    boolean primed() {
        return frame >= LATENCY;
    }

    static double[][] interpolation() {
        double[][] result = new double[PHASES][TAPS];
        for (int phase = 0; phase < PHASES; phase++) {
            double sum = 0;
            for (int j = 0; j < TAPS; j++) {
                double x = j - (TAPS / 2 - 1) - phase / (double) PHASES;
                double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
                double window = .42 - .5 * Math.cos(2 * Math.PI * j / (TAPS - 1)) + .08 * Math.cos(4 * Math.PI * j / (TAPS - 1));
                result[phase][j] = sinc * window;
                sum += result[phase][j];
            }
            for (int j = 0; j < TAPS; j++) result[phase][j] /= sum;
        }
        return result;
    }
}
