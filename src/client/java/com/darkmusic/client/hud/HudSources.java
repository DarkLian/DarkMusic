package com.darkmusic.client.hud;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.TrackWaveform;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsService;

import java.util.List;

/**
 * Everything the HUDs read. Reset and backup restore replace the services behind these, so each call returns the
 * current one; keep no result across frames.
 */
public interface HudSources {
    /**
     * Null until the config has loaded.
     */
    Settings settings();

    /**
     * Null until startup creates the player.
     */
    PlaybackController playback();

    LyricsService lyrics();

    DownloadService downloads();

    /**
     * One snapshot of the library's tracks.
     */
    List<Track> tracks();

    ArtworkCache.Cover artwork(Track track);

    /**
     * Null when no waveform is available yet.
     */
    TrackWaveform waveform(Track track);
}
