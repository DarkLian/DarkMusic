package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ParallelLoudnessTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void logging() {
        LoggerFactory.getLogger(ParallelLoudnessTest.class);
    }

    private AudioSettings settings(boolean parallel) {
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.normalization = settings.analyzeLoudness = true;
        settings.parallelLoudnessAnalysis = parallel;
        return settings;
    }

    private ThreadPoolExecutor executor(LoudnessCache cache) throws Exception {
        var field = LoudnessCache.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(cache);
    }

    @Test
    void parallelModeDefaultsOffAndSurvivesSnapshotsAndPersistence() {
        var settings = new AudioSettings();
        assertFalse(settings.parallelLoudnessAnalysis);
        assertFalse(ConfigStore.JSON.fromJson("{}", AudioSettings.class).parallelLoudnessAnalysis);
        var original = settings.playbackCopy();
        settings.parallelLoudnessAnalysis = true;
        assertFalse(settings.samePlayback(original));
        assertTrue(settings.playbackCopy().parallelLoudnessAnalysis);
        assertTrue(ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), AudioSettings.class).parallelLoudnessAnalysis);
    }

    @Test
    void workerCountIsBoundedAndCanReturnToSerialMode() throws Exception {
        ExternalAudio.configure(null);
        try (var cache = new LoudnessCache()) {
            cache.parallel(true);
            assertEquals(Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 3), executor(cache).getCorePoolSize());
            assertTrue(executor(cache).getMaximumPoolSize() <= 3);
            cache.parallel(false);
            assertEquals(1, executor(cache).getCorePoolSize());
            assertEquals(1, executor(cache).getMaximumPoolSize());
            assertEquals(2, executor(cache).getQueue().remainingCapacity());
        }
    }

    private List<Track> music() throws Exception {
        String ffmpeg = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeTrue(!ffmpeg.isBlank() && Files.isRegularFile(Path.of(ffmpeg)), "Real FFmpeg tools required");
        Path root = Path.of(System.getProperty("darkmusic.test.music", "run/config/darkmusic/music"));
        List<Path> files = List.of();
        if (Files.isDirectory(root)) try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile).filter(p -> p.toString().matches("(?i).*\\.(opus|mp3|flac|wav|ogg|m4a)$")).sorted().limit(3).toList();
        }
        if (Boolean.getBoolean("darkmusic.test.requireMusic"))
            assertEquals(3, files.size(), "Three real collection songs required");
        Assumptions.assumeTrue(files.size() == 3, "Private music unavailable");
        var tracks = new ArrayList<Track>();
        for (Path file : files)
            tracks.add(new Track("real", file.getFileName().toString(), file.toAbsolutePath(), "", "", "", 120, Files.size(file), Files.getLastModifiedTime(file).toMillis(), ""));
        return tracks;
    }

    private void configure(Path data) {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(data, System.getProperty("darkmusic.test.ffmpeg"), System.getProperty("darkmusic.test.ffprobe")));
    }

    private int awaitBatch(LoudnessCache cache) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
        int peak = 0;
        while (cache.analysisProgress().running() && System.nanoTime() < deadline) {
            peak = Math.max(peak, executor(cache).getActiveCount());
            Thread.sleep(10);
        }
        assertFalse(cache.analysisProgress().running(), "Library analysis did not finish");
        return peak;
    }

    @Test
    @Timeout(120)
    void realParallelAnalysisMatchesSerialMeasurementsPinsSessionsAndReusesDiskCache() throws Exception {
        var tracks = music();
        double[] gains = new double[tracks.size()];
        try {
            for (boolean parallel : new boolean[]{false, true}) {
                Path data = directory.resolve(parallel ? "parallel" : "serial");
                configure(data);
                var settings = settings(parallel);
                long start = System.nanoTime();
                try (var cache = new LoudnessCache()) {
                    var active = cache.session(tracks.getFirst(), settings);
                    assertEquals(1, active.gain(settings), 0);
                    cache.analyzeLibrary(tracks, settings);
                    int peak = awaitBatch(cache);
                    assertEquals(3, cache.analysisProgress().processed());
                    assertEquals(3, cache.analysisProgress().available());
                    assertTrue(peak <= (parallel ? 3 : 1));
                    if (parallel && Runtime.getRuntime().availableProcessors() >= 4)
                        assertTrue(peak >= 2, "No parallel work observed");
                    assertEquals(1, active.gain(settings), 0, "Background results changed the audible session");
                    for (int i = 0; i < tracks.size(); i++) {
                        double gain = cache.session(tracks.get(i), settings).gain(settings);
                        assertTrue(Double.isFinite(gain) && gain > 0);
                        if (parallel) assertEquals(gains[i], gain, 1e-12, "Concurrency changed the measurement");
                        else gains[i] = gain;
                    }
                    System.out.printf(Locale.ROOT, "LOUDNESS_BATCH %s: %.3fs, peak workers %d%n", parallel ? "parallel" : "serial", (System.nanoTime() - start) / 1e9, peak);
                }
                try (var reloaded = new LoudnessCache()) {
                    assertEquals(gains[0], reloaded.session(tracks.getFirst(), settings).gain(settings), 1e-12);
                    reloaded.analyzeLibrary(tracks, settings);
                    awaitBatch(reloaded);
                    assertEquals(3, reloaded.analysisProgress().available());
                }
            }
            for (Track track : tracks) {
                assertEquals(track.size(), Files.size(track.file()));
                assertEquals(track.modified(), Files.getLastModifiedTime(track.file()).toMillis());
            }
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    @Timeout(20)
    void cancellingARealBatchReleasesEveryJobWithoutRecordingFailures() throws Exception {
        var tracks = music();
        configure(directory);
        try (var cache = new LoudnessCache()) {
            cache.analyzeLibrary(tracks, settings(true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor(cache).getActiveCount() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            cache.cancelAnalysis();
            awaitBatch(cache);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor(cache).getActiveCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, executor(cache).getActiveCount());
            assertTrue(executor(cache).getQueue().isEmpty());
            var retry = LoudnessCache.class.getDeclaredField("retryAfter");
            retry.setAccessible(true);
            assertTrue(((Map<?, ?>) retry.get(cache)).isEmpty(), "Cancellation was reported as analysis failure");
        } finally {
            ExternalAudio.configure(null);
        }
    }
}
