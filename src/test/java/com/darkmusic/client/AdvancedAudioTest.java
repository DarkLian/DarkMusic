package com.darkmusic.client;

import com.darkmusic.client.audio.AudioProcessor;
import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.audio.SpectrumAnalyzer;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualQuality;
import com.darkmusic.client.lyrics.Lrc;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedAudioTest {
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

    private float[] excerpt() throws Exception {
        try (var in = TestMusic.clip().stream()) {
            var converter = new PcmRateConverter(in);
            float[] pcm = new float[32768];
            assertTrue(converter.read(pcm, pcm.length, 1) > 0);
            return pcm;
        }
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
    }

    @Test
    void lrcUnicodeOffsetsRepeatedTimestampsSeekAndSpeedTimeline() {
        var lrc = Lrc.parse("[offset:250]\n[00:01.50][00:03.005]音樂 🎵\n[00:02]next\n[99:99]invalid");
        assertEquals(3, lrc.lines().size());
        assertEquals(-1, lrc.current(1.249));
        assertEquals(0, lrc.current(1.25));
        assertEquals("音樂 🎵", lrc.lines().get(lrc.current(2.8)).text());
        assertEquals(1, lrc.current(2));
        assertEquals(-1, lrc.current(0));
    }

    @Test
    void invalidPreferencesAndOldSettingsMigrateToNeutralDefaults() {
        Settings s = ConfigStore.JSON.fromJson("{}", Settings.class);
        s.validate();
        assertTrue(s.audio.neutral);
        assertTrue(s.audio.gapless);
        assertEquals(0, s.audio.crossfade);
        assertEquals(VisualQuality.Preset.STANDARD, s.visualQuality.preset());
        var a = s.audio;
        a.bands = new double[]{Double.NaN};
        a.width = Double.NaN;
        a.targetLufs = 100;
        a.crossfade = 100;
        a.validate();
        assertEquals(10, a.bands.length);
        assertEquals(1, a.width);
        assertEquals(-9, a.targetLufs);
        assertEquals(10, a.crossfade);
        a.selectProfile("NIGHT");
        assertFalse(a.neutral);
        assertTrue(a.compressor);
        a.selectProfile("DEFAULT");
        assertTrue(a.neutral);
        assertFalse(a.compressor);
    }

    @Test
    void visualRefinementsFreezeWithAudiblePlaybackClock() throws Exception {
        var analyzer = new SpectrumAnalyzer();
        var q = new VisualQuality();
        q.select(VisualQuality.Preset.EXTREME);
        analyzer.refine(q);
        analyzer.configure(true, 36, 1, .6);
        var clip = TestMusic.clip();
        analyzer.begin(clip.format().getFrameRate(), clip.format().getChannels());
        analyzer.accept(clip.bytes(), 0, clip.bytes().length, 0);
        analyzer.advance(clip.bytes().length / clip.format().getFrameSize(), true);
        float[] values = new float[64], peaks = new float[64], frozen = new float[64];
        analyzer.copyTo(values);
        analyzer.copyPeaks(peaks);
        assertTrue(Arrays.stream(toDouble(values)).sum() > 0);
        for (int i = 0; i < 36; i++) assertTrue(peaks[i] >= values[i]);
        float beat = analyzer.beat();
        analyzer.advance(1_000_000, false);
        analyzer.copyTo(frozen);
        assertArrayEquals(values, frozen);
        assertEquals(beat, analyzer.beat());
        analyzer.clear();
        assertEquals(0, analyzer.beat());
    }

    private double[] toDouble(float[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }
}
