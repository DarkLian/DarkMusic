package com.darkmusic.client.render;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.SpectrumAnalyzer;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.testing.FakeAudio;
import com.darkmusic.client.testing.HotCost;
import com.darkmusic.client.testing.HotGolden;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpectrumHotLoopTest {
    private static SpectrumAnalyzer analyzer() throws Exception {
        var clip = TestMusic.clip();
        var analyzer = new SpectrumAnalyzer();
        analyzer.configure(true, 64, 1.3, .6);
        analyzer.begin(clip.format().getSampleRate(), clip.format().getChannels());
        analyzer.accept(clip.bytes(), 0, 16384 * clip.format().getFrameSize(), 0);
        analyzer.advance(16384, true);
        return analyzer;
    }

    private static String snapshot(SpectrumMesh mesh) throws Exception {
        int[] values = new int[mesh.count() / 5 * 6];
        for (int i = 0, out = 0; i < mesh.count(); i += 5) {
            for (int n = 0; n < 5; n++) values[out++] = mesh.rectangles()[i + n];
            values[out++] = mesh.bottomColors()[i / 5];
        }
        return HotGolden.hash(values);
    }

    @Test
    void spectrumRectanglesAndGradientsKeepEveryBit() throws Exception {
        var results = new LinkedHashMap<String, String>();
        results.put("clip", HotGolden.hash(TestMusic.clip().bytes()));
        var analyzer = analyzer();
        var renderer = new SpectrumRenderer();
        var playback = new PlaybackController(new FakeAudio(), message -> {
        });
        var settings = new Settings();
        settings.visualizerBars = 64;
        settings.visualQuality.glow = false;
        for (int repeat = 0; repeat < 3; repeat++)
            for (var style : Settings.SpectrumStyle.values())
                for (var shape : Settings.HudShape.values())
                    for (boolean downward : new boolean[]{false, true})
                        for (boolean effects : new boolean[]{false, true})
                            for (var finish : Settings.SurfaceFinish.values()) {
                                settings.spectrumStyle = style;
                                settings.hudShape = shape;
                                settings.spectrumFinish = finish;
                                settings.visualQuality.trails = settings.visualQuality.peakHold = effects;
                                renderer.bars(settings, playback, null, SpectrumRenderer.Bars.at(3, 7, 220, 50, downward, false).inHud(90).from(analyzer, settings));
                                String key = style + "." + shape + "." + downward + "." + effects + "." + finish;
                                String hash = snapshot(renderer.mesh());
                                String previous = results.putIfAbsent(key, hash);
                                if (previous != null) assertEquals(previous, hash, key);
                            }
        HotGolden.check("hot-spectrum-golden", results);
    }

    @Test
    @EnabledIfSystemProperty(named = "darkmusic.test.perf", matches = "true")
    void spectrumRendererBarsCost() throws Exception {
        var analyzer = analyzer();
        var renderer = new SpectrumRenderer();
        var playback = new PlaybackController(new FakeAudio(), message -> {
        });
        var settings = new Settings();
        settings.visualizerBars = 64;
        settings.visualQuality.glow = false;
        settings.spectrumStyle = Settings.SpectrumStyle.FILLED_WAVE;
        settings.visualQuality.trails = settings.visualQuality.peakHold = true;
        var request = SpectrumRenderer.Bars.at(3, 7, 220, 50, false, false).inHud(90).from(analyzer, settings);
        HotCost.measure("SpectrumRenderer.bars/220-beveled-wave-trails-peaks-no-GPU", () -> {
            renderer.bars(settings, playback, null, request);
            return renderer.mesh().count();
        });
    }
}
