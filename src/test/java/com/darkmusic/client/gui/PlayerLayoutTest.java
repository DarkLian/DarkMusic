package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLayoutTest {
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

    @Test
    void timeReadoutFitsBetweenSeekBarAndTransportAtEveryBreakpoint() {
        for (int width : new int[]{320, 480, 640, 800, 854, 1280, 1720})
            for (int height : new int[]{240, 300, 360, 400, 480, 720}) {
                var layout = PlayerLayout.of(width, height);
                assertTrue(layout.timeY() >= layout.seekY() + 20, width + "x" + height);
                assertTrue(layout.timeY() + 9 <= layout.transportY(), width + "x" + height);
            }
    }
}
