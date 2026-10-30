package com.darkmusic.client.audio;

import com.darkmusic.client.config.*;
import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedAudioDspTest {
    @Test
    void wsolaChangesDurationWithoutChangingToneOrStereoPhase() throws Exception {
        for (double deviceRate : new double[]{44100, 48000, 96000})
            for (double speed : new double[]{.75, 1, 1.25}) {
                int frames = 96000;
                byte[] bytes = new byte[frames * 4];
                for (int i = 0; i < frames; i++) {
                    int value = (int) (12000 * Math.sin(2 * Math.PI * 440 * i / 48000));
                    for (int c = 0; c < 2; c++) {
                        int v = c == 0 ? value : -value;
                        bytes[i * 4 + c * 2] = (byte) v;
                        bytes[i * 4 + c * 2 + 1] = (byte) (v >> 8);
                    }
                }
                var format = new AudioFormat(48000, 16, 2, true, false);
                try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), format, frames)) {
                    var converter = new PcmRateConverter(stream);
                    float[] out = new float[2048];
                    long count = 0, positiveCrossings = 0;
                    double last = 0;
                    for (int n; (n = converter.read(out, out.length, speed, deviceRate, true)) >= 0; ) {
                        for (int i = 0; i < n; i += 2) {
                            assertEquals(-out[i], out[i + 1], 1e-5);
                            if (count > 4800 && last <= 0 && out[i] > 0) positiveCrossings++;
                            last = out[i];
                            count++;
                        }
                    }
                    assertEquals(frames * deviceRate / 48000 / speed, count, 32, "tempo duration");
                    assertEquals(440, positiveCrossings * deviceRate / (count - 4800), 4, "preserved pitch at " + speed);
                }
            }
    }

    @Test
    void wsolaKeepsLowSampleRateMonoContinuous() throws Exception {
        // A device opened at the file's own low rate gives a short hop; the history ring must still cover the look-ahead.
        for (int rate : new int[]{8000, 11025, 16000, 22050})
            for (int channels : new int[]{1, 2}) {
                int frames = rate * 4;
                byte[] bytes = new byte[frames * channels * 2];
                for (int i = 0; i < frames; i++) {
                    int value = (int) (12000 * Math.sin(2 * Math.PI * 440 * i / rate));
                    for (int c = 0; c < channels; c++) {
                        bytes[(i * channels + c) * 2] = (byte) value;
                        bytes[(i * channels + c) * 2 + 1] = (byte) (value >> 8);
                    }
                }
                var format = new AudioFormat(rate, 16, channels, true, false);
                try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), format, frames)) {
                    var converter = new PcmRateConverter(stream);
                    float[] out = new float[2048];
                    // A clean 440 Hz sine never moves further than this between neighbouring samples.
                    double limit = 2 * Math.PI * 440 / rate * (12000 / 32768.0) * 1.5 + 1e-3;
                    double last = Double.NaN;
                    long count = 0, jumps = 0;
                    for (int n; (n = converter.read(out, out.length, 1.25, rate, true)) >= 0; )
                        for (int i = 0; i < n; i += channels) {
                            if (!Double.isNaN(last) && Math.abs(out[i] - last) > limit) jumps++;
                            last = out[i];
                            count++;
                        }
                    assertEquals(0, jumps, "discontinuities at " + rate + " Hz, " + channels + " channel(s)");
                    assertEquals(frames / 1.25, count, 32, "tempo duration at " + rate + " Hz");
                }
            }
    }

    @Test
    void truePeakProtectsIntersampleOvershootWithLinkedStereoAndFiniteLatency() {
        for (double rate : new double[]{44100, 48000, 96000, 192000})
            for (double fraction : new double[]{.02077, .20833, .35417, .4375, .48958}) {
                double hz = rate * fraction;
                var limiter = new TruePeakLimiter();
                limiter.begin(rate);
                double[] out = new double[8000];
                for (int i = 0; i < out.length; i++) {
                    double x = 1.6 * Math.sin(2 * Math.PI * hz * i / rate + .7);
                    limiter.frame(x, -x * .7);
                    out[i] = limiter.left;
                    assertEquals(-limiter.left * .7, limiter.right, 1e-10);
                    assertTrue(Double.isFinite(limiter.left));
                }
                double peak = reconstructedPeak(out, 40);
                assertTrue(peak <= TruePeakLimiter.CEILING + 1e-3, "intersample ceiling at " + hz + ": " + peak);
            }
    }

    @Test
    void truePeakProtectsBroadbandBurstsAndSilenceTransitions() {
        var random = new Random(20260317);
        var limiter = new TruePeakLimiter();
        limiter.begin(48000);
        double[] out = new double[16000];
        for (int i = 0; i < out.length; i++) {
            double x = i % 3000 < 900 ? 0 : (random.nextDouble() * 2 - 1) * (i % 3000 < 1200 ? 3 : 1);
            limiter.frame(x, -x);
            out[i] = limiter.left;
        }
        double peak = reconstructedPeak(out, 40);
        assertTrue(peak <= TruePeakLimiter.CEILING + 1e-3, "Broadband reconstructed peak: " + peak);
    }

    @Test
    void truePeakToggleNeverMixesTheSignalWithItsDelayedCopy() {
        double rate = 44100;
        int window = 2 * TruePeakLimiter.LATENCY;
        // The delay is half a period of the first tone and a whole period of the second, so a crossfade between the
        // direct and the delayed signal would cancel one and reinforce the other. The transition must not care.
        double[] tones = {rate / window, rate / TruePeakLimiter.LATENCY};
        for (boolean enable : new boolean[]{true, false}) {
            double[][] envelopes = new double[tones.length][];
            double[] energy = new double[tones.length];
            for (int t = 0; t < tones.length; t++) {
                var p = new AudioProcessor();
                p.configure(truePeak(!enable));
                p.begin(rate, 2);
                p.initialVolume(1);
                var rms = new ArrayList<Double>();
                for (int block = 0; block < 120; block++) {
                    if (block == 80) {
                        assertEquals(enable ? 0 : TruePeakLimiter.LATENCY, p.latencyFrames());
                        p.configure(truePeak(enable));
                    }
                    float[] pcm = new float[1024];
                    for (int i = 0; i < 512; i++)
                        pcm[2 * i] = pcm[2 * i + 1] = (float) (.1 * Math.sin(2 * Math.PI * tones[t] * (block * 512 + i) / rate));
                    p.process(pcm, pcm.length, 1, 1);
                    if (block >= 80 && block < 90) for (int w = 0; w < 512; w += window) {
                        double sum = 0;
                        for (int i = w; i < w + window; i++) sum += pcm[2 * i] * pcm[2 * i];
                        energy[t] += sum;
                        rms.add(Math.sqrt(sum / window));
                    }
                }
                assertEquals(enable ? TruePeakLimiter.LATENCY : 0, p.latencyFrames());
                envelopes[t] = rms.stream().mapToDouble(Double::doubleValue).toArray();
            }
            double steady = .1 / Math.sqrt(2);
            assertEquals(steady, envelopes[0][envelopes[0].length - 1], steady * .01, "Settled after the toggle");
            for (int w = 0; w < envelopes[0].length; w++)
                assertEquals(envelopes[0][w], envelopes[1][w], steady * .2, "Envelope at window " + w + " (enable " + enable + ")");
            assertEquals(energy[0], energy[1], energy[1] * .01, "Transition loss must not depend on frequency (enable " + enable + ")");
        }
    }

    private static AudioSettings truePeak(boolean enabled) {
        var s = new AudioSettings();
        s.neutral = false;
        s.limiter = enabled;
        s.enhancements.protection = AudioEnhancements.Protection.TRUE_PEAK;
        s.validate();
        return s;
    }

    private static double reconstructedPeak(double[] signal, int start) {
        double peak = 0;
        // Independent 8x/64-tap Blackman reconstruction, different from the limiter detector.
        for (int i = start; i < signal.length - 64; i++)
            for (int phase = 0; phase < 8; phase++) {
                double sum = 0, weight = 0;
                for (int j = -31; j <= 32; j++) {
                    double x = j - phase / 8.0;
                    double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
                    double w = .42 + .5 * Math.cos(Math.PI * x / 32) + .08 * Math.cos(2 * Math.PI * x / 32);
                    sum += signal[i + j] * sinc * w;
                    weight += sinc * w;
                }
                peak = Math.max(peak, Math.abs(sum / weight));
            }
        return peak;
    }

    @Test
    void partitionedConvolutionMatchesItsActualStereoImpulseAndDecays() throws Exception {
        for (double rate : new double[]{44100, 48000, 96000, 192000})
            for (var mode : ConvolutionReverb.Impulse.values()) {
                var reverb = new ConvolutionReverb(rate, mode);
                Await.until(Duration.ofSeconds(10), reverb::ready, "impulse ready");
                double[][] impulse = reverb.impulseSamples();
                double error = 0, energy = 0;
                int frames = impulse[0].length + ConvolutionReverb.HOP * 3;
                for (int i = 0; i < frames; i++)
                    for (int c = 0; c < 2; c++) {
                        double result = reverb.sample(i == 0 ? 1 : 0, c);
                        int at = i - ConvolutionReverb.HOP;
                        double expected = at >= 0 && at < impulse[c].length ? impulse[c][at] : 0;
                        error = Math.max(error, Math.abs(result - expected));
                        energy += result * result;
                        assertTrue(Double.isFinite(result));
                    }
                assertTrue(error < 1e-10, "FFT convolution error " + error);
                assertTrue(energy > 1e-7);
            }
    }

    @Test
    void allEnvironmentChoicesAreDistinctAndIntensityZeroIsIdentity() {
        List<double[]> fingerprints = new ArrayList<>();
        for (var preset : SoundEnvironmentSettings.BUILT_IN) {
            var s = new SoundEnvironmentSettings();
            s.select(preset);
            var dsp = new SoundEnvironmentProcessor();
            dsp.begin(48000);
            dsp.configure(s);
            dsp.prepare();
            double[] print = new double[256];
            for (int i = 0; i < 96000; i++) {
                double l = i < 24000 ? .15 * (Math.sin(i * .013) + Math.sin(i * .17) + Math.sin(i * 1.7)) : 0;
                double r = i < 24000 ? .15 * Math.sin(i * .079) : 0;
                dsp.process(l, r, false);
                assertTrue(Double.isFinite(dsp.left) && Math.abs(dsp.left) < 2, preset.name());
                print[i % 256] += dsp.left * Math.sin(i * .031) + dsp.right * Math.cos(i * .017);
            }
            for (int p = 0; p < fingerprints.size(); p++) {
                double difference = 0;
                for (int i = 0; i < 256; i++) difference += Math.abs(print[i] - fingerprints.get(p)[i]);
                assertTrue(difference > 1e-4, preset + " duplicates " + SoundEnvironmentSettings.BUILT_IN.get(p));
            }
            fingerprints.add(print);
            s.intensity = 0;
            dsp.begin(48000);
            dsp.configure(s);
            dsp.prepare();
            for (int i = 0; i < 100; i++) {
                dsp.process(.2, -.3, false);
                assertEquals(.2, dsp.left);
                assertEquals(-.3, dsp.right);
            }
        }
        assertEquals(SoundEnvironmentSettings.BUILT_IN.size(), fingerprints.size());
    }

    @Test
    void neutralAndEnvironmentAreIndependentAndProfilesDoNotEraseEnvironment() {
        var s = new AudioSettings();
        s.neutral = true;
        s.equalizer = true;
        s.bands[5] = 12;
        var env = new SoundEnvironmentSettings();
        env.select(SoundEnvironmentSettings.Preset.TELEPHONE);
        var processor = new AudioProcessor();
        processor.configure(s, env);
        processor.begin(48000, 2);
        float[] input = new float[4096];
        for (int i = 0; i < input.length; i++) input[i] = (float) (.3 * Math.sin(i * .4));
        float[] dry = input.clone();
        processor.process(input, input.length, 1, 1);
        assertFalse(Arrays.equals(dry, input));
        s.selectProfile("MUSIC");
        assertEquals(SoundEnvironmentSettings.Preset.TELEPHONE, env.preset);
        assertTrue(s.analyzeLoudness);
        var copy = s.playbackCopy();
        copy.enhancements.nodes[0].gain = 8;
        assertNotEquals(copy.enhancements.nodes[0].gain, s.enhancements.nodes[0].gain);
    }

    @Test
    void diagnosticsOnlyPublishesActuallySubmittedDeviceClockWindows() {
        var p = new AudioProcessor();
        p.begin(48000, 2);
        var d = p.diagnostics();
        d.panels(AudioDiagnostics.ALL);
        long submitted = 0;
        for (int block = 0; block < 8; block++) {
            float[] pcm = new float[1024];
            for (int i = 0; i < 512; i++)
                pcm[2 * i] = pcm[2 * i + 1] = (float) (.5 * Math.sin(2 * Math.PI * 1000 * (submitted + i) / 48000));
            p.process(pcm, pcm.length, 1, 1);
            byte[] bytes = new byte[pcm.length * 3];
            AudioProcessor.quantize(pcm, bytes, pcm.length, 3);
            d.submitted(bytes, 0, bytes.length / 2, submitted, 3, p);
            submitted += 256;
            d.submitted(bytes, bytes.length / 2, bytes.length / 2, submitted, 3, p);
            submitted += 256;
        }
        assertNull(d.snapshot());
        d.advance(submitted);
        var snapshot = d.snapshot();
        assertNotNull(snapshot);
        assertEquals(-6.02, snapshot.meter(4).peakDb(), .02);
        assertEquals(-9.03, snapshot.meter(4).rmsDb(), .08);
        assertEquals(1, snapshot.correlation, 1e-9);
        d.panels(0);
        assertNull(d.snapshot());
    }
}
