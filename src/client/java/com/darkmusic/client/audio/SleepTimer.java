package com.darkmusic.client.audio;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Pauses playback after a set time, fading out over the last 30 seconds. Not saved across launches.
 */
public final class SleepTimer {
    private static final long FADE = TimeUnit.SECONDS.toNanos(30);
    private final LongSupplier clock;
    private long at;
    private boolean fading;
    // The player the timer was started on; Reset to Defaults replaces it, and the replacement is not the timer's.
    private PlaybackController owner;

    public SleepTimer() {
        this(System::nanoTime);
    }

    SleepTimer(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Pauses after {@code minutes}; 0 cancels. A fade already under way returns to {@code volume}.
     */
    public void start(int minutes, PlaybackController playback, double volume) {
        at = minutes > 0 ? Math.max(1, clock.getAsLong() + TimeUnit.MINUTES.toNanos(minutes)) : 0;
        if (fading && playback != null) playback.volume(volume);
        fading = false;
        owner = playback;
    }

    /**
     * Seconds until the timer pauses playback, or -1 when it is off.
     */
    public long remaining() {
        return at == 0 ? -1 : Math.max(0, TimeUnit.NANOSECONDS.toSeconds(at - clock.getAsLong()));
    }

    public void tick(PlaybackController playback, double volume) {
        if (at == 0) return;
        if (owner == null) owner = playback;
        else if (owner != playback) {
            at = 0;
            fading = false;
            owner = null;
            return;
        }
        long left = at - clock.getAsLong();
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
