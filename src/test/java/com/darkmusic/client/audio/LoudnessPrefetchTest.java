package com.darkmusic.client.audio;

import com.darkmusic.client.config.*;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LoudnessPrefetchTest {
    @TempDir
    Path directory;

    private Track track(String name) {
        return new Track("library", name, directory.resolve(name), "", "", "", 120, 10, 20, "");
    }

    private AudioSettings settings() {
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.normalization = true;
        return settings;
    }

    private ThreadPoolExecutor executor(LoudnessCache cache) throws Exception {
        var field = LoudnessCache.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(cache);
    }

    private String queuedTrack(Runnable task) throws Exception {
        var field = task.getClass().getDeclaredField("lookup");
        field.setAccessible(true);
        Object lookup = field.get(task);
        var accessor = lookup.getClass().getDeclaredMethod("track");
        accessor.setAccessible(true);
        return ((Track) accessor.invoke(lookup)).relativePath();
    }

    @Test
    void upcomingRequestsReturnWithoutWaitingAndPrioritizeTheBoundedQueue() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass());
        ExternalAudio.configure(null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var cache = new LoudnessCache()) {
            var executor = executor(cache);
            executor.submit(() -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var settings = settings();
            cache.gain(track("older"), settings);
            cache.gain(track("other"), settings);
            assertEquals(2, executor.getQueue().size());
            try (var caller = Executors.newSingleThreadExecutor()) {
                caller.submit(() -> cache.prefetch(track("upcoming"), settings)).get(1, TimeUnit.SECONDS);
            }
            var queued = new ArrayList<>(executor.getQueue());
            assertEquals(2, queued.size(), "Upcoming work must not grow the bounded queue");
            assertEquals("upcoming", queuedTrack(queued.getFirst()));
            cache.prefetch(track("upcoming"), settings);
            assertEquals(2, executor.getQueue().size(), "Repeated notification must not duplicate analysis");
            var pending = LoudnessCache.class.getDeclaredField("pending");
            pending.setAccessible(true);
            assertEquals(2, ((Set<?>) pending.get(cache)).size(), "Eviction must release the displaced pending marker");
            settings.neutral = true;
            cache.prefetch(track("disabled"), settings);
            assertEquals(2, executor.getQueue().size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aPrefetchDuringDiskLoadingDoesNotProbeAnAlreadyCachedTrack() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass());
        var track = track("cached");
        String key = track.file().toAbsolutePath() + ":10:20:TRACK";
        Files.writeString(directory.resolve("loudness-cache.json"), ConfigStore.JSON.toJson(
            Map.of(key, Map.of("lufs", -8, "peakDb", -3, "source", "ANALYSIS"))));
        ExternalAudio.configure(() -> new ExternalAudio.Paths(directory, "", ""));
        try (var cache = new LoudnessCache()) {
            cache.prefetch(track, settings());
            executor(cache).submit(() -> {
            }).get(5, TimeUnit.SECONDS);
            assertEquals(Math.pow(10, -.5), cache.session(track, settings()).gain(settings()), 1e-12);
            var retry = LoudnessCache.class.getDeclaredField("retryAfter");
            retry.setAccessible(true);
            assertTrue(((Map<?, ?>) retry.get(cache)).isEmpty(), "No external tools are available, so an unwanted probe would fail");
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    void malformedTrailingCacheDataIsPreservedAndNeverUsedForTheFirstSample() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass());
        var track = track("cached");
        String key = track.file().toAbsolutePath() + ":10:20:TRACK";
        Path file = directory.resolve("loudness-cache.json");
        String original = ConfigStore.JSON.toJson(Map.of(key, Map.of("lufs", -8, "peakDb", -3))) + " invalid trailing data";
        Files.writeString(file, original);
        ExternalAudio.configure(() -> new ExternalAudio.Paths(directory, "", ""));
        try (var cache = new LoudnessCache()) {
            assertEquals(1, cache.session(track, settings()).gain(settings()), 0);
            cache.remember(track, key, new LoudnessCache.Reading(-8, -3));
        } finally {
            ExternalAudio.configure(null);
        }
        assertEquals(original, Files.readString(file));
    }
}