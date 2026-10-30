package com.darkmusic.client.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Saves the newest JSON to one file on a background executor. Saves that arrive while a write runs replace each other,
 * so a burst of changes costs at most one more write, and writes never overlap or reorder.
 * <p>
 * Each write returns its acknowledgement: it completes once that JSON, or a newer one that replaced it before it was
 * written, is on disk, and fails if that write fails or the executor refuses it. Returning from write means only that
 * the JSON is queued.
 */
public final class LatestWriter {
    private final Executor io;
    private final Path file;
    private final String invalidPrefix;
    private final Consumer<Exception> failed;
    private String pending;
    private CompletableFuture<Void> pendingWritten;
    private boolean writing;
    private volatile boolean preserveInvalid;

    public LatestWriter(Executor io, Path file, String invalidPrefix, Consumer<Exception> failed) {
        this.io = io;
        this.file = file;
        this.invalidPrefix = invalidPrefix;
        this.failed = failed;
    }

    /**
     * Keeps the current file, which could not be loaded, as a timestamped copy before the next write replaces it.
     */
    public void preserveInvalid() {
        preserveInvalid = true;
    }

    public CompletableFuture<Void> write(String json) {
        synchronized (this) {
            pending = json;
            if (pendingWritten == null) pendingWritten = new CompletableFuture<>();
            var written = pendingWritten;
            if (writing) return written;
            writing = true;
            try {
                io.execute(this::drain);
            } catch (RuntimeException failure) {
                writing = false;
                pending = null;
                pendingWritten = null;
                written.completeExceptionally(failure);
                throw failure;
            }
            return written;
        }
    }

    private void drain() {
        while (true) {
            String json;
            CompletableFuture<Void> written;
            synchronized (this) {
                json = pending;
                written = pendingWritten;
                pending = null;
                pendingWritten = null;
                if (json == null) {
                    writing = false;
                    return;
                }
            }
            try {
                if (preserveInvalid && Files.exists(file)) {
                    Files.copy(file, file.resolveSibling(invalidPrefix + System.currentTimeMillis() + ".json"));
                    preserveInvalid = false;
                }
                ConfigStore.atomicWrite(file, json);
                written.complete(null);
            } catch (Exception e) {
                failed.accept(e);
                written.completeExceptionally(e);
            }
        }
    }
}
