package com.darkmusic.client.audio;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumePlaybackTest {
    @Test
    void playCanStartASongPartWayThroughOptionallyPausedInItsOriginalCollection() {
        var backend = new UpNextTest.Fake();
        var playback = new PlaybackController(backend, message -> fail(message));
        var a = UpNextTest.track("r", "a.mp3");
        var b = UpNextTest.track("r", "b.mp3");
        var source = new PlaybackController.Source("Playlists", "Road Trip", "", "");
        playback.play(List.of(a, b), b, source, 42.5, true);
        assertEquals(b, backend.state.track());
        assertEquals(42.5, backend.state.position());
        assertEquals(AudioBackend.Status.PAUSED, backend.state.status());
        assertEquals(source, playback.source());
        playback.play(List.of(a, b), a, source, Double.NaN, false);
        assertEquals(0, backend.state.position(), "An unreadable position starts from the beginning");
        assertEquals(AudioBackend.Status.PLAYING, backend.state.status());
    }

    @Test
    void stopAfterThisSongStopsAtItsEndPreloadsNothingAndClearsWhenStopped() {
        var backend = new UpNextTest.Fake();
        var playback = new PlaybackController(backend, message -> fail(message));
        var a = UpNextTest.track("r", "a.mp3");
        var b = UpNextTest.track("r", "b.mp3");
        playback.play(List.of(a, b), a);
        assertNotNull(backend.prepared, "The next song is normally preloaded for gapless playback");
        playback.stopAfterCurrent(true);
        assertNull(backend.prepared, "Nothing may start early through gapless playback or crossfade");
        backend.ended.run();
        assertEquals(AudioBackend.Status.STOPPED, backend.state.status());
        assertEquals(1, backend.opens, "The next song never started");
        assertFalse(playback.stopAfterCurrent(), "It applies once");
        playback.play(List.of(a, b), a);
        playback.stopAfterCurrent(true);
        playback.stop();
        assertFalse(playback.stopAfterCurrent(), "Stopping by hand clears it");
    }

    @Test
    void upcomingIsUpNextFirstThenTheCollectionAndNothingWhenStopping() {
        var backend = new UpNextTest.Fake();
        var playback = new PlaybackController(backend, message -> fail(message));
        var a = UpNextTest.track("r", "a.mp3");
        var b = UpNextTest.track("r", "b.mp3");
        var c = UpNextTest.track("r", "c.mp3");
        playback.play(List.of(a, b), a);
        assertEquals(b, playback.upcoming());
        playback.addToQueue(c);
        assertEquals(c, playback.upcoming());
        playback.repeat(com.darkmusic.client.config.Repeat.ONE);
        assertEquals(a, playback.upcoming());
        playback.repeat(com.darkmusic.client.config.Repeat.OFF);
        playback.stopAfterCurrent(true);
        assertNull(playback.upcoming());
    }

    @Test
    void savedResumePointsSurviveReloadAndDamagedOnesAreDropped() {
        var settings = new Settings();
        settings.resume = new Settings.Resume("All", "@root/Albums", "root:Albums/a.mp3", 83.25);
        var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        loaded.validate();
        assertTrue(loaded.resumeLast);
        assertEquals("root:Albums/a.mp3", loaded.resume.track);
        assertEquals(83.25, loaded.resume.position);
        for (String damaged : List.of("{\"resume\":{\"mode\":\"All\",\"group\":\"\",\"track\":\"\",\"position\":1}}",
            "{\"resume\":{\"mode\":\"All\",\"group\":\"\",\"track\":\"r:a\",\"position\":-1}}",
            "{\"resume\":{\"mode\":null,\"group\":\"\",\"track\":\"r:a\",\"position\":1}}")) {
            var bad = ConfigStore.JSON.fromJson(damaged, Settings.class);
            bad.validate();
            assertNull(bad.resume, damaged);
        }
        var older = ConfigStore.JSON.fromJson("{}", Settings.class);
        older.validate();
        assertTrue(older.resumeLast, "On by default, including for settings saved by older versions");
        assertNull(older.resume);
    }
}
