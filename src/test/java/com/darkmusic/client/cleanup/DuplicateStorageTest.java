package com.darkmusic.client.cleanup;

import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateStorageTest {
    @TempDir
    Path temp;

    @Test
    void disablingAudioComparisonPreservesButDoesNotUseCachedFingerprints() throws Exception {
        var a = file("alpha-reference", "One disposable document");
        var b = file("beta-document", "Another document with a different size");
        var cache = new DuplicateCache(temp.resolve("data"));
        int[] fingerprint = new Random(23).ints(160).toArray();
        for (var t : List.of(a, b))
            cache.put(t.id(), DuplicateScanner.stamp(t, DuplicateScanner.attributes(t)), "", "", fingerprint);
        cache.flush();
        try (var scanner = new DuplicateScanner(temp.resolve("data"))) {
            scanner.start(List.of(a, b), DuplicatePolicy.STRICT, false);
            scanner.settled().get(10, TimeUnit.SECONDS);
            assertTrue(scanner.snapshot().pairs().isEmpty());
            scanner.start(List.of(a, b), DuplicatePolicy.STRICT, true);
            scanner.settled().get(10, TimeUnit.SECONDS);
            assertEquals(1, scanner.snapshot().pairs().size());
            assertTrue(scanner.snapshot().pairs().getFirst().evidence().contains(DuplicateResult.Evidence.AUDIO));
            assertEquals(0, scanner.snapshot().audioFailed());
        }
    }

    @Test
    void sharedArchiveIdentityFindsDifferentTitlesFromOtherExtractors() throws Exception {
        var a = file("alpha-reference", "First disposable document");
        var b = file("beta-document", "Second unrelated-size disposable document");
        Files.writeString(temp.resolve(".darkmusic-downloads.json"), "{\"SoundCloud:12345:original\":\"alpha-reference\",\"soundcloud:12345\":\"beta-document\"}");
        try (var scanner = new DuplicateScanner(temp.resolve("data"))) {
            scanner.start(List.of(a, b), DuplicatePolicy.STRICT, false);
            scanner.settled().get(10, TimeUnit.SECONDS);
            assertEquals(1, scanner.snapshot().pairs().size());
            assertEquals(Set.of(DuplicateResult.Evidence.SOURCE), scanner.snapshot().pairs().getFirst().evidence());
        }
    }

    Track file(String name, String contents) throws Exception {
        Path p = Files.writeString(temp.resolve(name), contents);
        return new Track("r", name, p, "Title " + name, "Artist", "", 180, Files.size(p), Files.getLastModifiedTime(p).toMillis(), "");
    }

    @Test
    void exactBytesCacheInvalidationCorruptionAndKnownDeletion() throws Exception {
        var a = file("a.txt", "Disposable byte-hash fixture");
        var b = file("b.txt", "Disposable byte-hash fixture");
        try (var scanner = new DuplicateScanner(temp.resolve("data"))) {
            assertTrue(scanner.start(List.of(a, b), DuplicatePolicy.STRICT, false));
            scanner.settled().get(10, TimeUnit.SECONDS);
            assertEquals(1, scanner.snapshot().pairs().size());
            assertTrue(scanner.snapshot().pairs().getFirst().evidence().contains(DuplicateResult.Evidence.EXACT));
            scanner.deleted(Set.of(a.id()));
            assertTrue(scanner.snapshot().pairs().isEmpty());
        }
        String stamp = DuplicateScanner.stamp(a, DuplicateScanner.attributes(a));
        var cache = new DuplicateCache(temp.resolve("data"));
        cache.load();
        assertNotNull(cache.get(a.id(), stamp));
        assertNull(cache.get(a.id(), "changed"));
        assertArrayEquals(new int[0], cache.get(a.id(), stamp).values());
        cache.put(a.id(), stamp, "", "", new int[]{1, -2, 3});
        cache.flush();
        var loaded = new DuplicateCache(temp.resolve("data"));
        loaded.load();
        assertArrayEquals(new int[]{1, -2, 3}, loaded.get(a.id(), stamp).values());
        Files.writeString(temp.resolve("data/cleanup-cache/cache-" + (a.id().hashCode() & 15) + ".json"), "not json");
        var damaged = new DuplicateCache(temp.resolve("data"));
        assertDoesNotThrow(damaged::load);
        assertNull(damaged.get(a.id(), stamp));
        var changed = file("a.txt", "Changed disposable fixture is longer");
        assertThrows(Exception.class, () -> DuplicateScanner.attributes(a));
        assertNotEquals(stamp, DuplicateScanner.stamp(changed, DuplicateScanner.attributes(changed)));
    }

    @Test
    void ignoresPersistOnlyForTheSameTwoFileIdentities() throws Exception {
        var a = file("a.txt", "A");
        var b = file("b.txt", "B");
        var match = new DuplicateResult("pair", a, b, "stampA", "stampB", Set.of(DuplicateResult.Evidence.FILENAME), DuplicateResult.Strength.POSSIBLE);
        var store = new DuplicateReviewStore(temp, Runnable::run);
        assertTrue(store.ready());
        assertTrue(store.ignore(match));
        var next = new DuplicateReviewStore(temp, Runnable::run);
        assertTrue(next.contains(match));
        var changed = new DuplicateResult("pair", a, b, "changed", "stampB", match.evidence(), match.strength());
        assertFalse(next.contains(changed));
        next.restore(match.reviewKey());
        assertFalse(new DuplicateReviewStore(temp, Runnable::run).contains(match));
        next.ignore(match);
        next.clear();
        assertTrue(new DuplicateReviewStore(temp, Runnable::run).snapshot().isEmpty());
    }

    @Test
    void unavailableFilesDoNotAbortAndCancellationRetainsOriginalDocuments() throws Exception {
        var a = file("a.txt", "same");
        var b = file("b.txt", "same");
        var offline = new Track("offline", "gone", temp.resolve("missing.txt"), "Unavailable", "", "", 180, 5, 1, "");
        try (var scanner = new DuplicateScanner(temp.resolve("data"))) {
            scanner.start(List.of(a, b, offline), DuplicatePolicy.BALANCED, false);
            scanner.settled().get(10, TimeUnit.SECONDS);
            assertEquals(1, scanner.snapshot().unavailable());
            assertEquals(1, scanner.snapshot().pairs().size());
            scanner.start(Collections.nCopies(100_000, a), DuplicatePolicy.BALANCED, false);
            scanner.cancel();
            scanner.settled().get(10, TimeUnit.SECONDS);
            assertEquals(DuplicateScanner.Stage.CANCELLED, scanner.snapshot().stage());
        }
        assertEquals("same", Files.readString(a.file()));
        assertEquals("same", Files.readString(b.file()));
    }
}
