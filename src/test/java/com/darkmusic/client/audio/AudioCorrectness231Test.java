package com.darkmusic.client.audio;

import com.darkmusic.client.config.*;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class AudioCorrectness231Test {
    @TempDir
    Path temp;

    @Test
    void profileSelectionKeepsIndependentTempoPreferenceAndOldJsonLoads() {
        var a = new AudioSettings();
        a.enhancements.preservePitch = true;
        for (String name : List.of("DEFAULT", "MUSIC", "GAMING", "VOCAL", "NIGHT")) {
            a.selectProfile(name);
            assertTrue(a.enhancements.preservePitch, name);
            assertTrue(a.playbackCopy().enhancements.preservePitch);
        }
        var old = ConfigStore.JSON.fromJson("{\"audio\":{\"neutral\":true}}", Settings.class);
        old.validate();
        assertFalse(old.audio.enhancements.preservePitch);
    }

    @Test
    void albumFallbackRequiresAUsableReadingAndReportsHeadroom() throws Exception {
        ExternalAudio.configure(null);
        try (var cache = new LoudnessCache()) {
            var executor = LoudnessCache.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((ExecutorService) executor.get(cache)).submit(() -> {
            }).get();
            var a = new AudioSettings();
            a.neutral = false;
            a.normalization = true;
            a.gainMode = AudioSettings.Normalization.ALBUM;
            var t = new Track("test", "absent.opus", temp.resolve("absent.opus"), "", "", "", 1, 1, 1, "");
            String key = t.file().toAbsolutePath() + ":1:1:ALBUM", attempt = key + ":false";
            assertEquals(LoudnessCache.State.NO_MEASUREMENT, cache.status(t, a, 1).state());
            assertFalse(cache.status(t, a, 1).albumFallback());
            var pending = LoudnessCache.class.getDeclaredField("pending");
            pending.setAccessible(true);
            ((Set<String>) pending.get(cache)).add(attempt);
            assertEquals(LoudnessCache.State.PENDING, cache.status(t, a, 1).state());
            assertFalse(cache.status(t, a, 1).albumFallback());
            ((Set<?>) pending.get(cache)).clear();
            var retry = LoudnessCache.class.getDeclaredField("retryAfter");
            retry.setAccessible(true);
            ((Map<String, Long>) retry.get(cache)).put(attempt, Long.MAX_VALUE);
            assertEquals(LoudnessCache.State.FAILED, cache.status(t, a, 1).state());
            assertFalse(cache.status(t, a, 1).albumFallback());
            cache.remember(t, key, new LoudnessCache.Reading(-20, -6, LoudnessCache.Source.LEGACY));
            assertFalse(cache.status(t, a, 1).albumFallback(), "Unknown legacy provenance must not be called track fallback");
            cache.remember(t, key, new LoudnessCache.Reading(-24, -.1, LoudnessCache.Source.TRACK_TAGS));
            assertTrue(cache.status(t, a, 1).albumFallback());
            assertTrue(cache.status(t, a, 1).peakLimited());
            cache.remember(t, key, new LoudnessCache.Reading(-20, -6, LoudnessCache.Source.ALBUM_TAGS));
            assertFalse(cache.status(t, a, 1).albumFallback());
            assertFalse(cache.status(t, a, 1).peakLimited());
            cache.remember(t, key, new LoudnessCache.Reading(-20, -6, LoudnessCache.Source.ANALYSIS));
            assertTrue(cache.status(t, a, 1).albumFallback());
            assertEquals(LoudnessCache.Source.ANALYSIS, cache.status(t, a, 1).source());
            a.neutral = true;
            assertEquals(LoudnessCache.Status.OFF, cache.status(t, a, 1));
        }
    }
}
