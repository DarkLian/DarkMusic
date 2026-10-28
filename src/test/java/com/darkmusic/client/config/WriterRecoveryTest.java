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
            // A null target makes every write fail with a runtime exception rather than an IOException.
            var writer = new LatestWriter(io, null, "config.invalid-", failure -> warnings.incrementAndGet());
            writer.write("{}");
            io.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
            writer.write("{}");
            io.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
            assertEquals(2, warnings.get());
            var field = LatestWriter.class.getDeclaredField("writing");
            field.setAccessible(true);
            assertFalse(field.getBoolean(writer));
        }
    }
}
