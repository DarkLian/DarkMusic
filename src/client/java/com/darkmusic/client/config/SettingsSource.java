package com.darkmusic.client.config;

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
}
