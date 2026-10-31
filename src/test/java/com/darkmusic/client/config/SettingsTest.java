package com.darkmusic.client.config;

import com.darkmusic.client.hud.CircularHudLayout;
import com.darkmusic.client.render.HudSurface;
import com.darkmusic.client.render.UiTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test
    void lastAudibleVolumeSurvivesMuteAndLegacyConfigurations() {
        var s = ConfigStore.JSON.fromJson("{\"volume\":0,\"lastAudibleVolume\":0.35,\"playerVisualizer\":true,\"hudVisualizer\":false}", Settings.class);
        s.validate();
        assertEquals(.35, s.lastAudibleVolume);
        assertFalse(s.hudVisualizer);
        var copy = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(s), Settings.class);
        copy.validate();
        assertEquals(.35, copy.lastAudibleVolume);
        assertFalse(ConfigStore.JSON.toJson(copy).contains("playerVisualizer"));
        s.volume = .25;
        s.lastAudibleVolume = .7;
        s.validate();
        assertEquals(.25, s.lastAudibleVolume);
        s.volume = 0;
        s.lastAudibleVolume = Double.NaN;
        s.validate();
        assertEquals(1, s.lastAudibleVolume);
    }

    @Test
    void customizationsPersistAndDefaultsKeepExistingAppearance() {
        var settings = new Settings();
        settings.validate();
        assertEquals(0x00111420, HudSurface.background(settings));
        assertEquals(CircularHudLayout.of(true, true), CircularHudLayout.of(true, true, settings.circularSpectrumHeight));
        settings.menuBackgroundColor = 0x224433;
        settings.menuAccentColor = 0xEECCCC;
        settings.hudBackgroundGradient = true;
        settings.hudBackgroundColors.end = 0x884422;
        settings.hudBackgroundTransparency = .5;
        settings.circularSpectrumHeight = 60;
        var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        loaded.validate();
        assertTrue(loaded.hudBackgroundGradient);
        assertEquals(0x884422, loaded.hudBackgroundColors.last());
        assertEquals(128, HudSurface.background(loaded) >>> 24);
        try {
            UiTheme.apply(loaded);
            assertEquals(0xFF224433, UiTheme.BACKGROUND);
            assertEquals(0xFFEECCCC, UiTheme.ACCENT);
        } finally {
            UiTheme.apply(new Settings());
        }
        assertTrue(CircularHudLayout.of(true, true, 60).height() > CircularHudLayout.of(true, true).height());
    }

    @Test
    void volumeStepsRoundCleanlyAndStayWithinTheAudibleRange() {
        assertEquals(.75, Settings.clampVolume(.7 + .05));
        assertEquals(.7, Settings.clampVolume(.75 - .05));
        assertEquals(1, Settings.clampVolume(1 + .05));
        assertEquals(0, Settings.clampVolume(-.05));
        assertEquals(1, Settings.clampVolume(Double.NaN));
    }

    @Test
    void menusDefaultToABlackBackground() {
        assertEquals(0x000000, new Settings().menuBackgroundColor);
    }

    @Test
    void hudStaysHiddenInMenusUnlessEnabled() {
        assertFalse(new Settings().hudInMenus);
    }
}
