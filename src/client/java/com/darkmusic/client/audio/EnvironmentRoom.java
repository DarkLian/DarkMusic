package com.darkmusic.client.audio;

import com.darkmusic.client.config.SoundEnvironmentSettings.Parameters;

/**
 * Parameterized Schroeder space. Buffers are allocated at rate initialization, never per sample.
 */
final class EnvironmentRoom {
    private final float[][][] comb = new float[2][4][], allpass = new float[2][2][];
    private final int[][] at = new int[2][4], allAt = new int[2][2], length = new int[2][4], allLength = new int[2][2];
    private final double[][] low = new double[2][4], feedback = new double[2][4];
    private float[][] predelay;
    private int preAt, preLength;
    private double rate, damping, targetDamping, smoothing;
    private final double[][] targetFeedback = new double[2][4];

    void initialize(double sampleRate) {
        if (rate != sampleRate) {
            rate = sampleRate;
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < 4; i++) comb[c][i] = new float[(int) (rate * .13) + 64];
                for (int i = 0; i < 2; i++) allpass[c][i] = new float[(int) (rate * .017) + 64];
            }
            predelay = new float[2][(int) (rate * .25) + 1];
        }
        smoothing = 1 - Math.exp(-1 / (sampleRate * .012));
        clear();
    }

    void clear() {
        if (predelay == null) return;
        for (int c = 0; c < 2; c++) {
            java.util.Arrays.fill(predelay[c], 0);
            for (int i = 0; i < 4; i++) {
                java.util.Arrays.fill(comb[c][i], 0);
                at[c][i] = 0;
                low[c][i] = 0;
            }
            for (int i = 0; i < 2; i++) {
                java.util.Arrays.fill(allpass[c][i], 0);
                allAt[c][i] = 0;
            }
        }
        preAt = 0;
    }

    void configure(Parameters p, boolean snap) {
        double[] times = {.0297, .0371, .0411, .0437};
        targetDamping = 1 - p.damping;
        if (snap) damping = targetDamping;
        preLength = Math.clamp((int) (rate * p.predelay), 0, predelay[0].length - 1);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 4; i++) {
                length[c][i] = Math.clamp((int) (rate * times[i] * p.roomSize) + c * 23, 1, comb[c][i].length);
                targetFeedback[c][i] = Math.min(.98, Math.pow(.001, length[c][i] / (rate * p.decay)));
                if (snap) feedback[c][i] = targetFeedback[c][i];
            }
            allLength[c][0] = Math.clamp((int) (rate * .005 * Math.sqrt(p.roomSize)) + c * 7, 1, allpass[c][0].length);
            allLength[c][1] = Math.clamp((int) (rate * .0017 * Math.sqrt(p.roomSize)) + c * 5, 1, allpass[c][1].length);
        }
    }

    double sample(double input, int c) {
        if (c == 0) damping += (targetDamping - damping) * smoothing;
        if (preLength > 0) {
            int read = Math.floorMod(preAt - preLength, predelay[c].length);
            double delayed = predelay[c][read];
            predelay[c][preAt] = (float) input;
            input = delayed;
        }
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            int pos = at[c][i];
            double delayed = comb[c][i][pos];
            feedback[c][i] += (targetFeedback[c][i] - feedback[c][i]) * smoothing;
            low[c][i] += (delayed - low[c][i]) * damping;
            comb[c][i][pos] = (float) (input * (1 - feedback[c][i]) + low[c][i] * feedback[c][i]);
            at[c][i] = (pos + 1) % length[c][i];
            sum += delayed * .25;
        }
        for (int i = 0; i < 2; i++) {
            int pos = allAt[c][i];
            double delayed = allpass[c][i][pos], out = delayed - sum * .5;
            allpass[c][i][pos] = (float) (sum + out * .5);
            allAt[c][i] = (pos + 1) % allLength[c][i];
            sum = out;
        }
        return sum;
    }

    void advance() {
        preAt = (preAt + 1) % predelay[0].length;
    }
}
