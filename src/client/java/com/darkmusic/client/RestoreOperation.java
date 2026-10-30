package com.darkmusic.client;

import com.darkmusic.client.text.Text;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
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
 * The media and restore flags are held from begin until end, which runs on every path. Whether a journal recovery is
 * still pending is read on the storage executor right after the install, never on the client thread.
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
         * Storage executor, after install whether or not it succeeded: whether a journal recovery is still pending.
         */
        boolean recoveryPending();

        /**
         * Client thread: replaces settings, playlists, history and key binds in memory, then saves.
         */
        void apply();

        /**
         * Client thread: releases the flags, or keeps them while a journal recovery is still pending.
         */
        void end(boolean recoveryPending);

        boolean closed();
    }

    private record Installed(Exception failure, boolean recoveryPending) {
    }

    private RestoreOperation() {
    }

    static CompletableFuture<Void> run(Steps steps, Executor client, Executor storage) {
        var finished = new CompletableFuture<Void>();
        steps.begin();
        CompletableFuture<Installed> installed;
        try {
            installed = CompletableFuture.supplyAsync(() -> {
                Exception failure = null;
                try {
                    steps.install();
                } catch (Exception e) {
                    failure = e;
                }
                boolean pending;
                try {
                    pending = steps.recoveryPending();
                } catch (RuntimeException unknown) {
                    // Keep the restrictions when the journal cannot be read.
                    pending = true;
                }
                return new Installed(failure, pending);
            }, storage);
        } catch (RuntimeException rejected) {
            // The storage executor stops only at shutdown; nothing was installed.
            installed = CompletableFuture.completedFuture(new Installed(rejected, false));
        }
        installed.thenAccept(result -> client.execute(() -> {
            try {
                if (result.failure() != null)
                    finished.completeExceptionally(new IOException(Text.get("backup.failed"), result.failure()));
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
                    steps.end(result.recoveryPending());
                } catch (RuntimeException | LinkageError broken) {
                    finished.completeExceptionally(broken);
                }
            }
        }));
        return finished;
    }
}
