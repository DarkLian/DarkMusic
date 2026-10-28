package com.darkmusic.client.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Saves the newest JSON to one file on a background executor. Saves that arrive while a write runs replace each other,
 * so a burst of changes costs at most one more write, and writes never overlap or reorder.
 */
public final class LatestWriter {
    private final Executor io;
    private final Path file;
    private final String invalidPrefix;
    private final Consumer<Exception> failed;
    private String pending;
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

    public void write(String json) {
        synchronized (this) {
            pending = json;
            if (writing) return;
            writing = true;
            try {
                io.execute(this::drain);
            } catch (RuntimeException failure) {
                writing = false;
                throw failure;
            }
        }
    }

    private void drain() {
        while (true) {
            String json;
            synchronized (this) {
                json = pending;
                pending = null;
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
            } catch (Exception e) {
                failed.accept(e);
            }
        }
    }
}
