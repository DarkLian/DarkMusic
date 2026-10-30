package com.darkmusic.client;

import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a backup: the durable install and the in-memory apply fail differently, and the flags are released on
 * every path.
 */
class RestoreOperationTest {
    private static class Fake implements RestoreOperation.Steps {
        final List<String> events = new ArrayList<>();
        boolean busy, closed, installFails, applyFails, endFails, pending, checkedOnStorage, onStorage;
        Error installDies;

        @Override
        public void begin() {
            busy = true;
            events.add("begin");
        }

        @Override
        public void install() throws IOException {
            events.add("install");
            if (installDies != null) throw installDies;
            if (installFails) throw new IOException("disk full");
        }

        @Override
        public boolean recoveryPending() {
            checkedOnStorage = onStorage;
            return pending;
        }

        @Override
        public void apply() {
            events.add("apply");
            if (applyFails) throw new IllegalStateException("playlist store closed");
        }

        @Override
        public void end(boolean recoveryPending) {
            events.add("end");
            if (endFails) throw new IllegalStateException("history store closed");
            busy = recoveryPending;
        }

        @Override
        public boolean closed() {
            return closed;
        }
    }

    @Test
    void installsThenAppliesThenReleases() throws Exception {
        var steps = new Fake();
        RestoreOperation.run(steps, Runnable::run, Runnable::run).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("begin", "install", "apply", "end"), steps.events);
        assertFalse(steps.busy);
    }

    @Test
    void aFailedInstallChangesNothingInMemory() {
        var steps = new Fake();
        steps.installFails = true;
        var future = RestoreOperation.run(steps, Runnable::run, Runnable::run);
        var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
        assertEquals(Text.get("backup.failed"), error.getMessage());
        assertEquals(List.of("begin", "install", "end"), steps.events);
        assertFalse(steps.busy);
    }

    @Test
    void aFailedApplyAfterTheInstallSaysARestartFinishesIt() {
        var steps = new Fake();
        steps.applyFails = true;
        var future = RestoreOperation.run(steps, Runnable::run, Runnable::run);
        var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
        assertEquals(Text.get("backup.restart"), error.getMessage());
        assertEquals("playlist store closed", error.getCause().getMessage());
        assertTrue(steps.busy, "the services hold stale data, so nothing may save it over the restored files");
    }

    @Test
    void closingAfterTheInstallDoesNotPretendNothingChanged() throws Exception {
        var steps = new Fake();
        var later = new ArrayList<Runnable>();
        var future = RestoreOperation.run(steps, later::add, Runnable::run);
        steps.closed = true;
        later.forEach(Runnable::run);
        future.get(5, TimeUnit.SECONDS);
        assertTrue(steps.events.contains("install"));
        assertFalse(steps.events.contains("apply"));
        assertEquals("end", steps.events.getLast());
    }

    @Test
    void aPendingRecoveryIsReadOnStorageAndKeepsTheFlags() throws Exception {
        var steps = new Fake();
        steps.pending = true;
        steps.installFails = true;
        var future = RestoreOperation.run(steps, Runnable::run, task -> {
            steps.onStorage = true;
            try {
                task.run();
            } finally {
                steps.onStorage = false;
            }
        });
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(steps.checkedOnStorage, "the journal is read on the storage executor, not the client thread");
        assertTrue(steps.busy, "media actions stay blocked until the journal recovers");
    }

    @Test
    void theFutureCompletesOnlyAfterTheFlagsAreSettled() {
        for (boolean applyFails : new boolean[]{false, true}) {
            var steps = new Fake();
            steps.applyFails = applyFails;
            var client = new ArrayList<Runnable>();
            var future = RestoreOperation.run(steps, client::add, Runnable::run);
            var endedWhenDone = new boolean[1];
            var busyWhenDone = new boolean[]{!applyFails};
            future.whenComplete((unused, failure) -> {
                endedWhenDone[0] = steps.events.contains("end");
                busyWhenDone[0] = steps.busy;
            });
            client.forEach(Runnable::run);
            assertTrue(future.isDone());
            assertTrue(endedWhenDone[0], "a caller reacting to the result must see the flags already settled");
            // Released after success; kept for the restart after a failed apply.
            assertEquals(applyFails, busyWhenDone[0]);
        }
    }

    @Test
    void aFailingEndIsReportedNotLost() {
        var steps = new Fake();
        steps.endFails = true;
        var error = assertThrows(ExecutionException.class, () -> RestoreOperation.run(steps, Runnable::run, Runnable::run).get(5, TimeUnit.SECONDS)).getCause();
        assertEquals("history store closed", error.getMessage(), "after a successful apply the end failure is the result");

        var both = new Fake();
        both.endFails = true;
        both.installFails = true;
        error = assertThrows(ExecutionException.class, () -> RestoreOperation.run(both, Runnable::run, Runnable::run).get(5, TimeUnit.SECONDS)).getCause();
        assertEquals(Text.get("backup.failed"), error.getMessage(), "the original failure stays the result");
        assertEquals("history store closed", error.getSuppressed()[0].getMessage());
    }

    @Test
    void aRefusedClientDispatchFailsTheFutureAndLeavesClientStateAlone() {
        var steps = new Fake();
        var future = RestoreOperation.run(steps, task -> {
            throw new RejectedExecutionException("client stopped");
        }, Runnable::run);
        var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(RejectedExecutionException.class, error);
        assertFalse(steps.events.contains("apply"));
        assertFalse(steps.events.contains("end"), "flags belong to the client thread, which is gone");
    }

    @Test
    void aStorageTaskThatDiesStillEndsAndKeepsTheRestrictions() {
        var steps = new Fake();
        steps.installDies = new AssertionError("journal writer crashed");
        var future = RestoreOperation.run(steps, Runnable::run, Runnable::run);
        var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
        assertEquals(Text.get("backup.failed"), error.getMessage());
        assertEquals("journal writer crashed", error.getCause().getMessage());
        assertFalse(steps.events.contains("apply"));
        assertEquals("end", steps.events.getLast());
        assertTrue(steps.busy, "the journal state is unknown, so the restrictions stay");
    }
}
