package com.darkmusic.client.hud;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

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
    void oppositeDirectionsShareOneAngle() {
        assertEquals(1, Settings.Rotation.CLOCKWISE.sign());
        assertEquals(-1, Settings.Rotation.COUNTERCLOCKWISE.sign());
        assertEquals(0, Settings.Rotation.OFF.sign());
        var now = new AtomicLong(1_000_000_000L);
        var rotation = new CircularRotation(now::get);
        assertEquals(0, rotation.advance(4, true), "The first frame only records the time");
        now.addAndGet(1_000_000_000L);
        assertEquals(.25, rotation.advance(4, true), 1e-9, "One second of a four-second turn");
        now.addAndGet(2_000_000_000L);
        assertEquals(.25, rotation.advance(4, false), 1e-9, "The angle holds while stopped");
        now.addAndGet(3_000_000_000L);
        assertEquals(0, rotation.advance(4, true), 1e-9, "Time spent stopped does not count; three seconds wraps to a full turn");
    }
}
