package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.PlayerLayout;
import com.darkmusic.client.hud.VolumeFeedback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerRefinementsTest {
    @Test
    void shortcutFeedbackHoldsThenFadesAndNewPressRestartsIt() {
        var feedback = new VolumeFeedback();
        assertEquals(0, feedback.opacity(0));
        feedback.show(.55, 0);
        assertEquals(1, feedback.opacity(0));
        assertEquals(1, feedback.opacity(1_000_000_000L));
        assertEquals(.5, feedback.opacity(1_375_000_000L), 1e-8);
        assertEquals(0, feedback.opacity(1_750_000_000L));
        feedback.show(0, 2_000_000_000L);
        assertEquals(1, feedback.opacity(2_100_000_000L));
    }

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
    void enlargedPanelKeepsAllColumnsAndMediaInsideTheirAvailableArea() {
        for (int width : new int[]{426, 640, 854, 1280})
            for (int height : new int[]{240, 360, 480, 720}) {
                var p = PlayerLayout.of(width, height);
                assertTrue(p.navigation().width() == 0 || p.navigation().width() >= 104);
                if (p.inspector().width() > 0) {
                    assertTrue(p.inspector().x() > p.tracks().x() + 240);
                    assertEquals(width - 12, p.inspector().right());
                    assertTrue(p.artworkSize() <= p.inspector().width());
                    assertTrue(p.inspector().y() + p.artworkSize() < p.dock().y());
                    assertTrue(p.spectrumY() + p.spectrumHeight() <= p.inspector().bottom());
                }
            }
        var usual = PlayerLayout.of(854, 480);
        assertTrue(usual.artworkSize() > 96);
        assertTrue(usual.inspector().width() >= 180);
        assertTrue(usual.spectrumHeight() >= 24);
    }
}
