package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoudnessRetryTest {
    @TempDir
    Path temp;

    @Test
    void unavailableToolsRecoverAfterBackoffWithoutRecreatingCacheOrHammeringEveryBuffer() throws Exception {
        String configured = System.getProperty("darkmusic.test.ffmpeg", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(configured.isBlank(), "Real FFmpeg integration is opt-in");
        Path ff = Path.of(configured), source;
        try (var files = Files.walk(Path.of(System.getProperty("darkmusic.test.music")))) {
            source = files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".mp3")).findFirst().orElseThrow();
        }
        Path tagged = temp.resolve("tagged.flac");
        var converted = new ProcessRunner().run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-t", "2", "-vn", "-c:a", "flac", "-metadata", "replaygain_track_gain=3 dB", "-metadata", "replaygain_track_peak=0.2", tagged.toString()), temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 16000, s -> {
        });
        assertEquals(0, converted.exit(), converted.output());
        var track = new Track("test", "tagged.flac", tagged, "Private excerpt", "", "", 2, Files.size(tagged), Files.getLastModifiedTime(tagged).toMillis(), "");
        var clock = new AtomicLong();
        ExternalAudio.configure(null);
        try (var cache = new LoudnessCache(clock::get)) {
            var settings = new AudioSettings();
            assertEquals(1, cache.gain(track, settings));
            var retryField = LoudnessCache.class.getDeclaredField("retryAfter");
            retryField.setAccessible(true);
            var retries = (java.util.Map<?, ?>) retryField.get(cache);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (retries.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, retries.size());
            ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, ff.toString(), ff.resolveSibling(ff.getFileName().toString().replace("ffmpeg", "ffprobe")).toString()));
            for (int i = 0; i < 100; i++) assertEquals(1, cache.gain(track, settings));
            clock.set(31_000_000_000L);
            double gain = 1;
            deadline = System.nanoTime() + 5_000_000_000L;
            while (gain == 1 && System.nanoTime() < deadline) {
                gain = cache.gain(track, settings);
                Thread.sleep(10);
            }
            assertEquals(Math.pow(10, 3 / 20.0), gain, .01);
            assertTrue(retries.isEmpty());
        } finally {
            ExternalAudio.configure(null);
        }
    }
}
