package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements;
import com.darkmusic.client.config.AudioSettings;

/**
 * Audio Options' shareable reverb slot. Two bounded engines fade mode/IR changes.
 */
final class MusicReverb {
    private final Engine[] engines = new Engine[2];
    private int current;
    private double rate, amount, target, smoothing, transition, transitionStep;
    private boolean transitioning, initialized;
    double left, right;

    void begin(double sampleRate) {
        rate = sampleRate;
        current = 0;
        amount = target = transition = 0;
        smoothing = 1 - Math.exp(-1 / (rate * .012));
        transitionStep = 1 / (rate * .025);
        engines[0] = engines[1] = null;
        transitioning = initialized = false;
    }

    void prepare(AudioSettings settings, boolean environmentOwns) {
        var e = settings.enhancements;
        target = !settings.neutral && !environmentOwns && settings.reverb != AudioSettings.Reverb.DRY ? e.reverbAmount : 0;
        if (!initialized) {
            amount = target;
            initialized = true;
        }
        if (target == 0 && amount == 0) {
            engines[0] = engines[1] = null;
            transitioning = false;
            return;
        }
        if (target > 0) {
            if (engines[current] == null) engines[current] = new Engine(rate, settings);
            else if (!transitioning && !engines[current].matches(settings)) {
                engines[1 - current] = new Engine(rate, settings);
                transitioning = true;
                transition = 0;
            }
        }
        for (var engine : engines) if (engine != null) engine.prepare();
    }

    void process(double l, double r) {
        amount += (target - amount) * smoothing;
        if (target == 0 && amount < 1e-8) amount = 0;
        if (amount == 0 || engines[current] == null) {
            left = l;
            right = r;
            return;
        }
        var a = engines[current];
        a.process(l, r);
        double wetL = a.left, wetR = a.right;
        if (transitioning) {
            var b = engines[1 - current];
            b.process(l, r);
            transition = Math.min(1, transition + transitionStep);
            wetL += (b.left - wetL) * transition;
            wetR += (b.right - wetR) * transition;
            if (transition == 1) {
                engines[current] = null;
                current = 1 - current;
                transitioning = false;
            }
        }
        left = l + (wetL - l) * amount;
        right = r + (wetR - r) * amount;
    }

    int convolutionState() {
        if (target == 0) return 0;
        var selected = engines[transitioning ? 1 - current : current];
        if (selected == null || selected.convolution == null) return 0;
        return selected.convolution.unsupportedRate() ? 4 : selected.convolution.failed() ? 3 : selected.ready ? 2 : 1;
    }

    private static final class Engine {
        private final AudioSettings.Reverb mode;
        private final AudioEnhancements.ReverbEngine engine;
        private final AudioEnhancements.Impulse impulse;
        private final RoomReverb room;
        private final ConvolutionReverb convolution;
        private final double smoothing;
        private boolean ready;
        private double blend;
        double left, right;

        Engine(double rate, AudioSettings settings) {
            mode = settings.reverb;
            engine = settings.enhancements.reverbEngine;
            impulse = settings.enhancements.impulse;
            room = new RoomReverb(rate, mode);
            convolution = engine == AudioEnhancements.ReverbEngine.CONVOLUTION ? new ConvolutionReverb(rate, ConvolutionReverb.Impulse.valueOf(impulse.name())) : null;
            smoothing = 1 - Math.exp(-1 / (rate * .02));
        }

        boolean matches(AudioSettings s) {
            return mode == s.reverb && engine == s.enhancements.reverbEngine && impulse == s.enhancements.impulse;
        }

        void prepare() {
            ready = convolution != null && convolution.ready();
        }

        void process(double l, double r) {
            left = room.sample(l, 0);
            right = room.sample(r, 1);
            blend += ((ready ? 1 : 0) - blend) * smoothing;
            if (ready) {
                left += (l * .8 + convolution.sample(l, 0) * .2 - left) * blend;
                right += (r * .8 + convolution.sample(r, 1) * .2 - right) * blend;
            }
        }
    }
}
