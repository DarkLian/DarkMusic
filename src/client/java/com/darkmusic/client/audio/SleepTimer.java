package com.darkmusic.client.audio;

import java.util.concurrent.TimeUnit;

/**
 * Pauses playback after a set time, fading out over the last 30 seconds. Not saved across launches.
 */
public final class SleepTimer {
    private static final long FADE = TimeUnit.SECONDS.toNanos(30);
    private long at;
    private boolean fading;

    /**
     * Pauses after {@code minutes}; 0 cancels. A fade already under way returns to {@code volume}.
     */
    public void start(int minutes, PlaybackController playback, double volume) {
        at = minutes > 0 ? Math.max(1, System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes)) : 0;
        if (fading && playback != null) playback.volume(volume);
        fading = false;
    }

    /**
     * Seconds until the timer pauses playback, or -1 when it is off.
     */
    public long remaining() {
        return at == 0 ? -1 : Math.max(0, TimeUnit.NANOSECONDS.toSeconds(at - System.nanoTime()));
    }

    public void tick(PlaybackController playback, double volume) {
        if (at == 0) return;
        long left = at - System.nanoTime();
        boolean playing = playback.state().status() == AudioBackend.Status.PLAYING;
        if (left <= 0) {
            at = 0;
            fading = false;
            if (playing) playback.pause();
            playback.volume(volume);
        } else if (left < FADE && playing) {
            fading = true;
            playback.volume(volume * left / (double) FADE);
        }
    }
}
