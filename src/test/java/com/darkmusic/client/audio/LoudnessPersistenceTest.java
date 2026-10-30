package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class LoudnessPersistenceTest {
    @TempDir
    Path temp;

    private Track track(String name, long modified) {
        return new Track("test", name, temp.resolve(name), name, "", "", 5, 1234, modified, "");
    }

    private String key(Track t) {
        return t.file().toAbsolutePath() + ":" + t.size() + ":" + t.modified() + ":TRACK";
    }

    @Test
    void unreadableExistingCacheIsNotOverwrittenByNewResults() throws Exception {
        Path file = temp.resolve("loudness-cache.json");
        String original = "{incomplete cache";
        Files.writeString(file, original);
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        try (var cache = new LoudnessCache()) {
            var executor = LoudnessCache.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((ExecutorService) executor.get(cache)).submit(() -> {
            }).get();
            var t = track("new.opus", 1);
            cache.remember(t, key(t), new LoudnessCache.Reading(-20, -3));
        } finally {
            ExternalAudio.configure(null);
        }
        assertEquals(original, Files.readString(file));
    }

    private static void loaded(LoudnessCache cache) throws Exception {
        var executor = LoudnessCache.class.getDeclaredField("executor");
        executor.setAccessible(true);
        ((ExecutorService) executor.get(cache)).submit(() -> {
        }).get();
    }

    private static void flush(LoudnessCache cache) throws Exception {
        var flush = LoudnessCache.class.getDeclaredMethod("flush");
        flush.setAccessible(true);
        flush.invoke(cache);
    }

    @Test
    void aReplacedCacheClosingLateKeepsWhatItsReplacementSaved() throws Exception {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        try {
            var old = new LoudnessCache();
            loaded(old);
            var a = track("a.opus", 1);
            old.remember(a, key(a), new LoudnessCache.Reading(-20, -3));
            try (var replacement = new LoudnessCache()) {
                loaded(replacement);
                var b = track("b.opus", 1);
                replacement.remember(b, key(b), new LoudnessCache.Reading(-21, -4));
                flush(replacement);
                // The old instance's final write lands after its replacement saved.
                old.close();
                var json = JsonParser.parseString(Files.readString(temp.resolve("loudness-cache.json"))).getAsJsonObject();
                assertTrue(json.has(key(a)));
                assertTrue(json.has(key(b)), "A late write must not drop newer readings");
                var changed = track("a.opus", 2);
                replacement.remember(changed, key(changed), new LoudnessCache.Reading(-22, -5));
            }
            var json = JsonParser.parseString(Files.readString(temp.resolve("loudness-cache.json"))).getAsJsonObject();
            assertEquals(Set.of(key(track("a.opus", 2)), key(track("b.opus", 1))), json.keySet(), "A measured file keeps only its current version");
        } finally {
            ExternalAudio.configure(null);
        }
    }

    // A placeholder with the size and modification time a track records; the cache never reads its contents.
    private Track existing(String name, long modified) throws Exception {
        Path file = Files.write(temp.resolve(name), new byte[1234]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return track(name, modified);
    }

    private JsonObject saved() throws Exception {
        return JsonParser.parseString(Files.readString(temp.resolve("loudness-cache.json"))).getAsJsonObject();
    }

    @Test
    void aLateWriteOfAnOlderVersionDoesNotReplaceTheNewerOne() throws Exception {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        try {
            var old = new LoudnessCache();
            loaded(old);
            var before = track("a.dat", 1000);
            old.remember(before, key(before), new LoudnessCache.Reading(-20, -3));
            var after = existing("a.dat", 2000);
            try (var replacement = new LoudnessCache()) {
                loaded(replacement);
                replacement.remember(after, key(after), new LoudnessCache.Reading(-21, -4));
                flush(replacement);
                old.close();
                assertEquals(Set.of(key(after)), saved().keySet());
            }
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    void savingAnotherSongDoesNotBringBackAReplacedVersion() throws Exception {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        try {
            var before = track("a.dat", 1000);
            try (var first = new LoudnessCache()) {
                loaded(first);
                first.remember(before, key(before), new LoudnessCache.Reading(-20, -3));
            }
            var loadedBefore = new LoudnessCache();
            loaded(loadedBefore);
            var after = existing("a.dat", 2000);
            try (var replacement = new LoudnessCache()) {
                loaded(replacement);
                replacement.remember(after, key(after), new LoudnessCache.Reading(-21, -4));
                flush(replacement);
            }
            var other = track("b.opus", 1);
            loadedBefore.remember(other, key(other), new LoudnessCache.Reading(-19, -2));
            loadedBefore.close();
            assertEquals(Set.of(key(after), key(other)), saved().keySet());
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    void theFileOnDiskDecidesBetweenVersionsAndTheNewestWinsWhenItCannot() throws Exception {
        var restored = existing("restored.dat", 1000);
        var newer = track("restored.dat", 2000);
        var offlineOld = track("offline.opus", 1000);
        var offlineNew = track("offline.opus", 2000);
        var readings = new HashMap<String, LoudnessCache.Reading>();
        for (var t : List.of(restored, newer, offlineOld, offlineNew))
            readings.put(key(t), new LoudnessCache.Reading(-20, -3));
        LoudnessCache.latestVersions(readings);
        assertEquals(Set.of(key(restored), key(offlineNew)), readings.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryWaitsBeyondTheLimitDropTheSoonestFirst() throws Exception {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        try {
            var cache = new LoudnessCache(() -> 0L);
            loaded(cache);
            cache.close();
            var field = LoudnessCache.class.getDeclaredField("retryAfter");
            field.setAccessible(true);
            var waits = (Map<String, Long>) field.get(cache);
            waits.put("expired", -1L);
            for (int i = 1; i <= 4097; i++) waits.put("attempt-" + i, (long) i);
            cache.gain(track("song.opus", 1), new AudioSettings());
            assertEquals(4096, waits.size());
            assertFalse(waits.containsKey("expired"));
            assertFalse(waits.containsKey("attempt-1"));
            assertTrue(waits.containsKey("attempt-2"));
            assertTrue(waits.containsKey("attempt-4097"));
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    void aCacheFileThatBecomesUnreadableIsNotOverwritten() throws Exception {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        Path file = temp.resolve("loudness-cache.json");
        var t = track("song.opus", 1);
        try {
            for (String broken : List.of("{\"" + key(t).replace("\\", "\\\\") + "\":", "{broken", "[]", "{} trailing")) {
                Files.deleteIfExists(file);
                try (var cache = new LoudnessCache()) {
                    loaded(cache);
                    cache.remember(t, key(t), new LoudnessCache.Reading(-20, -3));
                    Files.writeString(file, broken);
                    flush(cache);
                }
                assertEquals(broken, Files.readString(file));
            }
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    void cachesLargerThanTheOldReadLimitLoadWithoutReanalysis() throws Exception {
        var json = new JsonObject();
        Track selected = null;
        for (int i = 0; i < 24000; i++) {
            var t = track("collection-" + "a".repeat(160) + "-" + i + ".opus", 1);
            var reading = new JsonObject();
            reading.addProperty("lufs", -20);
            reading.addProperty("peakDb", -3);
            json.add(key(t), reading);
            selected = t;
        }
        Path file = temp.resolve("loudness-cache.json");
        Files.writeString(file, json.toString());
        assertTrue(Files.size(file) > 4_000_000);
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        try (var cache = new LoudnessCache()) {
            var executor = LoudnessCache.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((ExecutorService) executor.get(cache)).submit(() -> {
            }).get();
            assertEquals(Math.pow(10, 2 / 20.0), cache.gain(selected, new AudioSettings()), 1e-12);
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    void burstResultsFlushOnCloseAndReloadWithoutLosingOfflineFiles() throws Exception {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        var settings = new AudioSettings();
        try {
            try (var cache = new LoudnessCache()) {
                // Wait for initial loading, as normal inspections do on its single worker.
                var executor = LoudnessCache.class.getDeclaredField("executor");
                executor.setAccessible(true);
                ((ExecutorService) executor.get(cache)).submit(() -> {
                }).get();
                for (int i = 0; i < 1000; i++) {
                    var t = track("song-" + i + ".opus", 1);
                    cache.remember(t, key(t), new LoudnessCache.Reading(-20, -3));
                }
            }
            var file = temp.resolve("loudness-cache.json");
            assertEquals(1000, JsonParser.parseString(Files.readString(file)).getAsJsonObject().size());
            try (var cache = new LoudnessCache()) {
                var executor = LoudnessCache.class.getDeclaredField("executor");
                executor.setAccessible(true);
                ((ExecutorService) executor.get(cache)).submit(() -> {
                }).get();
                var original = track("song-0.opus", 1);
                assertEquals(Math.pow(10, 2 / 20.0), cache.gain(original, settings), 1e-12);
                var changed = track("song-0.opus", 2);
                cache.remember(changed, key(changed), new LoudnessCache.Reading(-21, -4));
                cache.close();
                cache.remember(original, key(original), new LoudnessCache.Reading(-50, -20));
            }
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals(1000, json.size());
            assertFalse(json.has(key(track("song-0.opus", 1))));
            assertTrue(json.has(key(track("song-999.opus", 1))), "Missing/offline media must keep its reading");
            assertTrue(json.has(key(track("song-0.opus", 2))));
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    void failedAtomicWriteRetainsDirtyResultsForRetry() throws Exception {
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, "", ""));
        try (var cache = new LoudnessCache()) {
            var executor = LoudnessCache.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((ExecutorService) executor.get(cache)).submit(() -> {
            }).get();
            var t = track("real-reference.opus", 1);
            cache.remember(t, key(t), new LoudnessCache.Reading(-20, -3));
            Path file = Files.createDirectory(temp.resolve("loudness-cache.json"));
            var flush = LoudnessCache.class.getDeclaredMethod("flush");
            flush.setAccessible(true);
            flush.invoke(cache);
            assertTrue(Files.isDirectory(file));
            Files.delete(file);
            flush.invoke(cache);
            assertTrue(JsonParser.parseString(Files.readString(file)).getAsJsonObject().has(key(t)));
        } finally {
            ExternalAudio.configure(null);
        }
    }
}
