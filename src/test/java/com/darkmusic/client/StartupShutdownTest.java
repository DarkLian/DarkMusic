package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup unwinding and the closing order, driven through the production sequences without Minecraft.
 */
class StartupShutdownTest {
    private final List<String> events = new ArrayList<>();

    private AutoCloseable service(String name) {
        return () -> events.add(name);
    }

    private class Starting implements Startup.Steps {
        final RuntimeException failure;
        Throwable reported;

        Starting(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void build(Cleanup acquired) {
            for (String name : List.of("history", "artwork", "playback")) {
                events.add("built " + name);
                acquired.first(name, () -> events.add("closed " + name));
            }
            acquired.first("library", () -> {
                throw new IllegalStateException("watcher stuck");
            });
            if (failure != null) throw failure;
            events.add("built library");
        }

        @Override
        public void withdraw() {
            events.add("withdrawn");
        }

        @Override
        public void failed(Throwable failure) {
            reported = failure;
        }

        @Override
        public void save() {
            events.add("saved");
        }
    }

    @Test
    void aCompleteStartupIsSavedAndKeepsItsServices() {
        assertTrue(Startup.run(new Starting(null)));
        assertEquals("saved", events.getLast());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("closed") || e.equals("withdrawn")));
    }

    @Test
    void aFailedStartupClosesWhatItBuiltInReverseAndReportsTheOriginalError() {
        var failure = new IllegalStateException("downloads could not start");
        var steps = new Starting(failure);
        assertFalse(Startup.run(steps));
        assertEquals(List.of("built history", "built artwork", "built playback",
            "closed playback", "closed artwork", "closed history", "withdrawn"), events);
        assertSame(failure, steps.reported, "the original error is reported, not a close failure");
        assertEquals("watcher stuck", steps.reported.getSuppressed()[0].getCause().getMessage());
    }

    @Test
    void closingSavesFirstStopsAdmissionThenClosesConsumersBeforeTheirServicesAndDrainsStorageLast() {
        var failures = Shutdown.run(new Shutdown.Steps() {
            @Override
            public void saveFinal() {
                events.add("save");
            }

            @Override
            public void close() {
                events.add("closed");
            }

            @Override
            public void cancelWork() {
                events.add("cancel");
            }

            @Override
            public void flushHistory() {
                events.add("history");
            }

            @Override
            public void drainStorage() {
                events.add("storage");
            }
        }, new Shutdown.Services(service("updates"), service("discord"), service("media controls"), null,
            service("pip"), service("source links"), null, null, null, service("lyrics"), () -> {
            throw new IllegalStateException("download worker stuck");
        }, service("library"), service("playback"), service("artwork"), service("waveforms")));
        assertEquals(List.of("save", "closed", "cancel", "updates", "discord", "media controls", "pip", "source links",
            "lyrics", "library", "playback", "artwork", "waveforms", "history", "storage"), events);
        assertEquals(1, failures.size(), "a failed close is reported and the rest still run");
        assertTrue(events.indexOf("pip") < events.indexOf("playback") && events.indexOf("lyrics") < events.indexOf("library"));
    }
}
