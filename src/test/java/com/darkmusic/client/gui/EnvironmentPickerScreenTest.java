package com.darkmusic.client.gui;

import com.darkmusic.client.config.SoundEnvironmentSettings;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentPickerScreenTest {
    @Test
    void directEnvironmentListContainsEveryPresetExactlyOnce() {
        var available = EnvironmentPickerScreen.GROUPS.stream().flatMap(List::stream).toList();
        // Custom is a blank recipe with its own row, not a built-in.
        assertEquals(SoundEnvironmentSettings.BUILT_IN.size(), available.size());
        assertEquals(SoundEnvironmentSettings.BUILT_IN.size(), new HashSet<>(available).size());
        assertEquals(EnumSet.copyOf(SoundEnvironmentSettings.BUILT_IN), EnumSet.copyOf(available));
    }
}
