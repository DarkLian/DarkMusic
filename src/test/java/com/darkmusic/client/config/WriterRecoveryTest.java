package com.darkmusic.client.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WriterRecoveryTest {
    @Test
    void unexpectedFilesystemRuntimeFailuresDoNotLatchTheWriter() throws Exception {
        try (var io = Executors.newSingleThreadExecutor()) {
            var warnings = new AtomicInteger();
            var store = new ConfigStore(null, io, message -> warnings.incrementAndGet());
            store.save(new Settings());
            io.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
            store.save(new Settings());
            io.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
            assertEquals(2, warnings.get());
            var field = ConfigStore.class.getDeclaredField("writing");
            field.setAccessible(true);
            assertFalse(field.getBoolean(store));
        }
    }
}
