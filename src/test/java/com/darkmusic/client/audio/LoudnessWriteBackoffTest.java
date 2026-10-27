package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        org.slf4j.LoggerFactory.getLogger(getClass());
        var track = new Track("root", "unavailable.opus", directory.resolve("unavailable.opus"), "", "", "", 10, 1, 1, "");
        try (var cache = new LoudnessCache()) {
            cache.remember(track, track.file().toAbsolutePath() + ":1:1:TRACK", new LoudnessCache.Reading(-12, -1));
            var flush = LoudnessCache.class.getDeclaredMethod("flush");
            flush.setAccessible(true);
            flush.invoke(cache);
            var next = LoudnessCache.class.getDeclaredField("nextFlush");
            next.setAccessible(true);
            assertTrue(next.getLong(cache) - System.nanoTime() > TimeUnit.SECONDS.toNanos(50));
            var results = LoudnessCache.class.getDeclaredField("results");
            results.setAccessible(true);
            assertEquals(1, ((java.util.Map<?, ?>) results.get(cache)).size());
        } finally {
            ExternalAudio.configure(null);
        }
    }
}
