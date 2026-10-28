package com.darkmusic.client.config;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owns the live settings. Load, reset and backup restore replace them here, and every write to disk passes the same
 * rule: nothing is written before the config loads or while the client cannot write (shutting down, resetting or
 * restoring). Screens still edit fields in place and save when a change is committed.
 */
public final class SettingsStore implements SettingsSource {
    private final Consumer<Settings> writer;
    private final BooleanSupplier writable;
    private final Runnable apply;
    private volatile Settings current;

    /**
     * @param writer   writes settings to disk
     * @param writable whether the client may write now
     * @param apply    pushes the settings into the running services
     */
    public SettingsStore(Consumer<Settings> writer, BooleanSupplier writable, Runnable apply) {
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
            writer.accept(settings);
        }
    }

    /**
     * Writes without reapplying, for values the services already hold, such as the resume point.
     */
    public void persist() {
        var settings = current;
        if (settings != null && writable.getAsBoolean()) writer.accept(settings);
    }
}
