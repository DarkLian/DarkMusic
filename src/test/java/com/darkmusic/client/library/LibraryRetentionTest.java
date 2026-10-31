package com.darkmusic.client.library;

import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic scan-state tests; Track values are metadata, not fabricated audio files.
 */
class LibraryRetentionTest {
    @TempDir
    Path directory;

    private Track track(Path root, String id, String name, String title) {
        return new Track(id, name, root.resolve(name), title, "", "", 0, 0, 0, "");
    }

    private MusicLibrary.Snapshot combined(MusicLibrary library, Path root, MusicLibrary.Snapshot prior,
                                           List<Track> scanned, boolean complete, boolean finished) {
        return library.combined(root, "root", prior, scanned, List.of("@root", "@root/new"), complete, finished);
    }

    @Test
    void partialAndFailedScansMergeOldEntriesAndPreferFreshMetadata() throws Exception {
        Path root = directory.resolve("music");
        var old = track(root, "root", "known.opus", "Old title");
        var missing = track(root, "root", "old/retained.opus", "Retained");
        var fresh = track(root, "root", "known.opus", "Updated title");
        var added = track(root, "root", "new/added.opus", "Added");
        var prior = new MusicLibrary.Snapshot(1, List.of(old, missing), false, "", List.of("@root", "@root/old"), true, Set.of("root"));
        try (var library = new MusicLibrary(directory.resolve("config"))) {
            for (boolean finished : List.of(false, true)) {
                var value = combined(library, root, prior, List.of(fresh, added), false, finished);
                assertEquals(Set.of(fresh, missing, added), Set.copyOf(value.tracks()));
                assertEquals(3, value.tracks().size(), "Stable IDs must not be duplicated");
                assertTrue(value.folders().containsAll(List.of("@root/old", "@root/new")));
                assertFalse(value.complete());
                assertTrue(value.completeRoots().isEmpty());
            }
            var recovered = combined(library, root, prior, List.of(fresh, added), true, true);
            assertEquals(Set.of(fresh, added), Set.copyOf(recovered.tracks()));
            assertFalse(recovered.folders().contains("@root/old"));
            assertTrue(recovered.complete());
            assertEquals(Set.of("root"), recovered.completeRoots());
            assertTrue(combined(library, root, prior, List.of(), true, true).tracks().isEmpty());
            assertEquals(Set.of(old, missing), Set.copyOf(combined(library, root, prior, List.of(), false, true).tracks()));
        }
    }

    @Test
    void changingOnlySaveDestinationDoesNotPublishOrRestartActiveScan() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first")), second = Files.createDirectory(directory.resolve("second"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var starts = new AtomicInteger();
        try (var library = new MusicLibrary(directory.resolve("config"), path -> {
            if (path.equals(first)) {
                starts.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 5000)) {
            var roots = Map.of(first, "first", second, "second");
            library.setRoots(roots, first);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            synchronized (library) {
                var before = library.snapshot();
                library.setRoots(roots, second);
                assertSame(before, library.snapshot());
            }
            assertEquals(second, library.root());
            assertEquals("second", library.rootId());
            release.countDown();
            Await.until(Duration.ofSeconds(5), () -> library.snapshot().complete(), "Original scan completes");
            assertEquals(1, starts.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void reconfigurationKeepsRegisteredOfflineRootButRemovesUnregisteredEntries() throws Exception {
        Path kept = Files.createDirectory(directory.resolve("kept")), removed = Files.createDirectory(directory.resolve("removed"));
        Path added = directory.resolve("offline-added");
        var offline = new AtomicBoolean();
        try (var library = new MusicLibrary(directory.resolve("config"), path -> {
            if (offline.get()) throw new IllegalStateException("Unavailable test root");
        }, 1000)) {
            library.setRoots(Map.of(kept, "kept", removed, "removed"), kept);
            Await.until(Duration.ofSeconds(5), () -> library.snapshot().complete(), "Initial empty scan");
            var retained = track(kept, "kept", "old/song.opus", "Retained metadata");
            library.publish(new MusicLibrary.Snapshot(10, List.of(retained, track(removed, "removed", "song.opus", "Removed")),
                false, "", List.of("@kept", "@kept/old", "@removed"), true));
            offline.set(true);
            library.setRoots(Map.of(kept, "kept", added, "added"), kept);
            assertEquals(List.of(retained), library.snapshot().tracks());
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "Unavailable roots finish");
            assertEquals(List.of(retained), library.snapshot().tracks());
            assertTrue(library.snapshot().folders().contains("@kept/old"));
            assertFalse(library.snapshot().folders().contains("@removed"));
            assertFalse(library.snapshot().complete());
        }
    }
}
