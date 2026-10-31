package com.darkmusic.client.gui;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.Repeat;

/**
 * What the player is doing, for showing it and for enabling controls. Values and snapshots only: changes go through
 * {@link QueueOperations} and {@link TransportOperations}.
 */
public interface PlayerQueries {
    AudioBackend.State state();

    PlaybackController.Source playingSource();

    PlaybackController.UpNextSnapshot upNextSnapshot();

    boolean shuffle();

    Repeat repeat();
}
