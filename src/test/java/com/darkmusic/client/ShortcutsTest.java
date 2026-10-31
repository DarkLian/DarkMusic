package com.darkmusic.client;

import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutsTest {
    private static final String BACK = "key.darkmusic.seek_back", FORWARD = "key.darkmusic.seek_forward";

    @Test
    void onlyTheOldArrowDefaultsWithoutSeekOverridesMigrate() {
        Settings settings = new Settings();
        assertTrue(Shortcuts.migration(settings, BACK, FORWARD, "key.keyboard.right", "key.keyboard.left"));
        for (String key : List.of(BACK, FORWARD)) {
            settings.nativeShortcutNames = Set.of(key);
            assertFalse(Shortcuts.migration(settings, BACK, FORWARD, "key.keyboard.right", "key.keyboard.left"));
            settings.nativeShortcutNames = Set.of();
            settings.shortcuts.put(key, "key.keyboard.unknown");
            assertFalse(Shortcuts.migration(settings, BACK, FORWARD, "key.keyboard.right", "key.keyboard.left"));
            settings.shortcuts.clear();
        }
        assertFalse(Shortcuts.migration(settings, BACK, FORWARD, "key.keyboard.equal", "key.keyboard.left"));
        assertFalse(Shortcuts.migration(settings, BACK, FORWARD, "key.keyboard.right", "key.keyboard.minus"));
    }

    @Test
    void unrelatedNativeNamesAndSavedKeysDoNotBlockMigrationOrGetChanged() {
        Settings settings = new Settings();
        settings.nativeShortcutNames = Set.of("key.darkmusic.toggle");
        settings.shortcuts.put("key.darkmusic.open", "key.keyboard.b");
        var before = Map.copyOf(settings.shortcuts);
        assertTrue(Shortcuts.migration(settings, BACK, FORWARD, "key.keyboard.right", "key.keyboard.left"));
        assertEquals(before, settings.shortcuts);
    }

    @Test
    void savedBindingsClearOnlyWhenNativeOptionsCoverEveryBinding() {
        var saved = new LinkedHashMap<>(Map.of("open", "key.keyboard.b", "speed", "key.keyboard.equal"));
        var before = Map.copyOf(saved);
        ShortcutMigration.clearSaved(Set.of("open"), saved, List.of("open", "speed"));
        assertEquals(before, saved);
        ShortcutMigration.clearSaved(Set.of("open", "speed", "unrelated"), saved, List.of("open", "speed"));
        assertTrue(saved.isEmpty());
    }
}
