package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoudnessWriteBackoffTest {
    @TempDir
    Path directory;

    @Test
    void failedPersistenceDefersTheNextBackgroundAttemptAndKeepsTheReading() throws Exception {
        Files.createDirectory(directory.resolve("loudness-cache.json"));
        ExternalAudio.configure(() -> new ExternalAudio.Paths(directory, "", ""));
        LoggerFactory.getLogger(getClass());
        var track = new Track("root", "unavailable.opus", directory.resolve("unavailable.opus"), "", "", "", 10, 1, 1, "");
        try (var cache = new LoudnessCache()) {
            cache.remember(track, track.file().toAbsolutePath() + ":1:1:TRACK", new LoudnessCache.Reading(-12, -1));
            cache.store().flush();
            assertTrue(cache.store().nextFlush() - System.nanoTime() > TimeUnit.SECONDS.toNanos(50));
            assertEquals(1, cache.store().readings().size());
        } finally {
            ExternalAudio.configure(null);
        }
    }
}
