package com.darkmusic.client.gui;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.library.Track;

import java.util.List;

/**
 * Starting and moving through playback. Each applies its own admission and returns whether it ran: starting a song,
 * play/pause, skipping and seeking wait while a media operation owns the files; stop, pause and resume run whenever
 * the client is alive, so a running import can still be stopped and a preview can pause the music.
 */
public interface TransportOperations {
    boolean play(List<Track> tracks, Track track, PlaybackController.Source source);

    boolean toggle();

    boolean next();

    boolean previous();

    boolean seek(double seconds);

    boolean stop();

    boolean pause();

    boolean resume();
}
