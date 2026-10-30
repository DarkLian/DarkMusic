package com.darkmusic.client.audio;

import com.darkmusic.client.config.Settings;

import java.util.concurrent.TimeUnit;

/**
 * Remembers what is playing for Resume Where You Left Off: on a song change, pause or stop at once, and every
 * 30 seconds while playing, so a crash loses little. Stopping keeps the song but starts it over next time.
 */
public final class ResumeTracker {
    private static final long INTERVAL = TimeUnit.SECONDS.toNanos(30);
    private String track = "";
    private AudioBackend.Status status;
    private long savedAt;
    // Reset and backup restore replace the settings object, which has not seen what is playing yet.
    private Settings owner;

    /**
     * Updates {@code settings.resume} from the player. With {@code persist}, a song that keeps playing is updated only
     * every 30 seconds. Returns whether the point changed and should be saved.
     */
    public boolean record(Settings settings, PlaybackController playback, boolean persist) {
        if (settings != owner) {
            owner = settings;
            track = "";
            status = null;
        }
        var state = playback.state();
        var current = state.status();
        var song = state.track();
        long now = System.nanoTime();
        if (song != null && (current == AudioBackend.Status.PLAYING || current == AudioBackend.Status.PAUSED)) {
            boolean changed = !song.id().equals(track) || current != status;
            if (!changed && persist && (current != AudioBackend.Status.PLAYING || now - savedAt < INTERVAL))
                return false;
            var source = playback.playingSource();
            settings.resume = new Settings.Resume(source.mode(), source.group(), song.id(),
                Double.isFinite(state.position()) ? Math.max(0, state.position()) : 0);
            track = song.id();
        } else if (current == AudioBackend.Status.STOPPED && status != null && status != current
            && settings.resume != null) {
            var point = settings.resume;
            settings.resume = new Settings.Resume(point.mode, point.group, point.track, 0);
        } else return false;
        status = current;
        savedAt = now;
        return true;
    }
}
