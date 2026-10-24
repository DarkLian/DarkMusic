package com.darkmusic.client.gui;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumAppearanceTest {
    @Test
    void opacityComposesWithTrailAndGlowAlphaInsteadOfReplacingIt() {
        assertEquals(0x80123456, SpectrumAppearance.opacity(0xff123456, .5));
        assertEquals(0x2c123456, SpectrumAppearance.opacity(0x58123456, .5));
        assertEquals(0x1a123456, SpectrumAppearance.alpha(0x80123456, 0x33));
        assertEquals(0x00123456, SpectrumAppearance.opacity(0x00123456, .5));
        assertEquals(0xff123456, SpectrumAppearance.opacity(0xff123456, Double.NaN));
    }

    @Test
    void newOpacityDefaultsPreserveExistingAppearanceAndPersist() {
        assertEquals(1, new Settings().spectrumOpacity);
        var settings = new Settings();
        settings.spectrumOpacity = .35;
        var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        loaded.validate();
        assertEquals(.35, loaded.spectrumOpacity);
        loaded.spectrumOpacity = Double.NaN;
        loaded.validate();
        assertEquals(1, loaded.spectrumOpacity);
        loaded.spectrumOpacity = 0;
        loaded.validate();
        assertEquals(.1, loaded.spectrumOpacity);
    }

    @Test
    void roundedPeakOnlyAppearsAfterItSeparatesFromLiveCap() {
        assertFalse(SpectrumAppearance.detachedPeak(.51f, .5f, 100));
        assertTrue(SpectrumAppearance.detachedPeak(.6f, .5f, 100));
        assertFalse(SpectrumAppearance.detachedPeak(.6f, .5f, 10));
    }
}
