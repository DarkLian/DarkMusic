package com.darkmusic.client;

import com.darkmusic.client.gui.SongSelection;
import com.darkmusic.client.playlist.PlaylistDrag;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BulkEditingTest {
    @TempDir
    Path temp;

    @Test
    void connectedDragUsesOnlyTheGrabbedGroupAndInsertsWithoutReversing() {
        var keys = List.of("a", "b", "c", "d", "e", "f");
        var drag = PlaylistDrag.connected(keys, Set.of("b", "c", "e"), 1);
        assertEquals(1, drag.start());
        assertEquals(2, drag.count());
        assertEquals(List.of(0, 3, 4, 5, 1, 2), drag.atBoundary(6, 6).order(6));
        assertEquals(List.of(1, 2, 0, 3, 4, 5), drag.atBoundary(0, 6).order(6));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), drag.atBoundary(2, 6).order(6));
        assertEquals(1, PlaylistDrag.connected(keys, Set.of("b", "c", "e"), 4).count());
        assertThrows(IllegalArgumentException.class, () -> new PlaylistDrag(5, 2, 0).order(6));
    }

    @Test
    void eligibleSongsAndBlockMovesRemainStableAcrossReload() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        try {
            var store = new PlaylistStore(temp, io, message -> fail(message));
            var library = java.util.stream.IntStream.range(0, 5).mapToObj(i -> StorageTest.track("r", i + ".mp3", temp.resolve(i + ".mp3"))).toList();
            String id = store.create("Drag");
            store.addAll(id, library.subList(0, 2));
            assertEquals(library.subList(2, 5), store.availableToAdd(id, library));
            store.addAll(id, library);
            assertTrue(store.availableToAdd(id, library).isEmpty());
            long revision = store.revision();
            store.moveBlock(id, 1, 2, 3);
            assertEquals(revision + 1, store.revision());
            assertEquals(List.of(library.get(0), library.get(3), library.get(4), library.get(1), library.get(2)), store.resolve(id, library));
            store.moveBlock(id, 3, 2, 3);
            assertEquals(revision + 1, store.revision(), "No-op drops do not save");
            io.submit(() -> {
            }).get();
            var reloaded = new PlaylistStore(temp, io, message -> fail(message));
            reloaded.loaded(reloaded.read());
            assertEquals(store.get(id), reloaded.get(id));
            store.removeAll(id, Set.of(library.get(1).id()));
            assertEquals(List.of(library.get(1)), store.availableToAdd(id, library));
            assertTrue(store.availableToAdd("deleted", library).isEmpty());
        } finally {
            io.shutdown();
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void selectionSurvivesFilteringAndUsesStableReferences() {
        var s = new SongSelection();
        s.visible(List.of("a", "b", "c", "d"));
        s.choose(1, false, false);
        s.choose(3, false, true);
        assertEquals(Set.of("b", "c", "d"), s.selected());
        s.visible(List.of("a", "d"));
        assertEquals(2, s.hidden());
        s.invert();
        assertEquals(Set.of("a", "b", "c"), s.selected());
        s.all();
        assertEquals(4, s.selected().size());
        s.retain(List.of("b", "d"));
        assertEquals(Set.of("b", "d"), s.selected());
        s.clear();
        assertTrue(s.selected().isEmpty());
    }

    @Test
    void bulkOperationsAreOrderedDeduplicatedAndPersistOncePerOperation() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        try {
            var store = new PlaylistStore(temp, io, message -> fail(message));
            var a = StorageTest.track("r", "a.mp3", temp.resolve("a"));
            var b = StorageTest.track("r", "b.mp3", temp.resolve("b"));
            var c = StorageTest.track("r", "c.mp3", temp.resolve("c"));
            var d = StorageTest.track("r", "d.mp3", temp.resolve("d"));
            String id = store.create("Test");
            long revision = store.revision();
            store.addAll(id, List.of(a, b, a, c, d));
            assertEquals(revision + 1, store.revision());
            assertEquals(List.of(a, b, c, d), store.resolve(id, List.of(a, b, c, d)));
            store.moveAll(id, Set.of(b.id(), c.id()), -1);
            assertEquals(List.of(b, c, a, d), store.resolve(id, List.of(a, b, c, d)));
            store.moveAll(id, Set.of(b.id(), c.id()), 1);
            assertEquals(List.of(a, b, c, d), store.resolve(id, List.of(a, b, c, d)));
            store.removeAll(id, Set.of(a.id(), c.id()));
            assertEquals(List.of(b, d), store.resolve(id, List.of(a, b, c, d)));
            io.submit(() -> {
            }).get();
            var reloaded = new PlaylistStore(temp, io, message -> fail(message));
            reloaded.loaded(io.submit(reloaded::read).get());
            assertEquals(store.get(id), reloaded.get(id));
            assertEquals(List.of(d), reloaded.resolve(id, List.of(d)), "Missing references are retained but not played");
        } finally {
            io.shutdown();
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
