package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements;

/**
 * Separate band detector and smoothly morphed peaking filter; no per-sample coefficient calculation.
 */
final class DynamicBand {
    private final Biquad detector = new Biquad(), filter = new Biquad(), nominal = new Biquad();
    private AudioEnhancements.Node settings;
    private double rate, envelope, attack, release, leftDetector;
    double dynamicGain;

    void begin(double sampleRate) {
        rate = sampleRate;
        envelope = dynamicGain = 0;
        detector.initialize(rate);
        filter.initialize(rate);
        nominal.initialize(rate);
    }

    void configure(AudioEnhancements.Node value) {
        settings = value;
        attack = 1 - Math.exp(-1 / (rate * value.attack));
        release = 1 - Math.exp(-1 / (rate * value.release));
        detector.configure(AudioEnhancements.Type.BAND_PASS, rate, value.frequency, 0, value.q);
        detector.snap();
        nominal.configure(AudioEnhancements.Type.PEAK, rate, value.frequency, value.gain, value.q);
        nominal.snap();
    }

    void block() {
        if (settings == null || !settings.enabled) {
            filter.identity();
            dynamicGain = 0;
            return;
        }
        double db = AudioDynamics.db(envelope);
        dynamicGain = settings.range * Math.clamp((db - settings.threshold) / 12, 0, 1);
        filter.configure(AudioEnhancements.Type.PEAK, rate, settings.frequency, settings.gain + dynamicGain, settings.q);
    }

    /**
     * One stereo sample: the envelope follows the louder channel and updates once both are in.
     */
    double sample(double x, int c) {
        if (settings == null || !settings.enabled) return x;
        double detected = Math.abs(detector.sample(x, c));
        if (c == 0) leftDetector = detected;
        if (c == 1) {
            double peak = Math.max(leftDetector, detected);
            envelope += (peak - envelope) * (peak > envelope ? attack : release);
        }
        return filter.sample(x, c);
    }

    double response(double frequency, boolean staticOnly) {
        return settings == null || !settings.enabled ? 0 : (staticOnly ? nominal : filter).response(frequency, rate, false);
    }
}
