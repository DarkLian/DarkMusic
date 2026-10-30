package com.darkmusic.client;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Playlist reconciliation after scans, with the storage executor and the client thread as queues the test drains,
 * so a scan, a playlist edit or a reset can land between the check and its result. Files are disposable text files;
 * no audio is involved.
 */
class LibraryReconciliationTest {
    @TempDir
    Path directory;
    private ExecutorService writes;
    private final ArrayDeque<Runnable> storage = new ArrayDeque<>(), client = new ArrayDeque<>();
    private final Fake inputs = new Fake();
    private LibraryReconciliation reconciliation;
    private Path first, second;

    private final class Fake implements LibraryReconciliation.Inputs {
        MusicLibrary.Snapshot snapshot;
        Map<Path, String> roots;
        PlaylistStore playlists;
        boolean idle = true, mediaBusy;

        public MusicLibrary.Snapshot snapshot() {
            return snapshot;
        }

        public Map<Path, String> roots() {
            return roots;
        }

        public PlaylistStore playlists() {
            return playlists;
        }

        public boolean playlistsReady() {
            return playlists != null;
        }

        public boolean idle() {
            return idle;
        }

        public boolean mediaBusy() {
            return mediaBusy;
        }

        public PlaybackController playback() {
            return null;
        }

        public LyricsLookup lyrics() {
            return null;
        }

        public DuplicateScanner cleanupScanner() {
            return null;
        }

        public DuplicateReviewStore cleanupReviews() {
            return null;
        }

        public LibrarySourceIndex sourceIndex() {
            return null;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        writes = Executors.newSingleThreadExecutor();
        first = Files.createDirectories(directory.resolve("first"));
        second = Files.createDirectories(directory.resolve("second"));
        Files.writeString(first.resolve("unscanned.txt"), "present on disk, not in the scan");
        inputs.roots = Map.of(first, "a", second, "b");
        var store = new PlaylistStore(Files.createDirectories(directory.resolve("config")), writes, message -> fail(message));
        store.loaded(List.of(new PlaylistStore.Playlist("mix", "Mix", List.of(
            new PlaylistStore.Entry("a", "deleted.txt"), new PlaylistStore.Entry("a", "unscanned.txt"),
            new PlaylistStore.Entry("a", "scanned.txt"), new PlaylistStore.Entry("b", "deleted.txt")))));
        inputs.playlists = store;
        inputs.snapshot = scan(1, Set.of("a"));
        reconciliation = new LibraryReconciliation(inputs, storage::add, client::add, message -> fail(message));
    }

    @AfterEach
    void tearDown() throws Exception {
        writes.shutdown();
        assertTrue(writes.awaitTermination(5, TimeUnit.SECONDS));
    }

    private MusicLibrary.Snapshot scan(long revision, Set<String> complete) {
        var track = new Track("a", "scanned.txt", first.resolve("scanned.txt"), "Scanned", "", "", 1, 1, 1, "");
        return new MusicLibrary.Snapshot(revision, List.of(track), false, "", List.of(), complete.size() == 2, complete);
    }

    private static void drain(ArrayDeque<Runnable> queue) {
        while (!queue.isEmpty()) queue.poll().run();
    }

    private List<String> entries() {
        return inputs.playlists.get("mix").entries().stream().map(e -> e.rootId() + "/" + e.path()).toList();
    }

    @Test
    void removesOnlyEntriesWhoseFileIsGoneFromACompletelyScannedRoot() {
        assertFalse(reconciliation.tick());
        drain(storage);
        drain(client);
        // The incomplete root keeps its entry; a file on disk that the scan missed is not evidence of deletion.
        assertEquals(List.of("a/unscanned.txt", "a/scanned.txt", "b/deleted.txt"), entries());
        assertTrue(reconciliation.tick(), "reconciled with this scan, so autoplay may start");
        assertTrue(storage.isEmpty());
    }

    @Test
    void aPlaylistEditDuringTheCheckDiscardsTheResultAndChecksAgain() {
        reconciliation.tick();
        drain(storage);
        inputs.playlists.removeAll("mix", Set.of("b:deleted.txt"));
        drain(client);
        assertTrue(entries().contains("a/deleted.txt"), "a result for older playlists must not be applied");
        assertFalse(reconciliation.tick());
        drain(storage);
        drain(client);
        assertEquals(List.of("a/unscanned.txt", "a/scanned.txt"), entries());
    }

    @Test
    void changedRootsOrABusyClientDiscardTheResult() {
        reconciliation.tick();
        drain(storage);
        inputs.roots = Map.of(first, "a");
        drain(client);
        assertTrue(entries().contains("a/deleted.txt"));

        reconciliation.tick();
        drain(storage);
        inputs.idle = false;
        drain(client);
        assertTrue(entries().contains("a/deleted.txt"));
    }

    @Test
    void nothingStartsWhileMediaOperationsHoldTheFiles() {
        inputs.mediaBusy = true;
        assertFalse(reconciliation.tick());
        assertTrue(storage.isEmpty());
    }

    @Test
    void aCheckFromBeforeAResetCannotMarkTheNewLibraryReconciled() {
        reconciliation.tick();
        drain(storage);
        reconciliation.reset();
        // The new library numbers its scans from the start again, so its first complete scan has the old revision.
        drain(client);
        assertTrue(entries().contains("a/deleted.txt"), "the old library's result is discarded");
        assertFalse(reconciliation.tick(), "the new library's scan still gets its own check");
        assertEquals(1, storage.size());
        drain(storage);
        drain(client);
        assertFalse(entries().contains("a/deleted.txt"));
    }
}
