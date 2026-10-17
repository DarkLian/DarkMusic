package com.darkmusic.client;

import com.darkmusic.client.audio.SpectrumAnalyzer;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.HudLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumTest {
    private static byte[] music(int frames, double volume) throws Exception {
        var clip = TestMusic.clip();
        byte[] pcm = java.util.Arrays.copyOf(clip.bytes(), frames * clip.format().getFrameSize());
        com.darkmusic.client.audio.JavaSoundBackend.scale(pcm, pcm.length, volume);
        return pcm;
    }

    private static float[] levels(SpectrumAnalyzer spectrum) {
        var values = new float[64];
        spectrum.copyTo(values);
        return values;
    }

    private static int peak(float[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        return best;
    }

    private static float max(float[] values) {
        return values[peak(values)];
    }

    private static SpectrumAnalyzer analyzer(double smoothing, double sensitivity) throws Exception {
        var spectrum = new SpectrumAnalyzer();
        spectrum.configure(true, 32, sensitivity, smoothing);
        spectrum.begin(TestMusic.clip().format().getFrameRate(), TestMusic.clip().format().getChannels());
        return spectrum;
    }

    @Test
    void fftMatchesAnIndependentDirectFourierTransformOfRealMusic() throws Exception {
        var spectrum = analyzer(0, 1);
        var clip = TestMusic.clip();
        var pcm = music(SpectrumAnalyzer.SIZE, 1);
        spectrum.accept(pcm, 0, pcm.length, 0);
        spectrum.advance(SpectrumAnalyzer.SIZE, true);
        double strongest = -1;
        int best = 1;
        for (int bin = 1; bin < SpectrumAnalyzer.SIZE / 2; bin++) {
            double real = 0, imaginary = 0;
            for (int frame = 0; frame < SpectrumAnalyzer.SIZE; frame++) {
                double sample = 0;
                for (int channel = 0; channel < clip.format().getChannels(); channel++) {
                    int p = frame * clip.format().getFrameSize() + channel * 2;
                    sample += (short) ((pcm[p] & 255) | pcm[p + 1] << 8) / 32768.0;
                }
                sample = sample / clip.format().getChannels() * (.5 - .5 * Math.cos(2 * Math.PI * frame / (SpectrumAnalyzer.SIZE - 1)));
                double angle = 2 * Math.PI * bin * frame / SpectrumAnalyzer.SIZE;
                real += sample * Math.cos(angle);
                imaginary += sample * Math.sin(angle);
            }
            double energy = real * real + imaginary * imaginary;
            if (energy > strongest) {
                strongest = energy;
                best = bin;
            }
        }
        double frequency = best * clip.format().getFrameRate() / SpectrumAnalyzer.SIZE;
        int expected = (int) (Math.log(Math.max(30, frequency) / 30) / Math.log(Math.min(16000, clip.format().getFrameRate() / 2) / 30) * 32);
        assertTrue(max(levels(spectrum)) > .1);
        assertTrue(Math.abs(peak(levels(spectrum)) - Math.clamp(expected, 0, 31)) <= 2);
    }

    @Test
    void spectrumWaitsForDeviceClockAndFreezesDuringPause() throws Exception {
        var spectrum = analyzer(.6, 1);
        var pcm = music(8192, .5);
        spectrum.accept(pcm, 0, pcm.length, 0);
        spectrum.advance(0, true);
        assertEquals(0, max(levels(spectrum)));
        spectrum.advance(4096, true);
        var before = levels(spectrum);
        assertTrue(max(before) > .1);
        spectrum.advance(8192, false);
        assertArrayEquals(before, levels(spectrum));
        spectrum.advance(8192, true);
        assertTrue(max(levels(spectrum)) > max(before));
        spectrum.clear();
        assertEquals(0, max(levels(spectrum)));
    }

    @Test
    void silenceMuteAndDisabledAnalysisDoNotGenerateAnimation() throws Exception {
        var spectrum = analyzer(0, 1);
        var silence = music(8192, 0);
        spectrum.accept(silence, 0, silence.length, 0);
        spectrum.advance(8192, true);
        assertEquals(0, max(levels(spectrum)));
        spectrum.configure(false, 32, 1, 0);
        var pcm = music(8192, .9);
        spectrum.accept(pcm, 0, pcm.length, 8192);
        spectrum.advance(16384, true);
        assertEquals(0, max(levels(spectrum)));
    }

    @Test
    void attackDecayAndSensitivityRespondWithoutNonFiniteLevels() throws Exception {
        var fast = analyzer(0, 1);
        var slow = analyzer(.95, 1);
        var pcm = music(4096, .25);
        fast.accept(pcm, 0, pcm.length, 0);
        slow.accept(pcm, 0, pcm.length, 0);
        fast.advance(2048, true);
        slow.advance(2048, true);
        assertTrue(max(levels(fast)) > max(levels(slow)));
        var boosted = analyzer(0, 4);
        boosted.accept(pcm, 0, pcm.length, 0);
        boosted.advance(2048, true);
        assertTrue(max(levels(boosted)) > max(levels(fast)));
        float before = max(levels(fast));
        var zeros = music(4096, 0);
        for (int i = 1; i <= 10; i++) {
            fast.accept(zeros, 0, zeros.length, i * 4096L);
            fast.advance((i + 1) * 4096L, true);
        }
        assertTrue(max(levels(fast)) < before / 10);
        for (float value : levels(fast)) assertTrue(Float.isFinite(value) && value >= 0 && value <= 1);
    }

    @Test
    void reconfigureAndNewDeviceClearOldTrackAndSampleRateState() throws Exception {
        var spectrum = analyzer(0, 1);
        var pcm = music(8192, .5);
        spectrum.accept(pcm, 0, pcm.length, 0);
        spectrum.advance(8192, true);
        assertTrue(max(levels(spectrum)) > 0);
        spectrum.begin(44100, 1);
        assertEquals(0, max(levels(spectrum)));
        spectrum.configure(true, 8, 1, 0);
        var silence = music(4096, 0);
        spectrum.accept(silence, 0, silence.length, 0);
        spectrum.advance(4096, true);
        assertEquals(0, max(levels(spectrum)));
    }

    @Test
    void settingsAndHudGeometryClampToTheScreen() {
        var settings = new Settings();
        settings.visualizerSensitivity = Double.NaN;
        settings.visualizerSmoothing = Double.POSITIVE_INFINITY;
        settings.visualizerBars = 200;
        settings.validate();
        assertEquals(64, settings.visualizerBars);
        assertEquals(1, settings.visualizerSensitivity);
        assertEquals(.6, settings.visualizerSmoothing);
        for (int width : new int[]{320, 426, 854}) {
            var b = HudLayout.bounds(settings, width, 240);
            assertTrue(b.x() >= 8 && b.x() + b.width() <= width - 8);
            assertTrue(b.y() + b.height() <= 232);
        }
    }
}
