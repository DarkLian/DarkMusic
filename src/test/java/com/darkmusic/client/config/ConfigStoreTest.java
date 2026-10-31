package com.darkmusic.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir
    Path temp;

    @Test
    void nativeShortcutMigrationDetectsNativePreferencesWithoutSerializingItsState() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("config/darkmusic"));
        Files.writeString(temp.resolve("options.txt"), "key_key.darkmusic.volume_up:key.keyboard.f10\nkey_key.other.mod:key.keyboard.f9\n");
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new ConfigStore(directory, io, message -> fail(message));
            var settings = io.submit(store::load).get();
            assertEquals(Set.of("key.darkmusic.volume_up"), settings.nativeShortcutNames);
            assertFalse(ConfigStore.JSON.toJson(settings).contains("nativeShortcutNames"));
        }
    }

    @Test
    void undecodableOptionsFileWarnsButStillLoadsSettings() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("legacy/config/darkmusic"));
        // A Big5-encoded server name, as written by a legacy editor: invalid UTF-8 midway through the file.
        byte[] legacy = {'l', 'a', 's', 't', 'S', 'e', 'r', 'v', 'e', 'r', ':', (byte) 0xA4, (byte) 0xA4, '\n',
            'k', 'e', 'y', '_', 'k', 'e', 'y', '.', 'd', 'a', 'r', 'k', 'm', 'u', 's', 'i', 'c', '.', 'n', 'e', 'x', 't', ':', 'k', '\n'};
        Files.write(temp.resolve("legacy/options.txt"), legacy);
        var warnings = new CopyOnWriteArrayList<String>();
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new ConfigStore(directory, io, warnings::add);
            var settings = io.submit(store::load).get();
            assertNotNull(settings, "Settings must load even when shortcut detection cannot read options.txt");
            assertEquals(1, warnings.size(), "The unreadable shortcut file is reported once");
            assertNotNull(store.snapshotJson());
        }
    }
}
