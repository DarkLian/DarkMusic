package com.darkmusic.client;

import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reset to Defaults through its production phases, with the client thread as a queue the test drains and the
 * storage executor as a direct or rejecting one. Each case checks the final state as well as the future.
 */
class ResetOperationTest {
    /**
     * The client thread: tasks wait here until the test runs them, as they wait for the next client tick.
     */
    private static final class ClientThread implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void drain() {
            while (!tasks.isEmpty()) tasks.poll().run();
        }
    }

    private static class Fake implements ResetOperation.Steps {
        final List<String> events = new ArrayList<>();
        boolean resetting, closed, oldStopped = true, publishFails, verifyFails, releaseFails;
        Boolean published;
        List<String> retained = List.of();
        String warning = "";

        @Override
        public Callable<List<String>> begin() {
            resetting = true;
            events.add("begin");
            return () -> {
                events.add("stop old");
                if (!oldStopped) throw new IOException(Text.get("reset.darkmusic.waiting"));
                events.add("clear storage");
                return retained;
            };
        }

        @Override
        public void publish(boolean defaults) {
            events.add("build");
            if (publishFails) throw new IllegalStateException("second replacement failed");
            published = defaults;
            events.add("publish " + (defaults ? "defaults" : "prior"));
        }

        @Override
        public void abandon() {
            events.add("abandon");
        }

        @Override
        public void release() {
            resetting = false;
            events.add("release and save");
            if (releaseFails) throw new IllegalStateException("settings writer stopped");
        }

        @Override
        public void verify() throws IOException {
            events.add("verify");
            if (verifyFails) throw new IOException(Text.get("reset.darkmusic.save_failed"));
        }

        @Override
        public boolean closed() {
            return closed;
        }

        @Override
        public void warn(String message) {
            warning = message;
        }
    }

    private static Throwable failure(CompletableFuture<Void> future) {
        var thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return thrown.getCause();
    }

    @Test
    void fullResetPublishesDefaultsThenConfirmsTheSave() throws Exception {
        var client = new ClientThread();
        var steps = new Fake();
        var future = ResetOperation.run(steps, client, Runnable::run);
        assertTrue(steps.resetting);
        assertFalse(future.isDone(), "publication waits for the client thread");
        client.drain();
        future.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("begin", "stop old", "clear storage", "build", "publish defaults", "release and save", "verify"), steps.events);
        assertFalse(steps.resetting);
    }

    @Test
    void anOldServiceThatDoesNotStopLeavesStorageAndRestoresPriorSettings() {
        var client = new ClientThread();
        var steps = new Fake();
        steps.oldStopped = false;
        var future = ResetOperation.run(steps, client, Runnable::run);
        client.drain();
        assertEquals(Text.get("reset.darkmusic.waiting"), failure(future).getMessage());
        assertFalse(steps.events.contains("clear storage"));
        // The old services were closed, so the client still needs working replacements.
        assertEquals(false, steps.published);
        assertFalse(steps.resetting);
        assertFalse(steps.events.contains("verify"));
    }

    @Test
    void aReplacementThatFailsToBuildIsAbandonedAndTheClientStaysDisabled() {
        var client = new ClientThread();
        var steps = new Fake();
        steps.publishFails = true;
        var future = ResetOperation.run(steps, client, Runnable::run);
        client.drain();
        var error = failure(future);
        assertEquals(Text.get("reset.darkmusic.restart"), error.getMessage());
        assertEquals("second replacement failed", error.getCause().getMessage());
        assertEquals(List.of("begin", "stop old", "clear storage", "build", "abandon"), steps.events);
        assertTrue(steps.resetting, "no operation may start against a partial service set");
        assertEquals(Text.get("reset.darkmusic.restart"), steps.warning);
    }

    @Test
    void closingBeforeTheResultIsPostedPublishesNothing() {
        var client = new ClientThread();
        var steps = new Fake();
        var future = ResetOperation.run(steps, client, Runnable::run);
        steps.closed = true;
        client.drain();
        assertEquals(Text.get("reset.darkmusic.closing"), failure(future).getMessage());
        assertEquals(List.of("begin", "stop old", "clear storage"), steps.events);
    }

    @Test
    void keptFilesAndAFailedSaveAreReported() {
        var client = new ClientThread();
        var kept = new Fake();
        kept.retained = List.of("locked");
        var future = ResetOperation.run(kept, client, Runnable::run);
        client.drain();
        assertEquals(Text.get("reset.darkmusic.retained"), failure(future).getMessage());
        assertEquals(Text.get("reset.darkmusic.retained"), kept.warning);
        assertFalse(kept.resetting);

        var unsaved = new Fake();
        unsaved.verifyFails = true;
        future = ResetOperation.run(unsaved, client, Runnable::run);
        client.drain();
        assertEquals(Text.get("reset.darkmusic.save_failed"), failure(future).getMessage());
        assertFalse(unsaved.resetting);
    }

    @Test
    void aRejectedStorageExecutorStillCompletesTheFuture() {
        var client = new ClientThread();
        var steps = new Fake();
        Executor rejecting = task -> {
            throw new RejectedExecutionException("storage stopped");
        };
        var future = ResetOperation.run(steps, client, rejecting);
        client.drain();
        assertInstanceOf(RejectedExecutionException.class, failure(future));
        assertFalse(steps.events.contains("stop old"));
        assertFalse(steps.events.contains("build"), "the old services never stopped, so none may be replaced");
    }

    @Test
    void aRefusedClientDispatchFailsTheFutureAndPublishesNothing() {
        var steps = new Fake();
        var future = ResetOperation.run(steps, task -> {
            throw new RejectedExecutionException("client stopped");
        }, Runnable::run);
        assertInstanceOf(RejectedExecutionException.class, failure(future));
        assertFalse(steps.events.contains("build"));
        assertTrue(steps.resetting, "client-owned state is not touched from another thread");
    }

    @Test
    void aClientStepThatThrowsStillEndsTheFuture() {
        var client = new ClientThread();
        var steps = new Fake();
        steps.releaseFails = true;
        var future = ResetOperation.run(steps, client, Runnable::run);
        client.drain();
        assertEquals("settings writer stopped", failure(future).getMessage());
    }
}
