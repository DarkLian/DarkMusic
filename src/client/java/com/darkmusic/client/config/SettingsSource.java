package com.darkmusic.client.config;

import java.util.function.Consumer;

/**
 * The live settings and the client's save. Reset and backup restore replace the settings object, so read it on each
 * use instead of keeping it.
 */
public interface SettingsSource {
    /**
     * Null until the config has loaded.
     */
    Settings settings();

    /**
     * Commits a change: applies the settings to the running services, then queues the write. Skipped before the config
     * loads and while shutdown, reset or a restore owns the config. A queued write is not yet on disk.
     */
    void save();

    /**
     * Commits a change: edits the current settings, then saves as {@link #save()} does. The edit always lands on the
     * live object, never on one that reset or restore has replaced. Does nothing before the config loads. Changes
     * previewed while a control moves still edit in place and save when the control is released.
     */
    default void update(Consumer<Settings> edit) {
        var settings = settings();
        if (settings == null) return;
        edit.accept(settings);
        save();
    }
}
