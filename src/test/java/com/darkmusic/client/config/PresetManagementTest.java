package com.darkmusic.client.config;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PresetManagementTest {
    @Test
    void saveReplaceCancelAndCapacityAreNonDestructive() {
        var e = new SoundEnvironmentSettings();
        e.select(SoundEnvironmentSettings.Preset.CATHEDRAL);
        e.savePreset("  My Room  ");
        assertTrue(e.userPresets.containsKey("My Room"));
        double saved = e.userPresets.get("My Room").parameters.decay;
        e.parameters.decay = 1;
        assertThrows(IllegalArgumentException.class, () -> e.savePreset("my ROOM"));
        assertEquals(saved, e.userPresets.get("My Room").parameters.decay);
        // Cancel invokes no mutation; replacement is a separate explicit operation.
        assertEquals(1, e.userPresets.size());
        e.savePreset("my room", true);
        assertEquals(1, e.userPresets.size());
        assertEquals(1, e.userPresets.get("My Room").parameters.decay);
        for (int i = 1; i < 32; i++) e.savePreset("Room " + i);
        assertThrows(IllegalArgumentException.class, () -> e.savePreset("Extra"));
        assertEquals(32, e.userPresets.size());
        e.savePreset("My Room", true);
        assertEquals(32, e.userPresets.size());
        for (String invalid : List.of("", "  ", "A\nB", "x".repeat(81)))
            assertThrows(IllegalArgumentException.class, () -> e.savePreset(invalid));
        assertEquals(32, e.userPresets.size());
    }

    @Test
    void renameDeleteLoadAndJsonKeepActiveSoundAndIndependentProfiles() {
        var settings = new Settings();
        var e = settings.environment;
        e.select(SoundEnvironmentSettings.Preset.TUNNEL);
        e.savePreset("Tunnel");
        e.select(SoundEnvironmentSettings.Preset.VINYL);
        e.savePreset("Vinyl");
        var current = e.playbackCopy();
        PresetNames.rename(e.userPresets, "Tunnel", "  Echo  ");
        assertTrue(e.samePlayback(current));
        assertThrows(IllegalArgumentException.class, () -> PresetNames.rename(e.userPresets, "Echo", "VINYL"));
        assertTrue(e.userPresets.containsKey("Echo"));
        e.userPresets.remove("Vinyl");
        assertTrue(e.samePlayback(current));
        PresetNames.save(settings.audio.savedProfiles, "Music", ConfigStore.JSON.toJson(settings.audio.playbackCopy()), Integer.MAX_VALUE, false);
        var audio = settings.audio.playbackCopy();
        PresetNames.rename(settings.audio.savedProfiles, "Music", "Renamed");
        assertTrue(audio.samePlayback(settings.audio));
        assertThrows(IllegalArgumentException.class, () -> PresetNames.save(settings.audio.savedProfiles, "RENAMED", "bad", Integer.MAX_VALUE, false));
        var restored = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        restored.validate();
        assertTrue(current.samePlayback(restored.environment));
        restored.environment.loadPreset("Echo");
        assertEquals(SoundEnvironmentSettings.Preset.TUNNEL, restored.environment.preset);
        assertTrue(restored.audio.savedProfiles.containsKey("Renamed"));
        String longAudioName = "A".repeat(100);
        PresetNames.save(restored.audio.savedProfiles, longAudioName, "{}", Integer.MAX_VALUE, false, 100);
        assertTrue(restored.audio.savedProfiles.containsKey(longAudioName));
        assertThrows(IllegalArgumentException.class, () -> PresetNames.save(restored.audio.savedProfiles, "A".repeat(101), "{}", Integer.MAX_VALUE, false, 100));
    }
}
