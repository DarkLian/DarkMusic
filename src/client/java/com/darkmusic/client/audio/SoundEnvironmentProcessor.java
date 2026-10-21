package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements.Type;
import com.darkmusic.client.config.SoundEnvironmentSettings;
import com.darkmusic.client.config.SoundEnvironmentSettings.Parameters;

/**
 * Two bounded engines permit clickless preset changes without allocating in the sample loop.
 */
public final class SoundEnvironmentProcessor {
    private final Engine[] engines = {new Engine(), new Engine()};
    private SoundEnvironmentSettings requested = new SoundEnvironmentSettings(), applied;
    private double rate, wet, smoothing, transition;
    private int current;
    private boolean transitioning, ready, wasActive;
    public double left, right;

    public void begin(double sampleRate) {
        rate = sampleRate;
        smoothing = 1 - Math.exp(-1 / (rate * .012));
        wet = 0;
        transition = 0;
        transitioning = false;
        applied = null;
        if (ready) for (var e : engines) e.initialize(rate);
        wasActive = false;
    }

    public void configure(SoundEnvironmentSettings settings) {
        requested = settings;
    }

    public void prepare() {
        if (ready) for (var engine : engines) engine.prepare();
        if (!requested.active()) return;
        if (!ready) {
            for (var e : engines) e.initialize(rate);
            ready = true;
        }
        if (applied == null) {
            engines[current].configure(requested);
            applied = requested;
        } else if (!transitioning && !sameEngine(applied, requested)) {
            var next = engines[1 - current];
            next.clear();
            next.configure(requested);
            applied = requested;
            transitioning = true;
            transition = 0;
        }
    }

    private static boolean sameEngine(SoundEnvironmentSettings a, SoundEnvironmentSettings b) {
        return a.preset == b.preset && a.texture == b.texture && a.ambience == b.ambience && a.parameters.same(b.parameters);
    }

    public boolean ownsReverb() {
        return requested.ownsReverb();
    }

    public boolean isDry() {
        return wet == 0 && !requested.active();
    }

    public int convolutionState() {
        if (!requested.active() || !requested.parameters.acoustic || requested.parameters.wet == 0
            || requested.parameters.reverbEngine != com.darkmusic.client.config.AudioEnhancements.ReverbEngine.CONVOLUTION)
            return 0;
        if (!ready || applied == null) return 1;
        var e = engines[transitioning ? 1 - current : current];
        return e.convolution == null ? 1 : e.convolution.unsupportedRate() ? 4 : e.convolution.failed() ? 3 : e.convolutionReady ? 2 : 1;
    }

    public double latencyMs() {
        return requested.active() ? requested.parameters.modulationMs * 2 + 2 * 1000 / rate : 0;
    }

    public double toneResponse(double frequency) {
        return ready && applied != null ? engines[current].response(frequency) : 0;
    }

    public void process(double l, double r, boolean bypass) {
        double target = requested.active() && !bypass ? requested.intensity : 0;
        wet += (target - wet) * smoothing;
        if (target == 0 && wet < 1e-8) wet = 0;
        if (wet == 0 || !ready || applied == null) {
            left = l;
            right = r;
            if (wasActive) {
                for (var e : engines) e.clear();
                applied = null;
                transitioning = false;
                wasActive = false;
            }
            return;
        }
        wasActive = true;
        var active = engines[current];
        active.process(l, r);
        double a = active.left, b = active.right;
        if (transitioning) {
            var next = engines[1 - current];
            next.process(l, r);
            transition = Math.min(1, transition + 1 / (rate * .025));
            a += (next.left - a) * transition;
            b += (next.right - b) * transition;
            if (transition == 1) {
                current = 1 - current;
                transitioning = false;
            }
        }
        left = l + (a - l) * wet;
        right = r + (b - r) * wet;
    }

    private static final class Engine {
        private final Biquad high = new Biquad(), low = new Biquad(), presence = new Biquad();
        private final EnvironmentRoom room = new EnvironmentRoom();
        private ConvolutionReverb convolution;
        private boolean convolutionReady;
        private double convolutionMix, convolutionSmoothing;
        private float[][] history, modulation;
        private Parameters p;
        private SoundEnvironmentSettings.Preset preset;
        private boolean texture, ambience;
        private double rate, trim, noiseGain, oscSin, oscCos = 1, incSin, incCos, wind, crackle;
        private double[] damping = new double[2];
        private int cursor, modAt, echoFrames;
        private long random;
        double left, right;

        void initialize(double sampleRate) {
            convolutionSmoothing = 1 - Math.exp(-1 / (sampleRate * .02));
            if (history == null || rate != sampleRate) {
                rate = sampleRate;
                history = new float[2][(int) (rate * 1.7) + 4];
                modulation = new float[2][(int) (rate * .007) + 8];
            }
            high.initialize(rate);
            low.initialize(rate);
            presence.initialize(rate);
            room.initialize(rate);
            clear();
        }

        void clear() {
            if (history == null) return;
            for (var h : history) java.util.Arrays.fill(h, 0);
            for (var h : modulation) java.util.Arrays.fill(h, 0);
            java.util.Arrays.fill(damping, 0);
            room.clear();
            high.reset();
            low.reset();
            presence.reset();
            cursor = modAt = 0;
            oscSin = wind = crackle = 0;
            convolutionMix = 0;
            oscCos = 1;
        }

        void configure(SoundEnvironmentSettings s) {
            p = s.parameters;
            preset = s.preset;
            texture = s.texture;
            ambience = s.ambience;
            high.configure(Type.HIGH_PASS, rate, p.highPass, 0, .707);
            high.snap();
            low.configure(Type.LOW_PASS, rate, p.lowPass * (1 - p.distance * .65), 0, .707);
            low.snap();
            presence.configure(Type.PEAK, rate, p.presenceFrequency, p.presenceGain, 1.1);
            presence.snap();
            room.configure(p);
            echoFrames = Math.max(1, (int) (rate * p.echoTime));
            convolution = p.acoustic && p.wet > 0 && p.reverbEngine == com.darkmusic.client.config.AudioEnhancements.ReverbEngine.CONVOLUTION
                ? new ConvolutionReverb(rate, ConvolutionReverb.Impulse.valueOf(p.impulse.name())) : null;
            convolutionReady = false;
            double reflectionGain = 0;
            for (double v : p.reflectionGains) reflectionGain += Math.abs(v);
            // Reserve filter/transient headroom and the maximum reflection sends.
            // This is gain staging, not a second limiter or automatic normalization.
            trim = .8 * Math.pow(10, (-p.distance * 9 - Math.max(0, p.presenceGain)) / 20)
                / (1 + reflectionGain + p.echoGain / .65 + p.wet);
            noiseGain = Math.pow(10, p.noiseDb / 20);
            random = 0x41c64e6dL + 991L * preset.ordinal();
            double angle = 2 * Math.PI * p.modulationHz / rate;
            incSin = Math.sin(angle);
            incCos = Math.cos(angle);
        }

        private double noise() {
            random ^= random << 13;
            random ^= random >>> 7;
            random ^= random << 17;
            return (random >>> 11) * 0x1.0p-52 - 1;
        }

        void prepare() {
            convolutionReady = convolution != null && convolution.ready();
        }

        void process(double l, double r) {
            convolutionMix += ((convolutionReady ? 1 : 0) - convolutionMix) * convolutionSmoothing;
            if (p.mono) l = r = (l + r) * .5;
            l = presence.sample(high.sample(l, 0), 0);
            r = presence.sample(high.sample(r, 1), 1);
            if (p.drive > 0) {
                double divisor = Math.tanh(1 + p.drive);
                l = Math.tanh(l * (1 + p.drive)) / divisor;
                r = Math.tanh(r * (1 + p.drive)) / divisor;
            }
            double generated = 0;
            if (texture) {
                double n = noise();
                if (preset == SoundEnvironmentSettings.Preset.VINYL) {
                    if (n > .9991) crackle = noise() * noiseGain * 8;
                    crackle *= .97;
                    generated = crackle + n * noiseGain * .12;
                } else generated = n * noiseGain;
            }
            if (ambience) {
                wind += (noise() - wind) * .001;
                generated += wind * noiseGain * 4;
                if (preset == SoundEnvironmentSettings.Preset.FOREST_CLEARING)
                    generated += Math.sin(oscSin * 38) * Math.pow(Math.max(0, oscCos), 16) * noiseGain * .3;
                if (preset == SoundEnvironmentSettings.Preset.UNDERWATER)
                    generated += Math.sin(oscSin * 13) * wind * noiseGain;
            }
            l = low.sample(l + generated, 0);
            r = low.sample(r + generated, 1);
            if (p.modulationMs > 0) {
                double depth = p.modulationMs * rate / 1000, delay = 2 + depth * (1 + oscSin);
                for (int c = 0; c < 2; c++) {
                    double value = c == 0 ? l : r;
                    modulation[c][modAt] = (float) value;
                    int whole = (int) delay;
                    double fraction = delay - whole;
                    double a = modulation[c][Math.floorMod(modAt - whole, modulation[c].length)];
                    double b = modulation[c][Math.floorMod(modAt - whole - 1, modulation[c].length)];
                    if (c == 0) l = a + (b - a) * fraction;
                    else r = a + (b - a) * fraction;
                }
                modAt = (modAt + 1) % modulation[0].length;
            }
            for (int c = 0; c < 2; c++) {
                double x = c == 0 ? l : r;
                double echo = history[c][Math.floorMod(cursor - echoFrames, history[c].length)];
                damping[c] += (echo - damping[c]) * (1 - p.damping);
                history[c][cursor] = (float) (x + damping[c] * .35 * p.echoGain);
                double output = x + damping[c] * p.echoGain;
                for (int i = 0; i < p.reflectionTimes.length; i++)
                    output += history[c][Math.floorMod(cursor - (int) (rate * p.reflectionTimes[i]), history[c].length)] * p.reflectionGains[i];
                if (p.acoustic && p.wet > 0) {
                    double reverberated = room.sample(x, c);
                    if (convolutionReady) reverberated += (convolution.sample(x, c) - reverberated) * convolutionMix;
                    output = output * (1 - p.wet) + reverberated * p.wet * .5;
                }
                output *= trim * (c == 0 ? 1 - Math.max(0, p.pan) * .7 : 1 + Math.min(0, p.pan) * .7);
                if (c == 0) left = output;
                else right = output;
            }
            if (p.acoustic && p.wet > 0) room.advance();
            cursor = (cursor + 1) % history[0].length;
            double next = oscSin * incCos + oscCos * incSin;
            oscCos = oscCos * incCos - oscSin * incSin;
            oscSin = next;
        }

        double response(double hz) {
            return high.response(hz, rate, false) + presence.response(hz, rate, false) + low.response(hz, rate, false);
        }
    }
}
