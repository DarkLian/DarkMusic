package com.darkmusic.client.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioSettingsTest {
    @Test
    void profileSwitchClearsOldEqAndCustomVisualsCyclePredictably() {
        var audio = new AudioSettings();
        for (String profile : new String[]{"DEFAULT", "MUSIC", "GAMING", "NIGHT"}) {
            audio.selectProfile("VOCAL");
            assertNotEquals(0, audio.bands[0]);
            audio.selectProfile(profile);
            audio.equalizer = true;
            assertArrayEquals(new double[10], audio.bands);
            assertEquals("FLAT", audio.eqPreset);
        }
        var quality = new VisualQuality();
        quality.glow = true;
        assertEquals(VisualQuality.Preset.CUSTOM, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.STANDARD, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.ENHANCED, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.EXTREME, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.STANDARD, quality.preset());
    }

    @Test
    void invalidPreferencesAndOldSettingsMigrateToNeutralDefaults() {
        Settings s = ConfigStore.JSON.fromJson("{}", Settings.class);
        s.validate();
        assertTrue(s.audio.neutral);
        assertTrue(s.audio.gapless);
        assertEquals(0, s.audio.crossfade);
        assertTrue(s.visualQuality.frequencySmoothing && s.visualQuality.peakHold && s.visualQuality.trails && s.visualQuality.glow);
        assertEquals(256, s.visualQuality.detail);
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
    void profileSelectionKeepsIndependentTempoPreferenceAndOldJsonLoads() {
        var a = new AudioSettings();
        a.enhancements.preservePitch = true;
        for (String name : List.of("DEFAULT", "MUSIC", "GAMING", "VOCAL", "NIGHT")) {
            a.selectProfile(name);
            assertTrue(a.enhancements.preservePitch, name);
            assertTrue(a.playbackCopy().enhancements.preservePitch);
        }
        var old = ConfigStore.JSON.fromJson("{\"audio\":{\"neutral\":true}}", Settings.class);
        old.validate();
        assertFalse(old.audio.enhancements.preservePitch);
    }
}
