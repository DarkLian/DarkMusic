package com.darkmusic.client.config;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Owns the live settings. Load, reset and backup restore replace them here, and every write to disk passes the same
 * rule: nothing is written before the config loads or while the client cannot write (shutting down, resetting or
 * restoring). Screens still edit fields in place and save when a change is committed.
 */
public final class SettingsStore implements SettingsSource {
    private final Function<Settings, CompletableFuture<Void>> writer;
    private final BooleanSupplier writable;
    private final Runnable apply;
    private volatile Settings current;
    private volatile CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    /**
     * @param writer   queues settings for writing; its future completes once they, or newer ones, are on disk
     * @param writable whether the client may write now
     * @param apply    pushes the settings into the running services
     */
    public SettingsStore(Function<Settings, CompletableFuture<Void>> writer, BooleanSupplier writable, Runnable apply) {
        this.writer = writer;
        this.writable = writable;
        this.apply = apply;
    }

    @Override
    public Settings settings() {
        return current;
    }

    /**
     * Load, reset and restore install a new settings object; screens built after this read it.
     */
    public void replace(Settings settings) {
        current = settings;
    }

    /**
     * Reapplies the settings to the running services, then writes them.
     */
    @Override
    public void save() {
        var settings = current;
        if (settings != null && writable.getAsBoolean()) {
            apply.run();
            lastWrite = writer.apply(settings);
        }
    }

    /**
     * Writes without reapplying, for values the services already hold, such as the resume point.
     */
    public void persist() {
        var settings = current;
        if (settings != null && writable.getAsBoolean()) lastWrite = writer.apply(settings);
    }

    /**
     * Completes once the most recently queued settings, or newer ones, are on disk; fails if that write failed. A save
     * that was skipped (before load, or while the client cannot write) does not change it.
     */
    public CompletableFuture<Void> lastWrite() {
        return lastWrite;
    }
}
