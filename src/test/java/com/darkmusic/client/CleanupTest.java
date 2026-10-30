package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shutdown and startup unwinding rely on every step being attempted once, in order.
 */
class CleanupTest {
    @Test
    void aFailingStepDoesNotStrandTheRest() {
        var ran = new ArrayList<String>();
        var failures = new Cleanup()
            .then("downloads", () -> ran.add("downloads"))
            .then("library", () -> {
                throw new IllegalStateException("watcher stuck");
            })
            .then("history", () -> ran.add("history"))
            .then("storage", () -> ran.add("storage"))
            .run();
        assertEquals(List.of("downloads", "history", "storage"), ran);
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().getMessage().startsWith("library"));
        assertEquals("watcher stuck", failures.getFirst().getCause().getMessage());
    }

    @Test
    void resourcesUnwindInReverseAndOnlyOnce() {
        var ran = new ArrayList<String>();
        var acquired = new Cleanup().first("artwork", () -> ran.add("artwork")).first("playback", () -> ran.add("playback"));
        acquired.run();
        acquired.run();
        assertEquals(List.of("playback", "artwork"), ran);
    }
}
