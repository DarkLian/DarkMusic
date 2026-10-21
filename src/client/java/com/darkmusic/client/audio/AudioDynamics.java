package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements;

/**
 * Linked stereo envelope compressor; parameters and exponentials update only on configuration.
 */
final class AudioDynamics {
    private double envelope, gain = 1, attack, release, slew, threshold, ratio, knee, makeup;
    double detectorDb = -180, reductionDb;

    void begin() {
        envelope = 0;
        gain = 1;
        detectorDb = -180;
        reductionDb = 0;
    }

    void configure(AudioEnhancements e, double rate) {
        attack = 1 - Math.exp(-1 / (rate * e.attack));
        release = 1 - Math.exp(-1 / (rate * e.release));
        slew = 1 - Math.exp(-1 / (rate * .01));
        threshold = e.threshold;
        ratio = e.ratio;
        knee = e.knee;
        makeup = Math.pow(10, e.makeup / 20);
    }

    double apply(double left, double right) {
        double peak = Math.max(Math.abs(left), Math.abs(right));
        envelope += (peak - envelope) * (peak > envelope ? attack : release);
        detectorDb = db(envelope);
        double over = detectorDb - threshold, reduced;
        if (knee > 0 && over > -knee / 2 && over < knee / 2)
            reduced = (1 - 1 / ratio) * (over + knee / 2) * (over + knee / 2) / (2 * knee);
        else reduced = over > 0 ? (1 - 1 / ratio) * over : 0;
        double desired = Math.pow(10, -Math.clamp(reduced, 0, 72) / 20);
        gain += (desired - gain) * slew;
        reductionDb = -db(gain);
        return gain * makeup;
    }

    static double db(double amplitude) {
        return amplitude > 1e-12 ? 20 * Math.log10(amplitude) : Double.NEGATIVE_INFINITY;
    }
}
