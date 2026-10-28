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
     * Writes the settings and reapplies them; skipped while a restore owns the config.
     */
    void save();
}
