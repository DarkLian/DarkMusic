package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.SoundEnvironmentSettings;

/**
 * Worker-owned staged DSP. Configuration and buffer preparation happen between PCM blocks.
 */
public final class AudioProcessor {
    public static final double[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private final FilterRack filters = new FilterRack();
    private final DynamicBand[] dynamic = {new DynamicBand(), new DynamicBand()};
    private final DynamicBand deEsser = new DynamicBand();
    private final AudioDynamics compressor = new AudioDynamics();
    private final HeadphoneCrossfeed crossfeed = new HeadphoneCrossfeed();
    private final SoundEnvironmentProcessor environment = new SoundEnvironmentProcessor();
    private final TruePeakLimiter protection = new TruePeakLimiter();
    private final AudioDiagnostics diagnostics = new AudioDiagnostics();
    private AudioSettings settings = new AudioSettings(), applied;
    private SoundEnvironmentSettings surroundings = new SoundEnvironmentSettings();
    private AudioComparison comparison = AudioComparison.OFF;
    private final MusicReverb reverb = new MusicReverb();
    private int channels;
    private double rate, gain = 1, duck = 1, headroom = 1, width = 1, aoWet, protectedWet, smoothing, slowSmoothing;
    private double dryEnergy, wetEnergy, levelGain = 1, limiterReduction, estimatedPeak;
    private double feed, compressionWet;

    public void configure(AudioSettings value) {
        settings = value;
    }

    public void configure(AudioSettings value, SoundEnvironmentSettings space) {
        settings = value;
        surroundings = space;
    }

    public void comparison(AudioComparison value) {
        comparison = value;
    }

    public AudioDiagnostics diagnostics() {
        return diagnostics;
    }

    public void begin(double sampleRate, int channelCount) {
        begin(sampleRate, channelCount, false);
    }

    void begin(double sampleRate, int channelCount, boolean handoff) {
        // A gapless/crossfade promotion is one uninterrupted device stream, not a fresh DSP session.
        if (handoff && rate == sampleRate && channels == channelCount) return;
        rate = sampleRate;
        channels = channelCount;
        applied = null;
        reverb.begin(rate);
        filters.begin(rate);
        for (var band : dynamic) band.begin(rate);
        deEsser.begin(rate);
        compressor.begin();
        crossfeed.begin(rate);
        environment.begin(rate);
        protection.begin(rate);
        protectedWet = 0;
        diagnostics.begin(rate, channels);
        smoothing = 1 - Math.exp(-1 / (rate * .012));
        slowSmoothing = 1 - Math.exp(-1 / (rate * .1));
        aoWet = settings.neutral ? 0 : 1;
        dryEnergy = wetEnergy = feed = compressionWet = 0;
        levelGain = 1;
        width = 1;
    }

    private void prepare() {
        if (applied != settings) {
            boolean first = applied == null;
            filters.configure(settings, first);
            compressor.configure(settings.enhancements, rate);
            for (int i = 0; i < dynamic.length; i++) dynamic[i].configure(settings.enhancements.dynamic[i]);
            var e = settings.enhancements;
            var d = new AudioEnhancements.Node();
            d.enabled = e.deEsser;
            d.frequency = e.deEsserFrequency;
            d.q = 1;
            d.threshold = e.deEsserThreshold;
            d.range = -12 * e.deEsserStrength;
            d.attack = .002;
            d.release = .08;
            deEsser.configure(d);
            if (first) {
                headroom = filters.headroom();
                width = settings.width;
                aoWet = settings.neutral ? 0 : 1;
                protectedWet = !settings.neutral && settings.limiter && e.protection == AudioEnhancements.Protection.TRUE_PEAK ? 1 : 0;
                compressionWet = settings.compressor ? 1 : 0;
                feed = e.crossfeed ? e.crossfeedAmount : 0;
            }
            applied = settings;
        }
        reverb.prepare(settings, surroundings.ownsReverb());
        for (var band : dynamic) band.block();
        deEsser.block();
        environment.configure(surroundings);
        environment.prepare();
    }

    public void process(float[] pcm, int count, double volume, double normalization) {
        if (rate <= 0 || channels < 1 || count < 0 || count > pcm.length || count % channels != 0)
            throw new IllegalArgumentException("PCM block");
        prepare();
        var s = settings;
        var e = s.enhancements;
        var audition = comparison;
        boolean bypassAo = audition.bypassed() && audition.mode() != AudioComparison.Mode.ENVIRONMENT;
        boolean bypassEnvironment = audition.bypassed() && audition.mode() != AudioComparison.Mode.AUDIO_OPTIONS;
        boolean effects = !s.neutral;
        double target = volume * (effects && s.normalization ? normalization : 1);
        if (s.listening == AudioSettings.Listening.AMBIENT) target *= s.ambientGain;
        double duckTarget = s.ducking && ListeningMix.ducking() ? s.duckGain : 1;
        double duckSmoothing = 1 - Math.exp(-1 / (rate * (duckTarget < duck ? s.duckAttack : s.duckRelease)));
        double gainSmoothing = 1 - Math.exp(-1 / (rate * .02));
        if (s.neutral && aoWet == 0 && protectedWet == 0 && environment.isDry()
            && diagnostics.panels() == 0 && !audition.levelMatched()) {
            for (int i = 0; i < count; i += channels) {
                gain += (target - gain) * gainSmoothing;
                duck += (duckTarget - duck) * duckSmoothing;
                for (int c = 0; c < channels; c++) {
                    double value = pcm[i + c] * gain * duck;
                    pcm[i + c] = (float) (Double.isFinite(value) ? value : 0);
                }
            }
            return;
        }
        boolean truePeak = effects && s.limiter && e.protection == AudioEnhancements.Protection.TRUE_PEAK;
        for (int i = 0; i < count; i += channels) {
            gain += (target - gain) * gainSmoothing;
            duck += (duckTarget - duck) * duckSmoothing;
            double originalL = pcm[i], originalR = channels == 2 ? pcm[i + 1] : originalL;
            diagnostics.capture(0, i, originalL, originalR);
            double l = originalL, r = originalR;
            headroom += (filters.headroom() - headroom) * smoothing;
            width += (s.width - width) * smoothing;
            feed += ((e.crossfeed ? e.crossfeedAmount : 0) - feed) * smoothing;
            compressionWet += ((s.compressor ? 1 : 0) - compressionWet) * smoothing;
            if (effects || aoWet > 1e-8) {
                l = filters.sample(l * headroom, 0);
                r = filters.sample(r * headroom, 1);
                for (var band : dynamic) {
                    l = band.sample(l, 0, true);
                    r = band.sample(r, 1, true);
                }
                l = deEsser.sample(l, 0, true);
                r = deEsser.sample(r, 1, true);
                if (channels == 2) {
                    double mid = (l + r) * .5, side = (l - r) * .5 * width, trim = 1 / Math.max(1, width);
                    l = (mid + side) * trim;
                    r = (mid - side) * trim;
                    if (feed > 1e-8) {
                        crossfeed.process(l, r, feed);
                        l = crossfeed.left;
                        r = crossfeed.right;
                    }
                }
                reverb.process(l, r);
                l = reverb.left;
                r = reverb.right;
                if (compressionWet > 1e-8) {
                    // Preserve the legacy detector's master/mix reference while applying
                    // its linked gain before Environment and the common master stage.
                    double amount = 1 + (compressor.apply(l * gain * duck, r * gain * duck) - 1) * compressionWet;
                    l *= amount;
                    r *= amount;
                }
            }
            aoWet += ((effects && !bypassAo ? 1 : 0) - aoWet) * smoothing;
            if (aoWet < 1e-8) aoWet = 0;
            l = originalL + (l - originalL) * aoWet;
            r = originalR + (r - originalR) * aoWet;
            if (audition.mode() == AudioComparison.Mode.AUDIO_OPTIONS) {
                double match = match(originalL, originalR, l, r, audition.levelMatched(), audition.bypassed());
                l *= match;
                r *= match;
            }
            diagnostics.capture(1, i, l, r);
            double environmentInputL = l, environmentInputR = r;
            environment.process(l, r, bypassEnvironment);
            l = environment.left;
            r = environment.right;
            if (audition.mode() != AudioComparison.Mode.AUDIO_OPTIONS) {
                double match = match(audition.mode() == AudioComparison.Mode.ENVIRONMENT ? environmentInputL : originalL,
                    audition.mode() == AudioComparison.Mode.ENVIRONMENT ? environmentInputR : originalR, l, r, audition.levelMatched(), audition.bypassed());
                l *= match;
                r *= match;
            }
            diagnostics.capture(2, i, l, r);
            l *= gain * duck;
            r *= gain * duck;
            if (truePeak || protectedWet > 1e-8) {
                protection.frame(l, r);
                protectedWet += ((truePeak ? 1 : 0) - protectedWet) * smoothing;
                if (protectedWet < 1e-8) protectedWet = 0;
                l += (protection.left - l) * protectedWet;
                r += (protection.right - r) * protectedWet;
                limiterReduction = protection.reductionDb;
                estimatedPeak = protection.estimatedPeak;
            } else {
                limiterReduction = 0;
                estimatedPeak = Math.max(Math.abs(l), Math.abs(r));
            }
            if (effects && s.limiter && !truePeak) {
                double before = Math.max(Math.abs(l), Math.abs(r));
                l = limit(l);
                r = limit(r);
                double after = Math.max(Math.abs(l), Math.abs(r));
                limiterReduction = before > 0 ? -AudioDynamics.db(after / before) : 0;
            }
            if (!Double.isFinite(l)) l = 0;
            if (!Double.isFinite(r)) r = 0;
            diagnostics.capture(3, i, l, r);
            pcm[i] = (float) l;
            if (channels == 2) pcm[i + 1] = (float) r;
        }
    }

    private static double limit(double x) {
        double a = Math.abs(x);
        return a <= .95 ? x : Math.copySign(.95 + .04 * Math.tanh((a - .95) / .04), x);
    }

    private double match(double inputL, double inputR, double outputL, double outputR, boolean enabled, boolean bypassed) {
        double desired = 1;
        if (enabled) {
            dryEnergy += ((inputL * inputL + inputR * inputR) * .5 - dryEnergy) * slowSmoothing;
            if (!bypassed) wetEnergy += ((outputL * outputL + outputR * outputR) * .5 - wetEnergy) * slowSmoothing;
            if (dryEnergy > 1e-12 && wetEnergy > 1e-12)
                desired = Math.clamp(Math.sqrt(bypassed ? wetEnergy / dryEnergy : dryEnergy / wetEnergy), .25, 1);
        }
        levelGain += (desired - levelGain) * smoothing;
        return levelGain;
    }

    public void initialVolume(double value) {
        gain = Math.clamp(value, 0, 1);
    }

    public double response(double hz, boolean staticOnly) {
        if (settings.neutral) return 0;
        double result = filters.response(hz, false);
        for (var band : dynamic) result += band.response(hz, staticOnly);
        return result + deEsser.response(hz, staticOnly);
    }

    public double headroom() {
        return settings.neutral ? 1 : headroom;
    }

    public double environmentResponse(double frequency) {
        return surroundings.active() ? environment.toneResponse(frequency) : 0;
    }

    public double detectorDb() {
        return !settings.neutral && settings.compressor ? compressor.detectorDb : Double.NaN;
    }

    public double reductionDb() {
        return !settings.neutral && settings.compressor ? compressor.reductionDb : 0;
    }

    public double limiterReduction() {
        return limiterReduction;
    }

    public double truePeak() {
        return !settings.neutral && settings.limiter && settings.enhancements.protection == AudioEnhancements.Protection.TRUE_PEAK ? estimatedPeak : Double.NaN;
    }

    public int latencyFrames() {
        return protectedWet > .999 ? TruePeakLimiter.LATENCY : 0;
    }

    public boolean convolutionReady() {
        return convolutionState() == 2;
    }

    public int convolutionState() {
        if (surroundings.ownsReverb()) return environment.convolutionState();
        if (settings.neutral || settings.reverb == AudioSettings.Reverb.DRY || settings.enhancements.reverbAmount == 0
            || settings.enhancements.reverbEngine != AudioEnhancements.ReverbEngine.CONVOLUTION) return 0;
        return reverb.convolutionState();
    }

    public static void quantize(float[] input, byte[] out, int count) {
        quantize(input, out, count, 2);
    }

    public static void quantize(float[] input, byte[] out, int count, int sampleBytes) {
        quantize(input, out, count, sampleBytes, null);
    }

    /**
     * Converts to 16- or 24-bit PCM. With a dither source, 16-bit samples get TPDF dither; samples already on the
     * 16-bit grid, including digital silence, stay exact so unprocessed playback remains bit-perfect.
     */
    public static void quantize(float[] input, byte[] out, int count, int sampleBytes, Dither dither) {
        if (sampleBytes != 2 && sampleBytes != 3) throw new IllegalArgumentException("Unsupported output precision");
        long scale = 1L << (sampleBytes * 8 - 1);
        boolean dithered = dither != null && sampleBytes == 2;
        for (int i = 0; i < count; i++) {
            double value = input[i] * (double) scale;
            if (dithered && Math.abs(value - Math.rint(value)) > 1e-3) value += dither.next();
            int n = (int) Math.clamp(Math.round(value), -scale, scale - 1);
            for (int b = 0; b < sampleBytes; b++) out[sampleBytes * i + b] = (byte) (n >> (b * 8));
        }
    }

    /**
     * Triangular dither of up to one least significant bit, from an allocation-free xorshift generator.
     * One instance belongs to one output thread.
     */
    public static final class Dither {
        private long state;

        public Dither(long seed) {
            state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        }

        double next() {
            return uniform() - uniform();
        }

        private double uniform() {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            return (state >>> 11) * 0x1.0p-53;
        }
    }
}
