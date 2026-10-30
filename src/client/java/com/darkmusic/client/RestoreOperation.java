package com.darkmusic.client;

import com.darkmusic.client.text.Text;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Applying a backup, admitted on the client thread. Installing it is the durable commit: the journal writes every
 * selected file on the storage executor, and once that returns the restore holds on disk. Applying it to the running
 * services comes after, on the client thread. The two fail differently and the returned future says which:
 * <ul>
 * <li>install fails: nothing changed in memory, and the journal keeps or recovers the files; fails with
 * {@code backup.failed}.
 * <li>apply fails: the restore is on disk and takes full effect on the next start; fails with {@code backup.restart}.
 * <li>the client closes in between: completes normally, since the next start loads the installed files.
 * </ul>
 * The media and restore flags are held from begin until end, which runs on every path.
 */
final class RestoreOperation {
    interface Steps {
        /**
         * Client thread: takes the media and restore flags and pauses history writes.
         */
        void begin();

        /**
         * Storage executor: the durable commit.
         */
        void install() throws Exception;

        /**
         * Client thread: replaces settings, playlists, history and key binds in memory, then saves.
         */
        void apply();

        /**
         * Client thread: releases the flags, keeping them while a journal recovery is still pending.
         */
        void end();

        boolean closed();
    }

    private RestoreOperation() {
    }

    static CompletableFuture<Void> run(Steps steps, Executor client, Executor storage) {
        var finished = new CompletableFuture<Void>();
        steps.begin();
        CompletableFuture<Void> installed;
        try {
            installed = CompletableFuture.runAsync(() -> {
                try {
                    steps.install();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, storage);
        } catch (RuntimeException rejected) {
            installed = CompletableFuture.failedFuture(rejected);
        }
        installed.whenComplete((unused, failure) -> client.execute(() -> {
            try {
                if (failure != null)
                    finished.completeExceptionally(new IOException(Text.get("backup.failed"), failure));
                else if (steps.closed()) finished.complete(null);
                else {
                    try {
                        steps.apply();
                        finished.complete(null);
                    } catch (RuntimeException | LinkageError broken) {
                        LoggerFactory.getLogger("darkmusic").error("The restored backup could not be applied", broken);
                        finished.completeExceptionally(new IllegalStateException(Text.get("backup.restart"), broken));
                    }
                }
            } finally {
                try {
                    steps.end();
                } catch (RuntimeException | LinkageError broken) {
                    finished.completeExceptionally(broken);
                }
            }
        }));
        return finished;
    }
}
