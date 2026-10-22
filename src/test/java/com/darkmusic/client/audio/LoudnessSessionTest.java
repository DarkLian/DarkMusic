package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoudnessSessionTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeLoggingBeforeBackgroundWorkers() {
        org.slf4j.LoggerFactory.getLogger(LoudnessSessionTest.class);
    }

    @TempDir
    Path directory;

    private Track song() {
        return new Track("library", "song.opus", directory.resolve("song.opus"), "", "", "", 120, 10, 20, "");
    }

    private String key(Track track) {
        return track.file().toAbsolutePath() + ":10:20:TRACK";
    }

    private AudioSettings settings() {
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.normalization = true;
        return settings;
    }

    @Test
    void lateResultsNeverChangeTheActiveSessionButTheNextSessionUsesThem() {
        ExternalAudio.configure(null);
        var track = song();
        var settings = settings();
        try (var cache = new LoudnessCache()) {
            var active = cache.session(track, settings);
            assertEquals(1, active.gain(settings));
            cache.remember(track, key(track), new LoudnessCache.Reading(-8, -2, LoudnessCache.Source.ANALYSIS));
            assertEquals(1, active.gain(settings), 0);
            assertEquals(LoudnessCache.State.NEXT_SESSION, active.status(settings, 1).state());
            assertFalse(active.status(settings, 1).albumFallback());
            var next = cache.session(track, settings);
            assertEquals(Math.pow(10, -10 / 20.0), next.gain(settings), 1e-12);
            assertEquals(LoudnessCache.State.READY, next.status(settings, next.gain(settings)).state());
        }
    }

    @Test
    void cachedReadingLoadsBeforeTheFirstDecisionAndRemainsPinned() throws Exception {
        var track = song();
        Files.writeString(directory.resolve("loudness-cache.json"), com.darkmusic.client.config.ConfigStore.JSON.toJson(
            Map.of(key(track), new LoudnessCache.Reading(-8, -2, LoudnessCache.Source.ANALYSIS))));
        ExternalAudio.configure(() -> new ExternalAudio.Paths(directory, "", ""));
        var settings = settings();
        try (var cache = new LoudnessCache()) {
            // No executor barrier: the public session API must wait for the disk cache itself.
            var session = cache.session(track, settings);
            assertEquals(Math.pow(10, -.5), session.gain(settings), 1e-12);
            cache.remember(track, key(track), new LoudnessCache.Reading(-20, -3));
            assertEquals(Math.pow(10, -.5), session.gain(settings), 1e-12);
            settings.targetLufs = -20;
            assertEquals(Math.pow(10, -.6), session.gain(settings), 1e-12, "An explicit target edit is still live");
            settings.neutral = true;
            assertEquals(LoudnessCache.Status.OFF, session.status(settings, 1));
            settings.neutral = false;
            assertEquals(Math.pow(10, -.6), session.gain(settings), 1e-12);
        } finally {
            ExternalAudio.configure(null);
        }
    }
}
