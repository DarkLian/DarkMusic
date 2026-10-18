package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;

/**
 * Small Schroeder network: damped parallel combs followed by two short all-pass diffusers.
 */
final class RoomReverb {
    private final float[][][] comb = new float[2][4][], allpass = new float[2][2][];
    private final int[][] combAt = new int[2][4], allAt = new int[2][2];
    private final double[][] damped = new double[2][4];
    private final double[] feedback = new double[4];
    final AudioSettings.Reverb mode;

    RoomReverb(double rate, AudioSettings.Reverb mode) {
        this.mode = mode;
        double room = mode == AudioSettings.Reverb.STUDIO ? .65 : mode == AudioSettings.Reverb.SMALL_ROOM ? 1 : 1.5;
        double decay = mode == AudioSettings.Reverb.STUDIO ? .3 : mode == AudioSettings.Reverb.SMALL_ROOM ? .7 : 1.8;
        double[] times = {.0297, .0371, .0411, .0437};
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 4; i++) {
                int n = Math.max(1, (int) (rate * times[i] * room) + c * 23);
                comb[c][i] = new float[n];
                feedback[i] = Math.pow(.001, n / (rate * decay));
            }
            allpass[c][0] = new float[Math.max(1, (int) (rate * .005) + c * 7)];
            allpass[c][1] = new float[Math.max(1, (int) (rate * .0017) + c * 5)];
        }
    }

    double sample(double input, int c) {
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            float[] line = comb[c][i];
            int at = combAt[c][i];
            double delayed = line[at];
            damped[c][i] += (delayed - damped[c][i]) * .65;
            line[at] = (float) (input + damped[c][i] * feedback[i]);
            combAt[c][i] = (at + 1) % line.length;
            sum += delayed * .25;
        }
        for (int i = 0; i < 2; i++) {
            float[] line = allpass[c][i];
            int at = allAt[c][i];
            double delayed = line[at], output = delayed - sum * .5;
            line[at] = (float) (sum + output * .5);
            allAt[c][i] = (at + 1) % line.length;
            sum = output;
        }
        double wet = mode == AudioSettings.Reverb.STUDIO ? .1 : mode == AudioSettings.Reverb.SMALL_ROOM ? .16 : .24;
        return input * (1 - wet) + sum * wet * .5;
    }
}
