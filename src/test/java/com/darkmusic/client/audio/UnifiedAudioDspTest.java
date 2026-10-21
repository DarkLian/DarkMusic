package com.darkmusic.client.audio;

import com.darkmusic.client.config.*;
import org.junit.jupiter.api.Test;

import java.util.*;

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
                var format = new javax.sound.sampled.AudioFormat(48000, 16, 2, true, false);
                try (var stream = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(bytes), format, frames)) {
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
    void truePeakProtectsIntersampleOvershootWithLinkedStereoAndFiniteLatency() {
        for (double rate : new double[]{44100, 48000, 96000})
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
        for (double rate : new double[]{44100, 48000, 96000})
            for (var mode : ConvolutionReverb.Impulse.values()) {
                var reverb = new ConvolutionReverb(rate, mode);
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!reverb.ready() && System.nanoTime() < until) Thread.sleep(5);
                assertTrue(reverb.ready());
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
    void allTwentyEnvironmentChoicesAreDistinctAndIntensityZeroIsIdentity() {
        List<double[]> fingerprints = new ArrayList<>();
        for (var preset : SoundEnvironmentSettings.Preset.values()) {
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
                assertTrue(difference > 1e-4, preset + " duplicates " + SoundEnvironmentSettings.Preset.values()[p]);
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
        assertEquals(20, fingerprints.size());
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
