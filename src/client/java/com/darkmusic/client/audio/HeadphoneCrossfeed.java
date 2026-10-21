package com.darkmusic.client.audio;

/**
 * Feed-forward low-pass crossfeed with a 0.25 ms delay; no feedback and no mono widening.
 */
final class HeadphoneCrossfeed {
    private final double[][] delay = new double[2][128];
    private final double[] low = new double[2];
    private int cursor, length;
    private double coefficient;
    double left, right;

    void begin(double rate) {
        length = Math.clamp((int) Math.round(rate * .00025), 1, 127);
        coefficient = 1 - Math.exp(-2 * Math.PI * 700 / rate);
        cursor = 0;
        for (var d : delay) java.util.Arrays.fill(d, 0);
        java.util.Arrays.fill(low, 0);
    }

    void process(double l, double r, double amount) {
        low[0] += (l - low[0]) * coefficient;
        low[1] += (r - low[1]) * coefficient;
        double a = delay[0][cursor], b = delay[1][cursor];
        delay[0][cursor] = low[0];
        delay[1][cursor] = low[1];
        cursor = (cursor + 1) % length;
        left = (l + b * amount) / (1 + amount);
        right = (r + a * amount) / (1 + amount);
    }
}
