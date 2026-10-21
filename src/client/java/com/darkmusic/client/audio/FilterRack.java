package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements;
import com.darkmusic.client.config.AudioSettings;

/**
 * One active graphic/parametric bank plus independently enabled tone shelves.
 */
final class FilterRack {
    private final Biquad[] filters = new Biquad[17];
    private double rate, targetHeadroom = 1;

    FilterRack() {
        for (int i = 0; i < filters.length; i++) filters[i] = new Biquad();
    }

    void begin(double sampleRate) {
        rate = sampleRate;
        for (var b : filters) b.initialize(rate);
    }

    void configure(AudioSettings s, boolean snap) {
        var e = s.enhancements;
        double maxBoost = 0;
        for (int i = 0; i < 10; i++) {
            double db = (s.equalizer && e.eqMode == AudioEnhancements.EqMode.GRAPHIC ? s.bands[i] : 0) + (i < 3 ? s.bass * (3 - i) / 3 : 0);
            filters[i].configure(AudioEnhancements.Type.PEAK, rate, AudioProcessor.FREQUENCIES[i], db, 1.4);
            maxBoost = Math.max(maxBoost, db);
        }
        for (int i = 0; i < 5; i++) {
            var n = e.nodes[i];
            if (s.equalizer && e.eqMode == AudioEnhancements.EqMode.PARAMETRIC && n.enabled) {
                filters[10 + i].configure(n.type, rate, n.frequency, n.gain, n.q);
                maxBoost = Math.max(maxBoost, n.gain);
            } else filters[10 + i].identity();
        }
        if (e.bassTone) {
            filters[15].configure(AudioEnhancements.Type.LOW_SHELF, rate, e.bassFrequency, e.bassGain, .707);
            maxBoost = Math.max(maxBoost, e.bassGain);
        } else filters[15].identity();
        if (e.trebleTone) {
            filters[16].configure(AudioEnhancements.Type.HIGH_SHELF, rate, e.trebleFrequency, e.trebleGain, .707);
            maxBoost = Math.max(maxBoost, e.trebleGain);
        } else filters[16].identity();
        double peak = maxBoost;
        for (int i = 0; i < 384; i++)
            peak = Math.max(peak, response(20 * Math.pow(Math.max(20, Math.min(20000, rate * .43)) / 20, i / 383.0), true));
        for (var n : e.nodes) if (n.enabled) peak = Math.max(peak, response(Math.min(n.frequency, rate * .43), true));
        if (peak > .01) peak += .5;
        if (s.compressor) peak += e.makeup;
        // Reserve the possible positive dynamic gain as well as the static cascade.
        for (var n : e.dynamic) if (n.enabled) peak += Math.max(0, n.gain) + Math.max(0, n.range);
        targetHeadroom = Math.pow(10, -Math.max(0, peak) / 20);
        if (snap) for (var b : filters) b.snap();
    }

    double response(double hz, boolean desired) {
        double db = 0;
        for (var b : filters) db += b.response(hz, rate, desired);
        return db;
    }

    double sample(double value, int channel) {
        for (var b : filters) value = b.sample(value, channel);
        return value;
    }

    double headroom() {
        return targetHeadroom;
    }
}
