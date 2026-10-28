package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.render.SpectrumRenderer;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.render.HudSurface;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.MetadataReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class CombinedHudTest {
    @Test
    void allComponentsPlacementsDirectionsAndMagnitudesStayDisjoint() {
        var s = new Settings();
        s.validate();
        for (boolean art : new boolean[]{false, true})
            for (boolean progress : new boolean[]{false, true})
                for (boolean spectrum : new boolean[]{false, true})
                    for (var position : Settings.SpectrumPosition.values())
                        for (var direction : Settings.SpectrumDirection.values()) {
                            s.hudArtwork = art;
                            s.hudProgress = progress;
                            s.hudVisualizer = spectrum;
                            s.spectrumPosition = position;
                            s.spectrumDirection = direction;
                            var c = HudLayout.content(s, Integer.MAX_VALUE);
                            assertEquals(art ? 220 : 184, c.width());
                            assertEquals((progress ? 46 : 38) + (spectrum ? 36 : 0), c.height());
                            if (spectrum) for (float amplitude : new float[]{0, .5f, 1, 4, Float.NaN}) {
                                int h = SpectrumRenderer.barHeight(amplitude, HudLayout.SPECTRUM_HEIGHT);
                                int baseline = c.baseline(direction);
                                int top = direction == Settings.SpectrumDirection.UPWARD ? baseline - h : baseline;
                                int bottom = top + h;
                                assertTrue(top >= c.spectrumY() && bottom <= c.spectrumY() + HudLayout.SPECTRUM_HEIGHT);
                                if (position == Settings.SpectrumPosition.ABOVE)
                                    assertTrue(bottom <= c.mainY() - HudLayout.GAP);
                                else assertTrue(top >= c.mainY() + c.mainHeight() + HudLayout.GAP);
                            }
                            for (int width : new int[]{320, 426, 854, 1280})
                                for (int height : new int[]{180, 240, 480, 720})
                                    for (double scale : new double[]{.5, 1, 3}) {
                                        s.hudScale = scale;
                                        s.hudX = 1.0;
                                        s.hudY = 1.0;
                                        var b = HudLayout.bounds(s, width, height);
                                        assertTrue(b.x() >= 8 && b.y() >= 8 && b.x() + b.width() <= width - 8 && b.y() + b.height() <= height - 8);
                                        assertEquals(c.width(), b.contentWidth());
                                    }
                        }
    }

    @Test
    void legacyMigrationPreservesChoicesAndDropsOnlyIndependentLayout() {
        var s = ConfigStore.JSON.fromJson("{\"hudEnabled\":false,\"hudVisualizer\":false,\"hudArtwork\":false,\"hudProgress\":false,\"hudX\":0.7,\"hudY\":0.4,\"hudScale\":1.3,\"visualizerX\":1,\"visualizerY\":0.9,\"visualizerWidth\":320,\"visualizerHeight\":120,\"visualizerScale\":3}", Settings.class);
        s.validate();
        assertFalse(s.hudEnabled);
        assertFalse(s.hudVisualizer);
        assertFalse(s.hudArtwork);
        assertFalse(s.hudProgress);
        assertEquals(.7, s.hudX);
        assertEquals(.4, s.hudY);
        assertEquals(1.3, s.hudScale);
        String serialized = ConfigStore.JSON.toJson(s);
        assertFalse(serialized.contains("visualizerWidth"));
        assertFalse(serialized.contains("visualizerX"));
        assertFalse(serialized.contains("visualizerScale"));
        var defaults = ConfigStore.JSON.fromJson("{}", Settings.class);
        defaults.validate();
        assertTrue(defaults.hudEnabled);
        assertEquals(1, defaults.volume);
        assertEquals(1, defaults.hudScale);
        assertEquals(1, defaults.hudBackgroundTransparency);
        assertEquals(36, defaults.visualizerBars);
        assertEquals(Settings.SpectrumDirection.UPWARD, defaults.spectrumDirection);
        assertTrue(defaults.hudArtwork && defaults.hudProgress && defaults.hudVisualizer);
        assertEquals(Settings.SpectrumPosition.BELOW, defaults.spectrumPosition);
        s.spectrumPosition = Settings.SpectrumPosition.ABOVE;
        s.spectrumDirection = Settings.SpectrumDirection.DOWNWARD;
        s.hudBackgroundTransparency = .73;
        var reloaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(s), Settings.class);
        reloaded.validate();
        assertEquals(s.spectrumPosition, reloaded.spectrumPosition);
        assertEquals(s.spectrumDirection, reloaded.spectrumDirection);
        assertEquals(.73, reloaded.hudBackgroundTransparency);
    }

    @Test
    void opacityOnlyChangesTheBackgroundAlpha() {
        var s = new Settings();
        for (double value : new double[]{0, .5, 1}) {
            s.hudBackgroundTransparency = value;
            int color = HudSurface.background(s);
            assertEquals((int) Math.round((1 - value) * 255), color >>> 24);
            assertEquals(0x111420, color & 0xFFFFFF);
        }
        s.hudBackgroundTransparency = Double.NaN;
        s.validate();
        assertEquals(0, HudSurface.background(s) >>> 24);
    }

    @Test
    void realArtworkRetainsItsAlphaAndTransparentLetterboxing() throws Exception {
        byte[] bytes = new MetadataReader().artwork(TestMusic.featured());
        assertNotNull(bytes, "The private collection needs embedded artwork for this check");
        var original = ImageIO.read(new ByteArrayInputStream(bytes));
        var fitted = ArtworkCache.fitArtwork(original);
        assertEquals(original.getRGB(original.getWidth() / 2, original.getHeight() / 2) >>> 24, fitted.getRGB(fitted.getWidth() / 2, fitted.getHeight() / 2) >>> 24);
        if (original.getWidth() != original.getHeight()) assertEquals(0, fitted.getRGB(0, 0) >>> 24);
    }

}
