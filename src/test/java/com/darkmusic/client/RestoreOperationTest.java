package com.darkmusic.client;

import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a backup: the durable install and the in-memory apply fail differently, and the flags are released on
 * every path.
 */
class RestoreOperationTest {
    private static class Fake implements RestoreOperation.Steps {
        final List<String> events = new ArrayList<>();
        boolean busy, closed, installFails, applyFails;

        @Override
        public void begin() {
            busy = true;
            events.add("begin");
        }

        @Override
        public void install() throws IOException {
            events.add("install");
            if (installFails) throw new IOException("disk full");
        }

        @Override
        public void apply() {
            events.add("apply");
            if (applyFails) throw new IllegalStateException("playlist store closed");
        }

        @Override
        public void end() {
            busy = false;
            events.add("end");
        }

        @Override
        public boolean closed() {
            return closed;
        }
    }

    @Test
    void installsThenAppliesThenReleases() throws Exception {
        var steps = new Fake();
        RestoreOperation.run(steps, Runnable::run, Runnable::run).get();
        assertEquals(List.of("begin", "install", "apply", "end"), steps.events);
        assertFalse(steps.busy);
    }

    @Test
    void aFailedInstallChangesNothingInMemory() {
        var steps = new Fake();
        steps.installFails = true;
        var future = RestoreOperation.run(steps, Runnable::run, Runnable::run);
        var error = assertThrows(ExecutionException.class, future::get).getCause();
        assertEquals(Text.get("backup.failed"), error.getMessage());
        assertEquals(List.of("begin", "install", "end"), steps.events);
        assertFalse(steps.busy);
    }

    @Test
    void aFailedApplyAfterTheInstallSaysARestartFinishesIt() {
        var steps = new Fake();
        steps.applyFails = true;
        var future = RestoreOperation.run(steps, Runnable::run, Runnable::run);
        var error = assertThrows(ExecutionException.class, future::get).getCause();
        assertEquals(Text.get("backup.restart"), error.getMessage());
        assertEquals("playlist store closed", error.getCause().getMessage());
        assertFalse(steps.busy, "the flags are released although applying failed");
    }

    @Test
    void closingAfterTheInstallDoesNotPretendNothingChanged() throws Exception {
        var steps = new Fake();
        var later = new ArrayList<Runnable>();
        var future = RestoreOperation.run(steps, later::add, Runnable::run);
        steps.closed = true;
        later.forEach(Runnable::run);
        future.get();
        assertTrue(steps.events.contains("install"));
        assertFalse(steps.events.contains("apply"));
        assertEquals("end", steps.events.getLast());
    }
}
