package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualQuality;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AudioProcessorTest {
    private float[] excerpt() throws Exception {
        try (var in = TestMusic.clip().stream()) {
            var converter = new PcmRateConverter(in);
            float[] pcm = new float[32768];
            assertTrue(converter.read(pcm, pcm.length, 1) > 0);
            return pcm;
        }
    }

    private double[] toDouble(float[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }

    @Test
    void twentyFourBitOutputKeepsSubSixteenBitDetailAndSpectrumReadsItsFrames() throws Exception {
        float[] values = {1f / 8388608, -1f / 8388608, .5f};
        byte[] bytes = new byte[9];
        AudioProcessor.quantize(values, bytes, 3, 3);
        assertArrayEquals(new byte[]{1, 0, 0, -1, -1, -1, 0, 0, 64}, bytes);
        float[] pcm = excerpt();
        byte[] converted = new byte[pcm.length * 3];
        AudioProcessor.quantize(pcm, converted, pcm.length, 3);
        var analyzer = new SpectrumAnalyzer();
        var format = TestMusic.clip().format();
        analyzer.configure(true, 36, 1, .6);
        analyzer.begin(format.getFrameRate(), format.getChannels());
        analyzer.accept(converted, 0, converted.length, 0, 3);
        analyzer.advance(pcm.length / format.getChannels(), true);
        float[] levels = new float[64];
        analyzer.copyTo(levels);
        assertTrue(Arrays.stream(toDouble(levels)).sum() > 0);
    }

    @Test
    void neutralBypassesAllEffectsAndQualityPresetsNeverChangeAudio() throws Exception {
        var pcm = excerpt();
        var original = pcm.clone();
        var a = new AudioSettings();
        a.equalizer = a.normalization = a.limiter = a.compressor = true;
        a.bands[0] = 12;
        a.bass = 6;
        a.width = 0;
        a.reverb = AudioSettings.Reverb.CONCERT_HALL;
        var processor = new AudioProcessor();
        processor.begin(TestMusic.clip().format().getSampleRate(), TestMusic.clip().format().getChannels());
        processor.configure(a);
        processor.process(pcm, pcm.length, 1, 2);
        assertArrayEquals(original, pcm);
        var s = new Settings();
        String audio = ConfigStore.JSON.toJson(s.audio);
        for (var preset : VisualQuality.Preset.values()) {
            s.visualQuality.select(preset);
            assertEquals(audio, ConfigStore.JSON.toJson(s.audio));
        }
        s.visualQuality.select(VisualQuality.Preset.ENHANCED);
        assertEquals(VisualQuality.Preset.ENHANCED, s.visualQuality.preset());
        s.visualQuality.glow = true;
        assertEquals(VisualQuality.Preset.CUSTOM, s.visualQuality.preset());
    }

    @Test
    void effectsRemainFiniteAndLimiterCannotWrapPcm() throws Exception {
        var a = new AudioSettings();
        a.neutral = false;
        a.equalizer = a.limiter = a.compressor = true;
        a.bass = 6;
        a.width = 1.5;
        a.reverb = AudioSettings.Reverb.CONCERT_HALL;
        Arrays.fill(a.bands, 12);
        var processor = new AudioProcessor();
        processor.begin(TestMusic.clip().format().getSampleRate(), TestMusic.clip().format().getChannels());
        processor.configure(a);
        for (int pass = 0; pass < 30; pass++) {
            var pcm = excerpt();
            processor.process(pcm, pcm.length, 1, 4);
            for (float v : pcm) assertTrue(Float.isFinite(v) && Math.abs(v) < 1);
        }
        byte[] out = new byte[6];
        AudioProcessor.quantize(new float[]{2, -2, 0}, out, 3);
        assertArrayEquals(new byte[]{-1, 127, 0, -128, 0, 0}, out);
    }

    @Test
    void actualPcmSurvivesFloatConversionAndNeutralQuantization() throws Exception {
        var clip = TestMusic.clip();
        float[] values = new float[clip.bytes().length / 2];
        int samples;
        try (var stream = clip.stream()) {
            samples = new PcmRateConverter(stream).read(values, values.length, 1);
        }
        byte[] result = new byte[samples * 2];
        AudioProcessor.quantize(values, result, samples);
        assertArrayEquals(Arrays.copyOf(clip.bytes(), result.length), result);
        AudioProcessor.quantize(values, result, samples, 2, new AudioProcessor.Dither(1));
        assertArrayEquals(Arrays.copyOf(clip.bytes(), result.length), result, "dither must leave on-grid playback bit-exact");
    }

    @Test
    void ditherKeepsDetailBelowOneSixteenBitStepAndLeavesTwentyFourBitAlone() {
        float[] quiet = new float[20000];
        Arrays.fill(quiet, .25f / 32768);
        byte[] out = new byte[quiet.length * 2];
        AudioProcessor.quantize(quiet, out, quiet.length);
        for (byte b : out) assertEquals(0, b);
        AudioProcessor.quantize(quiet, out, quiet.length, 2, new AudioProcessor.Dither(7));
        long sum = 0;
        for (int i = 0; i < out.length; i += 2) {
            int n = (short) ((out[i] & 255) | out[i + 1] << 8);
            assertTrue(Math.abs(n) <= 1);
            sum += n;
        }
        assertEquals(.25, sum / (double) quiet.length, .03);
        byte[] silence = new byte[8];
        AudioProcessor.quantize(new float[4], silence, 4, 2, new AudioProcessor.Dither(7));
        assertArrayEquals(new byte[8], silence);
        byte[] plain = new byte[quiet.length * 3], dithered = new byte[quiet.length * 3];
        AudioProcessor.quantize(quiet, plain, quiet.length, 3);
        AudioProcessor.quantize(quiet, dithered, quiet.length, 3, new AudioProcessor.Dither(7));
        assertArrayEquals(plain, dithered);
        byte[] clipped = new byte[6];
        AudioProcessor.quantize(new float[]{2, -2, 0}, clipped, 3, 2, new AudioProcessor.Dither(7));
        assertArrayEquals(new byte[]{-1, 127, 0, -128, 0, 0}, clipped);
    }
}
