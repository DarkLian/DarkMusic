package com.darkmusic.client.hud;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircularRotationTest {
    @Test
    void rotationDefaultsOffAndRepairsSavedValues() {
        var settings = ConfigStore.JSON.fromJson("{}", Settings.class);
        settings.validate();
        assertEquals(Settings.Rotation.OFF, settings.circularRotation);
        assertEquals(Settings.Rotation.OFF, settings.circularProgressRotation);
        assertEquals(20, settings.circularRotationSeconds);
        settings.circularRotation = Settings.Rotation.COUNTERCLOCKWISE;
        settings.circularProgressRotation = Settings.Rotation.CLOCKWISE;
        var restored = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        assertEquals(Settings.Rotation.COUNTERCLOCKWISE, restored.circularRotation);
        assertEquals(Settings.Rotation.CLOCKWISE, restored.circularProgressRotation);
        restored.circularRotation = restored.circularProgressRotation = null;
        restored.circularRotationSeconds = 0;
        restored.validate();
        assertEquals(Settings.Rotation.OFF, restored.circularRotation);
        assertEquals(Settings.Rotation.OFF, restored.circularProgressRotation);
        assertEquals(4, restored.circularRotationSeconds);
    }

    @Test
    void oppositeDirectionsShareOneAngle() throws InterruptedException {
        assertEquals(1, Settings.Rotation.CLOCKWISE.sign());
        assertEquals(-1, Settings.Rotation.COUNTERCLOCKWISE.sign());
        assertEquals(0, Settings.Rotation.OFF.sign());
        double start = CircularRotation.advance(4, true);
        Thread.sleep(50);
        double moved = CircularRotation.advance(4, true);
        assertTrue(moved != start, "The angle advances while moving");
        Thread.sleep(50);
        assertEquals(moved, CircularRotation.advance(4, false), "The angle holds while stopped");
    }
}
