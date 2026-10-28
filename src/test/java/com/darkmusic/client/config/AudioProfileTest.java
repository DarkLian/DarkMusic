package com.darkmusic.client.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioProfileTest {
    @Test
    void everyBuiltInProfileIsRecognisedAfterSelectingIt() {
        var a = new AudioSettings();
        for (String name : AudioSettings.PROFILES) {
            a.selectProfile(name);
            assertEquals(name, a.profile);
        }
    }

    @Test
    void freshSettingsAreOriginal() {
        var a = new AudioSettings();
        a.matchProfile();
        assertEquals("DEFAULT", a.profile);
        assertTrue(a.neutral);
    }

    @Test
    void anEditToABuiltInBecomesCustomAndSettingItBackReturnsToTheProfile() {
        var a = new AudioSettings();
        a.selectProfile("MUSIC");
        a.bass = 2.5;
        a.matchProfile();
        assertEquals(AudioSettings.CUSTOM, a.profile);
        // A slider dragged back lands within rounding of the original value.
        a.bass = 1e-9;
        a.matchProfile();
        assertEquals("MUSIC", a.profile);
    }

    @Test
    void customIsAlwaysABlankAdjustableStart() {
        var a = new AudioSettings();
        a.selectProfile("VOCAL");
        a.width = .6;
        a.matchProfile();
        a.selectProfile(AudioSettings.CUSTOM);
        assertEquals(AudioSettings.CUSTOM, a.profile);
        assertFalse(a.neutral, "Custom can be edited");
        assertFalse(a.normalization || a.limiter || a.compressor || a.equalizer);
        assertEquals(1, a.width, "Custom does not bring back an earlier edit");
    }

    @Test
    void profilesLeaveTransitionsGameMixPitchAndParallelAnalysisAlone() {
        var a = new AudioSettings();
        a.gapless = false;
        a.crossfade = 4;
        a.listening = AudioSettings.Listening.FOCUS;
        a.ducking = true;
        a.duckGain = .2;
        a.enhancements.preservePitch = true;
        a.parallelLoudnessAnalysis = true;
        for (String name : AudioSettings.PROFILES) {
            a.selectProfile(name);
            assertEquals(name, a.profile, "Values outside the profile do not make it Custom");
            assertFalse(a.gapless);
            assertEquals(4, a.crossfade);
            assertEquals(AudioSettings.Listening.FOCUS, a.listening);
            assertTrue(a.ducking);
            assertEquals(.2, a.duckGain);
            assertTrue(a.enhancements.preservePitch);
            assertTrue(a.parallelLoudnessAnalysis);
        }
    }

    @Test
    void profilesOwnTheAdvancedChain() {
        var a = new AudioSettings();
        a.selectProfile("MUSIC");
        a.enhancements.crossfeed = !a.enhancements.crossfeed;
        a.matchProfile();
        assertEquals(AudioSettings.CUSTOM, a.profile);
        a.selectProfile("MUSIC");
        assertEquals(new AudioEnhancements().crossfeed, a.enhancements.crossfeed);
    }

    @Test
    void gamingAndNightDifferInLoudnessTarget() {
        assertFalse(AudioSettings.recipe("GAMING").sameProfileValues(AudioSettings.recipe("NIGHT")));
    }

    @Test
    void aSavedProfileShowsCustomStaysSelectedWhileEditedAndSaveWritesBack() {
        var a = new AudioSettings();
        a.selectProfile(AudioSettings.CUSTOM);
        a.bass = 2;
        a.savedProfiles.put("Warm", a.profileJson());
        a.selectProfile("DEFAULT");
        assertTrue(a.loadSaved("Warm"));
        assertEquals("Warm", a.savedName());
        assertEquals(AudioSettings.CUSTOM, a.profile);
        a.bass = 4;
        a.matchProfile();
        assertEquals("Warm", a.savedName(), "Editing a saved profile keeps it selected");
        a.saveSelected();
        assertEquals(1, a.savedProfiles.size(), "Save wrote back instead of adding one");
        a.selectProfile("DEFAULT");
        assertTrue(a.loadSaved("Warm"));
        assertEquals(4, a.bass);
    }

    @Test
    void aSavedProfileEqualToABuiltInStillShowsCustom() {
        var a = new AudioSettings();
        a.selectProfile("NIGHT");
        a.savedProfiles.put("Like night", a.profileJson());
        assertTrue(a.loadSaved("Like night"));
        assertEquals(AudioSettings.CUSTOM, a.profile);
    }

    @Test
    void equalSavedProfilesAreToldApartByTheOneChosen() {
        var a = new AudioSettings();
        a.selectProfile("NIGHT");
        a.width = .8;
        var json = a.profileJson();
        a.savedProfiles.put("Wide night", json);
        a.savedProfiles.put("Same again", json);
        assertTrue(a.loadSaved("Same again"));
        assertEquals("Same again", a.savedName());
        assertTrue(a.loadSaved("Wide night"));
        assertEquals("Wide night", a.savedName());
    }

    @Test
    void choosingAProfileLeavesTheSavedOne() {
        var a = new AudioSettings();
        a.savedProfiles.put("Plain", a.profileJson());
        assertTrue(a.loadSaved("Plain"));
        a.selectProfile("MUSIC");
        assertNull(a.savedName());
        assertTrue(a.loadSaved("Plain"));
        a.selectProfile(AudioSettings.CUSTOM);
        assertNull(a.savedName());
    }

    @Test
    void savedProfilesDoNotCarryTheSavedListOrSelection() {
        var a = new AudioSettings();
        a.savedProfiles.put("One", a.profileJson());
        a.selectedSaved = "One";
        var saved = ConfigStore.JSON.fromJson(a.profileJson(), AudioSettings.class);
        assertTrue(saved.savedProfiles.isEmpty());
        assertNull(saved.selectedSaved);
    }

    @Test
    void theRemovedNeutralSwitchOverOtherValuesMigratesToOriginal() {
        var a = new AudioSettings();
        a.selectProfile("VOCAL");
        a.neutral = true;
        a.matchProfile();
        assertEquals("DEFAULT", a.profile);
        assertFalse(a.equalizer);
    }
}
