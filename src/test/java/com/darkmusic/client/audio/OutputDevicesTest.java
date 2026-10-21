package com.darkmusic.client.audio;

import javax.sound.sampled.Mixer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutputDevicesTest {
    private static class Info extends Mixer.Info {
        Info(String name, String version) {
            super(name, "vendor", "playback output", version);
        }
    }

    @Test
    void deviceIdentityDoesNotDependOnEnumerationOrder() {
        assertEquals(OutputDevices.id(new Info("Headphones", "1")), OutputDevices.id(new Info("Headphones", "1")));
        assertNotEquals(OutputDevices.id(new Info("Headphones", "1")), OutputDevices.id(new Info("Speakers", "1")));
        assertNotEquals(OutputDevices.id(new Info("Headphones", "1")), OutputDevices.id(new Info("Headphones", "2")));
        assertTrue(OutputDevices.id(new Info("耳機", "1")).matches("[0-9a-f]{64}"));
    }
}
