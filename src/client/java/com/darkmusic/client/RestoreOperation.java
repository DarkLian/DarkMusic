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
 * The media and restore flags are held from begin until end; end runs before the future completes, so a caller that
 * reacts to the result sees them released. Whether a journal recovery is still pending is read on the storage executor
 * right after the install, never on the client thread; if the storage task fails outside its own handling, the
 * journal state is unknown and the flags stay. If the client thread refuses the result (Minecraft stopped running
 * tasks while closing), the future fails with that refusal and the flags are left as they are: they belong to the
 * client thread, and a pending journal recovery runs on the next start.
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

    private record Installed(Throwable failure, boolean recoveryPending) {
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
        installed.whenComplete((result, broken) -> ClientDispatch.run(client, finished, () -> {
            // A storage task that died outside its own catches leaves the journal unknown: keep the restrictions.
            var cause = broken instanceof CompletionException && broken.getCause() != null ? broken.getCause() : broken;
            var outcome = result != null ? result : new Installed(cause, true);
            conclude(steps, outcome, finished);
        }));
        return finished;
    }

    /**
     * Client thread. Decides the outcome, releases the flags, and only then completes the future, so whoever waits on
     * it sees the flags already released. A failing end is reported: attached to a failure, or as the failure.
     */
    private static void conclude(Steps steps, Installed result, CompletableFuture<Void> finished) {
        Throwable failure = null;
        if (result.failure() != null) failure = new IOException(Text.get("backup.failed"), result.failure());
        else if (!steps.closed()) try {
            steps.apply();
        } catch (RuntimeException | LinkageError broken) {
            LoggerFactory.getLogger("darkmusic").error("The restored backup could not be applied", broken);
            failure = new IllegalStateException(Text.get("backup.restart"), broken);
        }
        try {
            steps.end(result.recoveryPending());
        } catch (RuntimeException | LinkageError broken) {
            LoggerFactory.getLogger("darkmusic").error("Could not release the restore flags", broken);
            if (failure == null) failure = broken;
            else failure.addSuppressed(broken);
        }
        if (failure == null) finished.complete(null);
        else finished.completeExceptionally(failure);
    }
}
