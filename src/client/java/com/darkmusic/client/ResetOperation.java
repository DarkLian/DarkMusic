package com.darkmusic.client;

import com.darkmusic.client.text.Text;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Reset to Defaults after confirmation, in phases with one owner each:
 * <ol>
 * <li>begin, on the client thread: resetting starts, which closes admission for every other operation and stops
 * per-tick work. Playback stops and pending folder checks and screen requests are dropped. Returns the storage task.
 * <li>The storage task, on the storage executor: closes the captured old services and waits for them; only once all
 * have stopped does it clear storage. Returns the files it had to keep.
 * <li>publish, on the client thread: builds and installs the replacement services, with the defaults when storage
 * was cleared and the prior settings otherwise; the old services are closed either way. Resetting still holds, so
 * nothing runs against a half-built set.
 * <li>release: resetting ends and the settings are saved. After a full reset the saved config is read back on the
 * storage executor, behind that save.
 * </ol>
 * The returned future completes on every path. If the client closes before publishing, or the storage executor
 * refuses the task, nothing is published. If publishing throws, what it built is abandoned and the client stays
 * disabled until restart.
 */
final class ResetOperation {
    interface Steps {
        /**
         * Client thread. Starts the reset and returns the work for the storage executor.
         */
        Callable<List<String>> begin();

        /**
         * Client thread. Builds and installs replacements; defaults says whether storage was cleared.
         */
        void publish(boolean defaults);

        /**
         * Client thread, after publish threw: closes whatever publish had built.
         */
        void abandon();

        /**
         * Client thread. Ends resetting, then saves.
         */
        void release();

        /**
         * Storage executor, after the save from release: confirms the saved config holds the defaults.
         */
        void verify() throws Exception;

        boolean closed();

        void warn(String message);
    }

    private ResetOperation() {
    }

    static CompletableFuture<Void> run(Steps steps, Executor client, Executor storage) {
        var finished = new CompletableFuture<Void>();
        var work = steps.begin();
        CompletableFuture<List<String>> stopped;
        try {
            stopped = CompletableFuture.supplyAsync(() -> {
                try {
                    return work.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, storage);
        } catch (RuntimeException rejected) {
            // The storage executor stops only at shutdown; nothing was stopped, so nothing may be replaced.
            finished.completeExceptionally(rejected);
            return finished;
        }
        stopped.whenComplete((retained, failure) -> client.execute(() -> {
            try {
                finish(steps, storage, retained, unwrap(failure), finished);
            } catch (RuntimeException | LinkageError unexpected) {
                finished.completeExceptionally(unexpected);
            }
        }));
        return finished;
    }

    private static void finish(Steps steps, Executor storage, List<String> retained, Throwable failure,
                               CompletableFuture<Void> finished) {
        if (steps.closed()) {
            finished.completeExceptionally(new IllegalStateException(Text.get("reset.darkmusic.closing")));
            return;
        }
        try {
            steps.publish(failure == null);
        } catch (RuntimeException | LinkageError broken) {
            LoggerFactory.getLogger("darkmusic").error("Could not rebuild services after a reset", broken);
            try {
                steps.abandon();
            } catch (RuntimeException | LinkageError cleanup) {
                broken.addSuppressed(cleanup);
            }
            if (failure != null) broken.addSuppressed(failure);
            String message = Text.get("reset.darkmusic.restart");
            steps.warn(message);
            finished.completeExceptionally(new IllegalStateException(message, broken));
            return;
        }
        steps.release();
        if (failure != null) finished.completeExceptionally(failure);
        else if (!retained.isEmpty()) {
            String message = Text.get("reset.darkmusic.retained");
            steps.warn(message);
            finished.completeExceptionally(new IOException(message));
        } else try {
            CompletableFuture.runAsync(() -> {
                try {
                    steps.verify();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, storage).whenComplete((unused, error) -> {
                if (error == null) finished.complete(null);
                else finished.completeExceptionally(unwrap(error));
            });
        } catch (RuntimeException rejected) {
            finished.completeExceptionally(rejected);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }
}
