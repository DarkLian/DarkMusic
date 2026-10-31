package com.darkmusic.client.gui;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelpTest {
    @Test
    void hoverPreferenceDefaultsOnAndTooltipBoxesStayWithinViewport() {
        assertTrue(ConfigStore.JSON.fromJson("{}", Settings.class).showHoverInfo);
        var settings = new Settings();
        settings.showHoverInfo = false;
        assertFalse(ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class).showHoverInfo);
        for (int w : new int[]{160, 320, 854})
            for (int h : new int[]{120, 240, 480})
                for (int x : new int[]{0, w / 2, w - 1})
                    for (int y : new int[]{0, h / 2, h - 1}) {
                        var p = Help.position(w, h, x, y, w - 20, h - 20);
                        assertTrue(p.x() >= 4 && p.x() + w - 20 <= w - 4);
                        assertTrue(p.y() >= 4 && p.y() + h - 20 <= h - 4);
                    }
    }
}
