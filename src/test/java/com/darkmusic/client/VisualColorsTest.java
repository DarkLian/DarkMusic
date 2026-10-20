package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualColors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VisualColorsTest {
    @Test
    void defaultsMigrationAndResetUseOceanDriveIndependently() {
        Settings legacy = ConfigStore.JSON.fromJson("{\"hudEnabled\":true}", Settings.class);
        legacy.validate();
        assertEquals(new VisualColors().color(.7), legacy.spectrumColors.color(.7));
        assertEquals(new VisualColors().color(.3), legacy.progressColors.color(.3));
        assertNotSame(legacy.spectrumColors, legacy.progressColors);
        legacy.spectrumColors.preset = VisualColors.Preset.AURORA;
        assertEquals(VisualColors.Preset.OCEAN_DRIVE, legacy.progressColors.preset);
        assertEquals(VisualColors.Preset.OCEAN_DRIVE, Settings.defaults(legacy).spectrumColors.preset);
        legacy.spectrumColors = null;
        legacy.progressColors.preset = null;
        legacy.validate();
        assertEquals(0xFF578CFF, legacy.spectrumColors.color(0));
        assertEquals(0xFF5EFAF0, legacy.progressColors.color(1));
    }

    @Test
    void gradientsKeepOpaqueEndpointsInterpolateAndPersistCustomHex() {
        var colors = new VisualColors();
        colors.preset = VisualColors.Preset.CUSTOM;
        colors.start = 0x102030;
        colors.end = 0x90A0B0;
        assertEquals(0xFF102030, colors.color(-1));
        assertEquals(0xFF90A0B0, colors.color(2));
        assertEquals(0xFF506070, colors.color(.5));
        assertEquals(0xFF102030, colors.color(Double.NaN));
        assertEquals(0xAABBCC, VisualColors.parse("#aAbBcC"));
        for (String invalid : new String[]{"#123", "GG0000", "1234567", ""}) assertNull(VisualColors.parse(invalid));
        var settings = new Settings();
        settings.progressColors = colors;
        var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        loaded.validate();
        assertEquals(colors.color(.5), loaded.progressColors.color(.5));
        assertEquals(new VisualColors().color(.5), loaded.spectrumColors.color(.5));
        for (var preset : VisualColors.Preset.values()) {
            colors.preset = preset;
            assertEquals(255, colors.color(.25) >>> 24);
        }
    }
}
