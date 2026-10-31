package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Tracks;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UpNextQueueTest {
    private static Track track(String name) {
        return Tracks.of("r", name, Path.of(name));
    }

    private final Track a = track("a"), b = track("b"), c = track("c");

    @Test
    void entriesGetIdsThatRemovalAndDragsName() {
        var queue = new UpNextQueue();
        queue.add(a, false);
        queue.add(b, false);
        queue.add(c, true);
        assertEquals(List.of(c, a, b), queue.entries().stream().map(PlaybackController.Queued::track).toList());
        assertEquals(List.of(3L, 1L, 2L), queue.ids());
        queue.insert(99, a);
        assertEquals(4L, queue.entries().getLast().id(), "an index past the end appends");
        assertEquals(1, queue.indexOf(1));
        assertEquals(-1, queue.indexOf(42));
        assertTrue(queue.remove(1));
        assertFalse(queue.remove(1));
        assertEquals(c, queue.removeFirst().track());
        assertEquals(List.of(2L, 4L), queue.ids());
    }

    @Test
    void aStaleOrInvalidDragIsRefused() {
        var queue = new UpNextQueue();
        queue.add(a, false);
        queue.add(b, false);
        assertNull(queue.reordered(List.of(2L, 1L), List.of(1, 0)), "the ids changed meanwhile");
        assertNull(queue.reordered(List.of(1L, 2L), List.of(0, 0)), "a position twice");
        assertNull(queue.reordered(List.of(1L, 2L), List.of(0, 2)), "out of range");
        assertNull(queue.reordered(List.of(1L, 2L), List.of(0, 1)), "no change");
        queue.replace(queue.reordered(List.of(1L, 2L), List.of(1, 0)));
        assertEquals(List.of(2L, 1L), queue.ids());
    }

    @Test
    void fullAtTheControllerLimit() {
        var queue = new UpNextQueue();
        for (int i = 0; i < PlaybackController.QUEUE_LIMIT; i++) {
            assertFalse(queue.full());
            queue.add(a, false);
        }
        assertTrue(queue.full());
    }

    @Test
    void reconcileDropsSongsOfGoneRootsAndCompleteScansAndTakesNewMetadata() {
        var queue = new UpNextQueue();
        var other = Tracks.of("gone", "x", Path.of("x"));
        queue.add(a, false);
        queue.add(b, false);
        queue.add(other, false);
        var renamed = new Track("r", "a", Path.of("a"), "New title", "Artist", "Album", 1, 0, 0, "");
        assertFalse(queue.stale(Map.of(a.id(), a, b.id(), b), Set.of("r", "gone"), Set.of()));
        assertTrue(queue.stale(Map.of(a.id(), renamed, b.id(), b), Set.of("r", "gone"), Set.of()));
        queue.reconcile(Map.of(a.id(), renamed), Set.of("r"), Set.of("r"));
        assertEquals(List.of(renamed), queue.entries().stream().map(PlaybackController.Queued::track).toList());
    }

    @Test
    void replacingAndRemovingTracksKeepsTheIds() {
        var queue = new UpNextQueue();
        queue.add(a, false);
        queue.add(b, false);
        var edited = new Track("r", "b", Path.of("b"), "Edited", "Artist", "Album", 1, 0, 0, "");
        queue.replaceTrack(b.id(), edited);
        assertEquals(edited, queue.entries().get(1).track());
        assertEquals(2L, queue.entries().get(1).id());
        queue.removeTracks(Set.of(a.id()));
        assertEquals(List.of(2L), queue.ids());
    }

    @Test
    void anyChangeDropsTheSnapshot() {
        var queue = new UpNextQueue();
        var snapshot = new PlaybackController.UpNextSnapshot(0, null, List.of(), List.of(), false, null, null, 0);
        queue.snapshot(snapshot);
        assertSame(snapshot, queue.snapshot());
        long revision = queue.revision();
        queue.changed();
        assertNull(queue.snapshot());
        assertEquals(revision + 1, queue.revision());
    }
}
