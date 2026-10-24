package com.darkmusic.client.hud;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.ArtworkCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudImageTest {
    @Test
    void artworkDefaultsAndPerLayoutChoicesSurvivePersistence() {
        var settings = ConfigStore.JSON.fromJson("{}", Settings.class);
        settings.validate();
        assertEquals(0, settings.circularImageGap);
        for (var style : new Settings.HudStyle[]{Settings.HudStyle.CLASSIC, Settings.HudStyle.CIRCULAR, Settings.HudStyle.ARTWORK}) {
            settings.hudStyle = style;
            assertEquals(Settings.HudImage.ARTWORK, settings.hudImage());
        }
        settings.hudStyle = Settings.HudStyle.CIRCULAR;
        settings.hudImage(Settings.HudImage.ICON);
        settings.hudStyle = Settings.HudStyle.CLASSIC;
        assertEquals(Settings.HudImage.ARTWORK, settings.hudImage());
        var restored = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        assertEquals(Settings.HudImage.ICON, restored.circularImage);
        assertEquals(Settings.HudImage.ARTWORK, restored.classicImage);
        assertEquals(Settings.HudImage.ARTWORK, restored.artworkImage);
        restored.classicImage = restored.circularImage = restored.artworkImage = null;
        restored.circularImageGap = 1000;
        restored.validate();
        assertEquals(Settings.HudImage.ARTWORK, restored.circularImage);
        assertEquals(24, restored.circularImageGap);
        restored.circularSpectrumStyle = restored.spectrumStyle = Settings.SpectrumStyle.ROUNDED_BARS;
        restored.validate();
        var rounded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(restored), Settings.class);
        assertEquals(Settings.SpectrumStyle.ROUNDED_BARS, rounded.spectrumStyle);
        assertEquals(Settings.SpectrumStyle.ROUNDED_BARS, rounded.circularSpectrumStyle);
    }

    @Test
    void nearestLayerTouchesImageAtZeroAndGapMovesTheWholeLayerStack() {
        var settings = new Settings();
        settings.hudStyle = Settings.HudStyle.CIRCULAR;
        for (boolean progress : new boolean[]{false, true})
            for (boolean spectrum : new boolean[]{false, true})
                for (boolean waveform : new boolean[]{false, true}) {
                    settings.hudProgress = progress;
                    settings.hudVisualizer = spectrum;
                    settings.waveformProgress = waveform;
                    settings.circularImageGap = 0;
                    float thickness = progress ? waveform ? settings.waveform.height : 3 : 0;
                    assertEquals(CircularHudLayout.ICON_RADIUS, CircularHudLayout.progressInner(settings));
                    assertEquals(CircularHudLayout.ICON_RADIUS + thickness, CircularHudLayout.spectrumBase(settings));
                    var tight = CircularHudLayout.of(settings);
                    settings.circularImageGap = 12;
                    float expectedGap = progress || spectrum ? 12 : 0;
                    assertEquals(CircularHudLayout.ICON_RADIUS + expectedGap, CircularHudLayout.progressInner(settings));
                    assertEquals(CircularHudLayout.ICON_RADIUS + expectedGap + thickness, CircularHudLayout.spectrumBase(settings));
                    assertEquals(tight.height() + expectedGap * 2, CircularHudLayout.of(settings).height());
                }
    }

    @Test
    void circularCropUsesActualContentWithoutStretchingAndIconFallbackUsesFullTexture() {
        assertEquals(1, HudImages.circularSpan(null));
        assertEquals(1, HudImages.circularSpan(new ArtworkCache.Cover(HudImages.ICON, 512)));
        assertEquals(288 / 512f, HudImages.circularSpan(new ArtworkCache.Cover(HudImages.ICON, 512, 512, 288, 0)));
        assertEquals(256 / 512f, HudImages.circularSpan(new ArtworkCache.Cover(HudImages.ICON, 512, 512, 512, 128)));
    }
}
