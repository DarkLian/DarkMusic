package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements.Type;

import java.util.Arrays;

/**
 * RBJ filters with bounded frequency/Q and coefficient ramps; state belongs to one worker.
 */
public final class Biquad {
    private final double[] c = {1, 0, 0, 0, 0}, target = {1, 0, 0, 0, 0};
    private final double[] x1 = new double[2], x2 = new double[2], y1 = new double[2], y2 = new double[2];
    private double smoothing = .002;
    private boolean changing;

    public void initialize(double rate) {
        smoothing = 1 - Math.exp(-1 / (rate * .012));
        reset();
    }

    public void reset() {
        Arrays.fill(x1, 0);
        Arrays.fill(x2, 0);
        Arrays.fill(y1, 0);
        Arrays.fill(y2, 0);
    }

    public void identity() {
        set(1, 0, 0, 0, 0);
    }

    public void snap() {
        System.arraycopy(target, 0, c, 0, 5);
        changing = false;
    }

    public void configure(Type type, double rate, double frequency, double db, double q) {
        double w = 2 * Math.PI * Math.clamp(frequency, Math.min(20, rate * .01), rate * .43) / rate;
        double cos = Math.cos(w), sin = Math.sin(w), a = Math.pow(10, Math.clamp(db, -24, 24) / 40);
        double alpha = sin / (2 * Math.clamp(q, .25, 8)), b0, b1, b2, a0, a1, a2;
        switch (type) {
            case LOW_PASS -> {
                b0 = (1 - cos) / 2;
                b1 = 1 - cos;
                b2 = b0;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case HIGH_PASS -> {
                b0 = (1 + cos) / 2;
                b1 = -(1 + cos);
                b2 = b0;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case BAND_PASS -> {
                b0 = alpha;
                b1 = 0;
                b2 = -alpha;
                a0 = 1 + alpha;
                a1 = -2 * cos;
                a2 = 1 - alpha;
            }
            case LOW_SHELF -> {
                double root = 2 * Math.sqrt(a) * alpha;
                b0 = a * ((a + 1) - (a - 1) * cos + root);
                b1 = 2 * a * ((a - 1) - (a + 1) * cos);
                b2 = a * ((a + 1) - (a - 1) * cos - root);
                a0 = (a + 1) + (a - 1) * cos + root;
                a1 = -2 * ((a - 1) + (a + 1) * cos);
                a2 = (a + 1) + (a - 1) * cos - root;
            }
            case HIGH_SHELF -> {
                double root = 2 * Math.sqrt(a) * alpha;
                b0 = a * ((a + 1) + (a - 1) * cos + root);
                b1 = -2 * a * ((a - 1) + (a + 1) * cos);
                b2 = a * ((a + 1) + (a - 1) * cos - root);
                a0 = (a + 1) - (a - 1) * cos + root;
                a1 = 2 * ((a - 1) - (a + 1) * cos);
                a2 = (a + 1) - (a - 1) * cos - root;
            }
            default -> {
                b0 = 1 + alpha * a;
                b1 = -2 * cos;
                b2 = 1 - alpha * a;
                a0 = 1 + alpha / a;
                a1 = -2 * cos;
                a2 = 1 - alpha / a;
            }
        }
        set(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private void set(double b0, double b1, double b2, double a1, double a2) {
        target[0] = b0;
        target[1] = b1;
        target[2] = b2;
        target[3] = a1;
        target[4] = a2;
        changing = true;
    }

    public double sample(double value, int channel) {
        if (channel == 0 && changing) {
            changing = false;
            for (int i = 0; i < 5; i++) {
                double d = target[i] - c[i];
                if (Math.abs(d) > 1e-13) {
                    c[i] += d * smoothing;
                    changing = true;
                } else c[i] = target[i];
            }
        }
        if (!changing && c[0] == 1 && c[1] == 0 && c[2] == 0 && c[3] == 0 && c[4] == 0) return value;
        double out = c[0] * value + c[1] * x1[channel] + c[2] * x2[channel] - c[3] * y1[channel] - c[4] * y2[channel];
        if (!Double.isFinite(out) || Math.abs(out) > 1e12) {
            reset();
            return 0;
        }
        x2[channel] = x1[channel];
        x1[channel] = value;
        y2[channel] = y1[channel];
        y1[channel] = out;
        return out;
    }

    public double response(double frequency, double rate, boolean desired) {
        double[] v = desired ? target : c;
        double w = 2 * Math.PI * Math.clamp(frequency, 0, rate * .499) / rate, cos = Math.cos(w), cos2 = Math.cos(2 * w);
        double n = v[0] * v[0] + v[1] * v[1] + v[2] * v[2] + 2 * (v[0] * v[1] + v[1] * v[2]) * cos + 2 * v[0] * v[2] * cos2;
        double d = 1 + v[3] * v[3] + v[4] * v[4] + 2 * (v[3] + v[3] * v[4]) * cos + 2 * v[4] * cos2;
        return 10 * Math.log10(Math.max(1e-24, n) / Math.max(1e-24, d));
    }
}
