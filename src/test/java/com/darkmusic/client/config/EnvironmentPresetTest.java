package com.darkmusic.client.config;

import com.darkmusic.client.config.SoundEnvironmentSettings.Preset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentPresetTest {
    @Test
    void choosingAnEnvironmentTurnsItOnAndOffTurnsItOff() {
        var e = new SoundEnvironmentSettings();
        e.select(Preset.BEDROOM);
        assertTrue(e.enabled);
        assertFalse(e.custom);
        e.select(Preset.OFF);
        assertFalse(e.enabled);
    }

    @Test
    void anEditToABuiltInBecomesCustomAndSettingItBackReturnsToIt() {
        var e = new SoundEnvironmentSettings();
        e.select(Preset.CONCERT_HALL);
        double wet = e.parameters.wet;
        e.parameters.wet = wet + .1;
        e.matchPreset();
        assertTrue(e.custom);
        e.parameters.wet = wet + 1e-9;
        e.matchPreset();
        assertFalse(e.custom);
    }

    @Test
    void builtInRecipesNeverChange() {
        var before = SoundEnvironmentSettings.recipe(Preset.CATHEDRAL);
        var e = new SoundEnvironmentSettings();
        e.select(Preset.CATHEDRAL);
        e.parameters.decay = 7;
        e.matchPreset();
        assertTrue(before.same(SoundEnvironmentSettings.recipe(Preset.CATHEDRAL)));
        e.select(Preset.CATHEDRAL);
        assertTrue(before.same(e.parameters));
    }

    @Test
    void customIsABlankPlayingRecipeAndNeverASavedOne() {
        var e = new SoundEnvironmentSettings();
        e.select(Preset.STADIUM);
        e.parameters.echoGain = .3;
        e.savePreset("Echo");
        e.selectCustom();
        e.matchPreset();
        assertTrue(e.custom);
        assertTrue(e.enabled, "Custom plays");
        assertEquals(Preset.CUSTOM, e.preset);
        assertNull(e.savedName());
        assertTrue(e.parameters.close(SoundEnvironmentSettings.recipe(Preset.CUSTOM)));
        assertEquals(1, e.intensity);
    }

    @Test
    void customIsNotListedWithTheBuiltIns() {
        assertFalse(SoundEnvironmentSettings.BUILT_IN.contains(Preset.CUSTOM));
        assertEquals(Preset.values().length - 1, SoundEnvironmentSettings.BUILT_IN.size());
    }

    @Test
    void choosingTheSelectedEnvironmentAgainRestoresItsRecipe() {
        var e = new SoundEnvironmentSettings();
        e.select(Preset.WAREHOUSE);
        e.parameters.drive = 2;
        e.intensity = .4;
        e.select(Preset.WAREHOUSE);
        assertFalse(e.custom);
        assertEquals(1, e.intensity);
        assertTrue(e.parameters.close(SoundEnvironmentSettings.recipe(Preset.WAREHOUSE)));
    }

    @Test
    void aSavedEnvironmentStaysItselfWhileEditedAndSaveWritesBackToIt() {
        var e = new SoundEnvironmentSettings();
        e.selectCustom();
        e.parameters.pan = .3;
        e.savePreset("Mine");
        assertEquals("Mine", e.savedName());
        e.parameters.pan = -.6;
        e.matchPreset();
        assertEquals("Mine", e.savedName(), "Editing a saved environment keeps it selected");
        assertFalse(e.custom);
        e.saveSelected();
        assertEquals(1, e.userPresets.size(), "Save wrote back instead of adding one");
        e.select(Preset.OFF);
        e.loadPreset("Mine");
        assertEquals(-.6, e.parameters.pan);
    }

    @Test
    void equalSavedEnvironmentsAreToldApartByTheOneChosen() {
        var e = new SoundEnvironmentSettings();
        e.select(Preset.BEDROOM);
        e.parameters.drive = 1;
        e.savePreset("First");
        e.savePreset("Second");
        e.loadPreset("Second");
        assertEquals("Second", e.savedName());
        e.loadPreset("First");
        assertEquals("First", e.savedName());
    }

    @Test
    void choosingABuiltInOrCustomLeavesTheSavedEnvironment() {
        var e = new SoundEnvironmentSettings();
        e.select(Preset.TUNNEL);
        e.savePreset("Tunnel copy");
        e.select(Preset.TUNNEL);
        assertNull(e.savedName());
        e.loadPreset("Tunnel copy");
        e.selectCustom();
        assertNull(e.savedName());
    }

    @Test
    void theRemovedEnableSwitchOffMigratesToOff() {
        var e = new SoundEnvironmentSettings();
        e.select(Preset.BEDROOM);
        e.enabled = false;
        e.matchPreset();
        assertEquals(Preset.OFF, e.preset);
        assertFalse(e.enabled);
    }
}
