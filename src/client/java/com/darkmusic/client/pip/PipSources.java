package com.darkmusic.client.pip;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.Settings;

/**
 * Everything PiP reads. Reset and backup restore replace the services behind these, so each call returns the current
 * one; keep no result across frames.
 */
public interface PipSources {
    /**
     * Null until the config has loaded.
     */
    Settings settings();

    /**
     * Null until startup creates the player.
     */
    PlaybackController playback();

    /**
     * Null until startup creates it.
     */
    PipController pip();
}
