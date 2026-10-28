package com.darkmusic.client.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SettingsStoreTest {
    private final List<String> events = new ArrayList<>();
    private final List<Settings> written = new ArrayList<>();
    private boolean writable = true;
    private final SettingsStore store = new SettingsStore(settings -> {
        events.add("write");
        written.add(settings);
    }, () -> writable, () -> events.add("apply"));

    @Test
    void nothingIsWrittenBeforeTheConfigLoads() {
        assertNull(store.settings());
        store.save();
        store.persist();
        assertEquals(List.of(), events);
    }

    @Test
    void saveAppliesBeforeWriting() {
        var settings = new Settings();
        store.replace(settings);
        store.save();
        assertEquals(List.of("apply", "write"), events);
        assertSame(settings, written.getFirst());
    }

    @Test
    void persistWritesWithoutApplying() {
        store.replace(new Settings());
        store.persist();
        assertEquals(List.of("write"), events);
    }

    @Test
    void nothingIsWrittenWhileTheClientCannotWrite() {
        store.replace(new Settings());
        writable = false;
        store.save();
        store.persist();
        assertEquals(List.of(), events);
    }

    @Test
    void replaceInstallsTheNewObjectForLaterSaves() {
        var first = new Settings();
        var second = new Settings();
        store.replace(first);
        store.replace(second);
        store.save();
        assertSame(second, store.settings());
        assertSame(second, written.getFirst());
    }
}
