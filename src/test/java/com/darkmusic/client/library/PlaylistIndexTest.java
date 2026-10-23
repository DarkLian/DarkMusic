package com.darkmusic.client.library;

import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistIndexTest {
    @TempDir
    Path directory;

    @Test
    void twentyPlaylistsReuseOneHundredThousandTrackIndexAndInvalidateOnSnapshotChange() {
        var reads = new AtomicInteger();
        List<Track> library = new AbstractList<>() {
            @Override
            public int size() {
                return 100_000;
            }

            @Override
            public Track get(int index) {
                reads.incrementAndGet();
                return new Track("root", index + ".opus", directory.resolve(index + ".opus"), "Title " + index, "Artist", "Album", 1, 1, 1, "");
            }
        };
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new PlaylistStore(directory, io, message -> fail(message));
            var playlists = java.util.stream.IntStream.range(0, 20).mapToObj(index -> new PlaylistStore.Playlist("list" + index, "List " + index,
                List.of(new PlaylistStore.Entry("root", index + ".opus")))).toList();
            store.loaded(playlists);
            long start = System.nanoTime();
            var first = store.index(library);
            for (var playlist : playlists) {
                assertEquals(1, store.resolve(playlist.id(), library).size());
                assertSame(first, store.index(library));
            }
            assertEquals(100_000, reads.get(), "Each library track should be indexed once, not once per playlist");
            var replacement = new Track("root", "0.opus", directory.resolve("0.opus"), "Edited title", "", "", 1, 2, 2, "");
            var refreshed = List.of(replacement);
            assertNotSame(first, store.index(refreshed));
            assertSame(replacement, store.resolve("list0", refreshed).getFirst());
            assertThrows(UnsupportedOperationException.class, () -> first.clear());
            System.out.printf(Locale.ROOT, "PLAYLIST_INDEX_100K_20: %.2f ms, %d track visits%n", (System.nanoTime() - start) / 1e6, reads.get());
        }
    }
}
