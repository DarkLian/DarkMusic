package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioEnhancements;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.SoundEnvironmentSettings;
import com.darkmusic.client.config.VisualQuality;
import com.darkmusic.client.testing.HotCost;
import com.darkmusic.client.testing.HotGolden;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AudioHotLoopTest {
    private static float[] input() throws Exception {
        var clip = TestMusic.clip();
        float[] values = new float[clip.bytes().length / 2];
        try (var stream = clip.stream()) {
            int read = new PcmRateConverter(stream).read(values, values.length, 1);
            return Arrays.copyOf(values, read);
        }
    }

    private static AudioSettings settings(String kind) {
        var settings = new AudioSettings();
        if (kind.equals("neutral")) return settings;
        settings.neutral = false;
        if (kind.equals("eq") || kind.equals("chain")) {
            settings.equalizer = true;
            settings.bass = 3;
            settings.width = 1.2;
            for (int i = 0; i < settings.bands.length; i++) settings.bands[i] = (i % 3 - 1) * 3;
        }
        if (kind.equals("limiter") || kind.equals("chain")) {
            settings.limiter = true;
            settings.enhancements.protection = AudioEnhancements.Protection.TRUE_PEAK;
        }
        if (kind.equals("chain")) {
            settings.compressor = true;
            settings.enhancements.crossfeed = true;
            settings.reverb = AudioSettings.Reverb.CONCERT_HALL;
        }
        return settings;
    }

    private static String processorOutput(float[] source, String kind, SoundEnvironmentSettings space) throws Exception {
        var format = TestMusic.clip().format();
        var processor = new AudioProcessor();
        processor.configure(settings(kind), space);
        processor.begin(format.getSampleRate(), format.getChannels());
        float[] result = source.clone();
        int block = 256 * format.getChannels();
        for (int at = 0; at < result.length; at += block) {
            float[] values = Arrays.copyOfRange(source, at, Math.min(source.length, at + block));
            processor.process(values, values.length, .8, 1.2);
            System.arraycopy(values, 0, result, at, values.length);
        }
        return HotGolden.hash(result);
    }

    private static String environmentOutput(float[] source, SoundEnvironmentSettings space) throws Exception {
        var format = TestMusic.clip().format();
        var engine = new SoundEnvironmentProcessor.Engine();
        engine.initialize(format.getSampleRate());
        engine.configure(space, true);
        engine.prepare();
        double[] result = new double[source.length / format.getChannels() * 2];
        for (int at = 0, out = 0; at < source.length; at += format.getChannels()) {
            engine.process(source[at], source[at + format.getChannels() - 1]);
            result[out++] = engine.left;
            result[out++] = engine.right;
        }
        return HotGolden.hash(result);
    }

    private static void identical(Map<String, String> results, String key, String value) {
        String previous = results.putIfAbsent(key, value);
        if (previous != null) assertEquals(previous, value, key + " must repeat exactly");
    }

    @Test
    void directDspAndEveryEnvironmentPresetRepeatAndMatchPreSplitGoldens() throws Exception {
        float[] source = input();
        var results = new LinkedHashMap<String, String>();
        results.put("clip", HotGolden.hash(TestMusic.clip().bytes()));
        results.put("floatInput", HotGolden.hash(source));
        for (int repeat = 0; repeat < 3; repeat++) {
            for (String kind : new String[]{"neutral", "eq", "limiter", "chain"})
                identical(results, "processor." + kind, processorOutput(source, kind, new SoundEnvironmentSettings()));
            for (var preset : SoundEnvironmentSettings.Preset.values()) {
                var space = new SoundEnvironmentSettings();
                space.select(preset);
                identical(results, "engine." + preset, environmentOutput(source, space));
                identical(results, "processor.environment." + preset, processorOutput(source, "eq", space));
            }
        }
        HotGolden.check("hot-dsp-golden", results);
    }

    @Test
    void waveformAndSpectrumArraysRepeatAndMatchPreSplitGoldens() throws Exception {
        var clip = TestMusic.clip();
        var results = new LinkedHashMap<String, String>();
        results.put("clip", HotGolden.hash(clip.bytes()));
        for (int repeat = 0; repeat < 3; repeat++) {
            try (var stream = clip.stream()) {
                var waveform = TrackWaveform.analyze(stream, () -> false);
                float[] values = new float[TrackWaveform.BINS];
                for (int i = 0; i < values.length; i++) values[i] = waveform.level(i);
                identical(results, "waveform", HotGolden.hash(values));
                identical(results, "duration", Double.toHexString(waveform.duration()));
            }
            for (int bands : new int[]{8, 24, 64})
                for (boolean refined : new boolean[]{false, true}) {
                    var analyzer = new SpectrumAnalyzer();
                    analyzer.configure(true, bands, 1.3, .6);
                    var quality = new VisualQuality();
                    quality.frequencySmoothing = quality.peakHold = quality.trails = quality.beatReactive = refined;
                    analyzer.refine(quality);
                    analyzer.begin(clip.format().getSampleRate(), clip.format().getChannels());
                    int block = 1024 * clip.format().getFrameSize();
                    for (int at = 0; at + block <= clip.bytes().length; at += block) {
                        analyzer.accept(clip.bytes(), at, block, at / clip.format().getFrameSize());
                        analyzer.advance(at / clip.format().getFrameSize() + 1024, true);
                    }
                    float[] values = new float[129];
                    float[] levels = new float[64], peaks = new float[64];
                    analyzer.copyTo(levels);
                    analyzer.copyPeaks(peaks);
                    System.arraycopy(levels, 0, values, 0, 64);
                    System.arraycopy(peaks, 0, values, 64, 64);
                    values[128] = analyzer.beat();
                    identical(results, "spectrum." + bands + "." + refined, HotGolden.hash(values));
                }
        }
        HotGolden.check("hot-analysis-golden", results);
    }

    @Test
    @EnabledIfSystemProperty(named = "darkmusic.test.perf", matches = "true")
    void audioProcessorCost() throws Exception {
        Assumptions.assumeTrue(TestMusic.clip().format().getChannels() == 2, "This cost workload requires a real stereo clip");
        float[] source = Arrays.copyOf(input(), 512), work = source.clone();
        var processor = new AudioProcessor();
        processor.configure(settings("chain"));
        processor.begin(TestMusic.clip().format().getSampleRate(), TestMusic.clip().format().getChannels());
        HotCost.measure("AudioProcessor.process/256-stereo-frames", () -> {
            System.arraycopy(source, 0, work, 0, source.length);
            processor.process(work, work.length, .8, 1.2);
            return Float.floatToRawIntBits(work[work.length - 1]);
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "darkmusic.test.perf", matches = "true")
    void environmentEngineCost() throws Exception {
        Assumptions.assumeTrue(TestMusic.clip().format().getChannels() == 2, "This cost workload requires a real stereo clip");
        float[] source = Arrays.copyOf(input(), 512);
        var settings = new SoundEnvironmentSettings();
        settings.select(SoundEnvironmentSettings.Preset.DREAMSCAPE);
        var engine = new SoundEnvironmentProcessor.Engine();
        engine.initialize(TestMusic.clip().format().getSampleRate());
        engine.configure(settings, true);
        engine.prepare();
        HotCost.measure("SoundEnvironmentProcessor.Engine.process/256-stereo-frames", () -> {
            for (int i = 0; i < source.length; i += 2) engine.process(source[i], source[i + 1]);
            return Double.doubleToRawLongBits(engine.right);
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "darkmusic.test.perf", matches = "true")
    void spectrumAnalyzerCost() throws Exception {
        var clip = TestMusic.clip();
        var analyzer = new SpectrumAnalyzer();
        analyzer.configure(true, 64, 1.3, .6);
        analyzer.begin(clip.format().getSampleRate(), clip.format().getChannels());
        analyzer.accept(clip.bytes(), 0, 2048 * clip.format().getFrameSize(), 0);
        HotCost.measure("SpectrumAnalyzer.analyze/2048-FFT-64-bands", () -> Float.floatToRawIntBits(analyzer.analyzeForTests(2048)));
    }

    @Test
    @EnabledIfSystemProperty(named = "darkmusic.test.perf", matches = "true")
    void trackWaveformCost() throws Exception {
        var clip = TestMusic.clip();
        byte[] bytes = Arrays.copyOf(clip.bytes(), 16384 * clip.format().getFrameSize());
        HotCost.measure("TrackWaveform.analyze/16384-frames", () -> {
            try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), clip.format(), 16384)) {
                return Float.floatToRawIntBits(TrackWaveform.analyze(stream, () -> false).level(100));
            }
        });
    }
}
