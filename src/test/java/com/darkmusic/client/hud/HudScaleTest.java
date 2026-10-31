package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudScaleTest {
    @Test
    void hudWheelUsesTenthsAndClampsTheCombinedComponent() {
        var wheel = new HudScale();
        assertEquals(1.1, wheel.scroll(1, 1));
        assertEquals(1, wheel.scroll(1.1, -1));
        assertEquals(1, wheel.scroll(1, .4));
        assertEquals(1.1, wheel.scroll(1, .6));
        assertEquals(3, wheel.scroll(2.9, 20));
        assertEquals(.5, wheel.scroll(.6, -20));
        var settings = new Settings();
        settings.hudScale = 2;
        settings.validate();
        var bounds = HudLayout.bounds(settings, 850, 480);
        assertEquals(440, bounds.width());
        assertEquals(164, bounds.height());
        settings.hudScale = Double.NaN;
        settings.validate();
        assertEquals(1, settings.hudScale);
        settings.hudScale = 3;
        var compact = HudLayout.bounds(settings, 200, 100);
        assertTrue(compact.x() + compact.width() <= 192 && compact.y() + compact.height() <= 92);
    }
}
