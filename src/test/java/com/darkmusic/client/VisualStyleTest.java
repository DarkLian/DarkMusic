package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.SpectrumCurve;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.hud.HudSurface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VisualStyleTest {
    @Test
    void spikeProfilesHavePointedPeaksAndBoundedFiniteSamples() {
        float[] levels = {1, .5f, Float.NaN, 1};
        assertEquals(0, SpectrumCurve.spike(levels, 4, 0));
        assertEquals(1, SpectrumCurve.spike(levels, 4, .125));
        assertEquals(.5, SpectrumCurve.spike(levels, 4, .375));
        assertEquals(0, SpectrumCurve.spike(levels, 4, .625));
        assertEquals(0, SpectrumCurve.spike(levels, 4, 1));
        for (int i = 0; i <= 1000; i++) {
            float value = SpectrumCurve.spike(levels, 4, i / 1000.0);
            assertTrue(Float.isFinite(value) && value >= 0 && value <= 1);
        }
    }

    @Test
    void waveInterpolationPreservesBandsAndCannotOvershoot() {
        float[] levels = {0, 1, .25f, .8f, 0};
        for (int i = 0; i < levels.length; i++)
            assertEquals(levels[i], SpectrumCurve.sample(levels, levels.length, i / 4.0));
        for (int i = 0; i <= 1000; i++) {
            double position = i / 1000.0;
            int left = Math.min(3, (int) (position * 4));
            float value = SpectrumCurve.sample(levels, 5, position);
            assertTrue(value >= Math.min(levels[left], levels[left + 1]) && value <= Math.max(levels[left], levels[left + 1]));
            assertEquals(value, SpectrumCurve.sample(levels, 5, position), "Paused data must remain stationary");
        }
        assertEquals(0, SpectrumCurve.sample(new float[64], 64, .5));
        assertEquals(0, SpectrumCurve.sample(new float[]{Float.NaN, Float.POSITIVE_INFINITY}, 2, .5));
    }

    @Test
    void shapedSurfacesStayInsideUnchangedLayoutAndHaveSymmetricCorners() {
        var settings = new Settings();
        settings.validate();
        for (var shape : Settings.HudShape.values())
            for (boolean artwork : new boolean[]{false, true})
                for (boolean progress : new boolean[]{false, true})
                    for (boolean spectrum : new boolean[]{false, true}) {
                        settings.hudShape = shape;
                        settings.hudArtwork = artwork;
                        settings.hudProgress = progress;
                        settings.hudVisualizer = spectrum;
                        var layout = HudLayout.content(settings, Integer.MAX_VALUE);
                        for (int row = 0; row < layout.height(); row++) {
                            int inset = HudSurface.inset(shape, layout.width(), layout.height(), row);
                            assertTrue(inset >= 0 && inset <= 6);
                            assertEquals(inset, HudSurface.inset(shape, layout.width(), layout.height(), layout.height() - 1 - row));
                            if (shape == Settings.HudShape.RECTANGLE) assertEquals(0, inset);
                        }
                        assertEquals(artwork ? 220 : 184, layout.width());
                        assertEquals((progress ? 46 : 38) + (spectrum ? 36 : 0), layout.height());
                    }
    }

    @Test
    void oldConfigsKeepOriginalAppearanceAndNewChoicesPersistAndReset() {
        var settings = ConfigStore.JSON.fromJson("{}", Settings.class);
        settings.validate();
        assertEquals(Settings.SpectrumStyle.BARS, settings.spectrumStyle);
        assertEquals(Settings.HudShape.RECTANGLE, settings.hudShape);
        assertFalse(settings.hudRaised);
        for (var style : Settings.SpectrumStyle.values())
            for (var shape : Settings.HudShape.values()) {
                settings.spectrumStyle = style;
                settings.hudShape = shape;
                settings.hudRaised = true;
                var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
                loaded.validate();
                assertEquals(style, loaded.spectrumStyle);
                assertEquals(shape, loaded.hudShape);
                assertTrue(loaded.hudRaised);
            }
        settings.spectrumStyle = null;
        settings.hudShape = null;
        settings.validate();
        assertEquals(Settings.SpectrumStyle.BARS, settings.spectrumStyle);
        assertEquals(Settings.HudShape.RECTANGLE, settings.hudShape);
        assertFalse(Settings.defaults(settings).hudRaised);
    }
}
