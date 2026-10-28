package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements;
import com.darkmusic.client.config.AudioEnhancements.Type;
import com.darkmusic.client.config.SoundEnvironmentSettings;
import com.darkmusic.client.config.SoundEnvironmentSettings.Parameters;

import java.util.Arrays;

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
            engines[current].configure(requested, true);
            applied = requested;
        } else if (!sameEngine(applied, requested)) {
            if (sameStructure(applied, requested)) {
                // Keep delay/filter/convolution histories while continuous controls move.
                engines[transitioning ? 1 - current : current].configure(requested, false);
                applied = requested;
            } else if (!transitioning) {
                var next = engines[1 - current];
                next.clear();
                next.configure(requested, true);
                applied = requested;
                transitioning = true;
                transition = 0;
            }
        }
    }

    private static boolean sameEngine(SoundEnvironmentSettings a, SoundEnvironmentSettings b) {
        return a.preset == b.preset && a.texture == b.texture && a.ambience == b.ambience && a.parameters.same(b.parameters);
    }

    private static boolean sameStructure(SoundEnvironmentSettings a, SoundEnvironmentSettings b) {
        var x = a.parameters;
        var y = b.parameters;
        return a.preset == b.preset && a.texture == b.texture && a.ambience == b.ambience && x.mono == y.mono && x.acoustic == y.acoustic &&
            x.reverbEngine == y.reverbEngine && x.impulse == y.impulse && x.roomSize == y.roomSize && x.predelay == y.predelay &&
            x.echoTime == y.echoTime && x.modulationMs == y.modulationMs && Arrays.equals(x.reflectionTimes, y.reflectionTimes);
    }

    public boolean isDry() {
        return wet == 0 && !requested.active();
    }

    public int convolutionState() {
        if (!requested.active() || !requested.parameters.acoustic || requested.parameters.wet == 0
            || requested.parameters.reverbEngine != AudioEnhancements.ReverbEngine.CONVOLUTION)
            return 0;
        if (!ready || applied == null) return 1;
        var e = engines[transitioning ? 1 - current : current];
        return e.convolution == null ? 1 : e.convolution.unsupportedRate() ? 4 : e.convolution.failed() ? 3 : e.convolutionReady ? 2 : 1;
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
        private boolean convolutionReady, acousticWasAudible;
        // At their slider ends (20 Hz, 20 kHz) the filters are off rather than ringing on transients.
        private boolean highOn, lowOn;
        private double convolutionMix, convolutionSmoothing;
        private float[][] history, modulation;
        private Parameters p;
        private SoundEnvironmentSettings.Preset preset;
        private boolean texture, ambience;
        private double rate, trim, noiseGain, oscSin, oscCos = 1, incSin, incCos, wind, crackle;
        private final double[] damping = new double[2];
        private final double[] reflectionGains = new double[8];
        private double targetTrim, targetNoise, parameterSmoothing, drive, pan, echoGain, reverbWet, dampingAmount;
        private int cursor, modAt, echoFrames;
        private long random;
        double left, right;

        void initialize(double sampleRate) {
            convolutionSmoothing = 1 - Math.exp(-1 / (sampleRate * .02));
            parameterSmoothing = 1 - Math.exp(-1 / (sampleRate * .012));
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
            for (var h : history) Arrays.fill(h, 0);
            for (var h : modulation) Arrays.fill(h, 0);
            Arrays.fill(damping, 0);
            room.clear();
            high.reset();
            low.reset();
            presence.reset();
            cursor = modAt = 0;
            oscSin = wind = crackle = 0;
            convolutionMix = 0;
            acousticWasAudible = false;
            oscCos = 1;
        }

        void configure(SoundEnvironmentSettings s, boolean snap) {
            p = s.parameters;
            preset = s.preset;
            texture = s.texture;
            ambience = s.ambience;
            high.configure(Type.HIGH_PASS, rate, p.highPass, 0, .707);
            highOn = p.highPass > 20 + 1e-6;
            if (snap) high.snap();
            low.configure(Type.LOW_PASS, rate, p.lowPass * (1 - p.distance * .65), 0, .707);
            lowOn = p.lowPass * (1 - p.distance * .65) < 20000 - 1e-6;
            if (snap) low.snap();
            presence.configure(Type.PEAK, rate, p.presenceFrequency, p.presenceGain, 1.1);
            if (snap) presence.snap();
            room.configure(p, snap);
            echoFrames = Math.max(1, (int) (rate * p.echoTime));
            if (snap || convolution == null) {
                convolution = p.acoustic && p.wet > 0 && p.reverbEngine == AudioEnhancements.ReverbEngine.CONVOLUTION
                    ? new ConvolutionReverb(rate, ConvolutionReverb.Impulse.valueOf(p.impulse.name())) : null;
                convolutionReady = false;
            }
            double reflectionGain = 0;
            for (double v : p.reflectionGains) reflectionGain += Math.abs(v);
            // Reserve filter/transient headroom and the maximum reflection sends.
            // This is gain staging, not a second limiter or automatic normalization.
            targetTrim = .8 * Math.pow(10, (-p.distance * 9 - Math.max(0, p.presenceGain)) / 20)
                / (1 + reflectionGain + p.echoGain / .65 + p.wet);
            targetNoise = Math.pow(10, p.noiseDb / 20);
            if (snap) {
                trim = targetTrim;
                noiseGain = targetNoise;
                drive = p.drive;
                pan = p.pan;
                echoGain = p.echoGain;
                reverbWet = p.wet;
                dampingAmount = p.damping;
                for (int i = 0; i < reflectionGains.length; i++)
                    reflectionGains[i] = i < p.reflectionGains.length ? p.reflectionGains[i] : 0;
                random = 0x41c64e6dL + 991L * preset.ordinal();
            }
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
            trim += (targetTrim - trim) * parameterSmoothing;
            noiseGain += (targetNoise - noiseGain) * parameterSmoothing;
            drive += (p.drive - drive) * parameterSmoothing;
            pan += (p.pan - pan) * parameterSmoothing;
            echoGain += (p.echoGain - echoGain) * parameterSmoothing;
            reverbWet += (p.wet - reverbWet) * parameterSmoothing;
            if (p.wet == 0 && reverbWet < 1e-8) reverbWet = 0;
            if (reverbWet > 0) acousticWasAudible = true;
            else if (acousticWasAudible) {
                // Fully bypassed acoustics must not replay a frozen tail when re-enabled later.
                room.clear();
                convolution = null;
                convolutionReady = acousticWasAudible = false;
                convolutionMix = 0;
            }
            dampingAmount += (p.damping - dampingAmount) * parameterSmoothing;
            for (int i = 0; i < p.reflectionGains.length; i++)
                reflectionGains[i] += (p.reflectionGains[i] - reflectionGains[i]) * parameterSmoothing;
            convolutionMix += ((convolutionReady ? 1 : 0) - convolutionMix) * convolutionSmoothing;
            if (p.mono) l = r = (l + r) * .5;
            l = presence.sample(highOn ? high.sample(l, 0) : l, 0);
            r = presence.sample(highOn ? high.sample(r, 1) : r, 1);
            if (drive > 1e-8) {
                double divisor = Math.tanh(1 + drive);
                l = Math.tanh(l * (1 + drive)) / divisor;
                r = Math.tanh(r * (1 + drive)) / divisor;
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
            l = lowOn ? low.sample(l + generated, 0) : l + generated;
            r = lowOn ? low.sample(r + generated, 1) : r + generated;
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
                damping[c] += (echo - damping[c]) * (1 - dampingAmount);
                history[c][cursor] = (float) (x + damping[c] * .35 * echoGain);
                double output = x + damping[c] * echoGain;
                for (int i = 0; i < p.reflectionTimes.length; i++)
                    output += history[c][Math.floorMod(cursor - (int) (rate * p.reflectionTimes[i]), history[c].length)] * reflectionGains[i];
                if (p.acoustic && reverbWet > 0) {
                    double reverberated = room.sample(x, c);
                    if (convolutionReady) reverberated += (convolution.sample(x, c) - reverberated) * convolutionMix;
                    output = output * (1 - reverbWet) + reverberated * reverbWet * .5;
                }
                output *= trim * (c == 0 ? 1 - Math.max(0, pan) * .7 : 1 + Math.min(0, pan) * .7);
                if (c == 0) left = output;
                else right = output;
            }
            if (p.acoustic && reverbWet > 0) room.advance();
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
