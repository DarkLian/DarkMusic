package com.darkmusic.client.cleanup;

import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReviewPerformanceTest {
    @TempDir
    Path temp;

    @Test
    void sequentialCacheCheckpointChangesOnlyTheTailShard() throws Exception {
        var cache = new DuplicateCache(temp);
        int[] fingerprint = new int[400];
        Arrays.fill(fingerprint, 123);
        for (int i = 0; i < 5000; i++) cache.put("id" + i, "stamp" + i, "", "", fingerprint);
        cache.flush();
        Path folder = temp.resolve("cleanup-cache/v2");
        var old = java.nio.file.attribute.FileTime.fromMillis(1_000_000);
        try (var files = Files.list(folder)) {
            for (Path file : files.toList()) Files.setLastModifiedTime(file, old);
        }
        for (int i = 5000; i < 5016; i++) cache.put("id" + i, "stamp" + i, "", "", fingerprint);
        cache.flush();
        try (var files = Files.list(folder)) {
            assertEquals(1, files.filter(p -> {
                try {
                    return !Files.getLastModifiedTime(p).equals(old);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).count());
        }
        var loaded = new DuplicateCache(temp);
        loaded.load();
        assertArrayEquals(fingerprint, loaded.get("id5015", "stamp5015").values());
    }

    @Test
    void incrementalAudioDiscoveryDoesNotRepeatTheHundredThousandTrackPass() {
        long started = System.nanoTime();
        var session = new DuplicateMatcher.Session(DuplicatePolicy.BALANCED, new ProcessRunner.Token());
        var entries = new ArrayList<DuplicateMatcher.Entry>();
        for (int i = 0; i < 100_000; i++) {
            String id = "unique" + i;
            var t = new Track("root", id, Path.of(id), id, "", "", 180, 1, 1, "");
            var e = new DuplicateMatcher.Entry(t, id, "", "", new int[0]);
            entries.add(e);
            session.add(e);
        }
        long before = session.snapshot().comparisons();
        int[] fingerprint = new Random(71).ints(400).toArray();
        for (int i : new int[]{0, 99_999}) {
            var e = entries.get(i);
            session.update(i, new DuplicateMatcher.Entry(e.track(), e.stamp(), "", "", fingerprint));
        }
        var result = session.snapshot();
        assertEquals(1, result.comparisons() - before);
        assertEquals(1, result.pairs().size());
        assertTrue(result.pairs().getFirst().evidence().contains(DuplicateResult.Evidence.AUDIO));
        System.out.printf("100k indexed entries plus two fingerprint updates: %.3f s, %d comparisons%n", (System.nanoTime() - started) / 1e9, result.comparisons());
    }
}
