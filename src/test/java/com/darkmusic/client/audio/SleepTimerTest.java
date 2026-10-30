package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SleepTimerTest {
    private final long[] now = {0};

    private static PlaybackController playing(UpNextTest.Fake backend) {
        var playback = new PlaybackController(backend, message -> fail(message));
        var song = UpNextTest.track("r", "a.mp3");
        playback.play(List.of(song), song);
        backend.volume = -1;
        return playback;
    }

    private void at(long seconds) {
        now[0] = TimeUnit.SECONDS.toNanos(seconds);
    }

    @Test
    void fadesOverTheLastThirtySecondsThenPausesAndRestoresTheVolume() {
        var backend = new UpNextTest.Fake();
        var playback = playing(backend);
        var timer = new SleepTimer(() -> now[0]);
        timer.start(1, playback, .8);
        at(45);
        timer.tick(playback, .8);
        assertEquals(.8 * 15 / 30, backend.volume, 1e-9);
        at(61);
        timer.tick(playback, .8);
        assertEquals(AudioBackend.Status.PAUSED, backend.state.status());
        assertEquals(.8, backend.volume);
        assertEquals(-1, timer.remaining());
    }

    @Test
    void aTimerNeverFadesOrPausesThePlayerThatReplacedItsOwn() {
        var oldBackend = new UpNextTest.Fake();
        var old = playing(oldBackend);
        var timer = new SleepTimer(() -> now[0]);
        timer.start(1, old, .8);
        at(45);
        timer.tick(old, .8);
        // Reset to Defaults installs a new player while the old one was fading.
        var backend = new UpNextTest.Fake();
        var replacement = playing(backend);
        for (long seconds : new long[]{50, 61, 120}) {
            at(seconds);
            timer.tick(replacement, .8);
        }
        assertEquals(-1, backend.volume, "The replacement's volume is untouched");
        assertEquals(AudioBackend.Status.PLAYING, backend.state.status());
        assertEquals(-1, timer.remaining(), "The timer ended with its player");
    }

    @Test
    void cancellingWithoutAPlayerLeavesTheOldPlayerAsItWas() {
        var oldBackend = new UpNextTest.Fake();
        var old = playing(oldBackend);
        var timer = new SleepTimer(() -> now[0]);
        timer.start(1, old, .8);
        at(45);
        timer.tick(old, .8);
        double faded = oldBackend.volume;
        // What reset does before closing the old player.
        timer.start(0, null, 0);
        assertEquals(-1, timer.remaining());
        assertEquals(faded, oldBackend.volume);
        var backend = new UpNextTest.Fake();
        var replacement = playing(backend);
        at(61);
        timer.tick(replacement, .8);
        assertEquals(-1, backend.volume);
        assertEquals(AudioBackend.Status.PLAYING, backend.state.status());
    }
}
