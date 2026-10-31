package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Tracks;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class PlayOrderTest {
    private static Track track(String name) {
        return Tracks.of("r", name, Path.of(name));
    }

    @Test
    void inOrderTheCurrentSongKeepsItsPlace() {
        var order = new PlayOrder();
        order.rebuild(2, 5, false);
        assertEquals(List.of(0, 1, 2, 3, 4), order.list());
        assertEquals(2, order.cursor());
        assertEquals(2, order.current());
        order.rebuild(-1, 3, false);
        assertEquals(-1, order.cursor());
        assertEquals(-1, order.current());
    }

    @Test
    void shuffledTheCurrentSongComesFirstAndEverySongOnce() {
        var order = new PlayOrder();
        order.rebuild(3, 50, true);
        assertEquals(3, order.at(0));
        assertEquals(0, order.cursor());
        assertEquals(50, new TreeSet<>(order.list()).size());
    }

    @Test
    void theVersionChangesOnRebuildAndBumpOnly() {
        var order = new PlayOrder();
        long start = order.version();
        order.rebuild(0, 3, false);
        order.cursor(1);
        order.remember(track("a"), false);
        order.adopt(List.of(2, 1, 0), 0);
        assertEquals(start + 1, order.version());
        order.bump();
        order.wrap(0, 3, true);
        assertEquals(start + 3, order.version());
    }

    @Test
    void aWrapDoesNotRepeatTheSongThatJustPlayed() {
        for (int attempt = 0; attempt < 50; attempt++) {
            var order = new PlayOrder();
            order.wrap(1, 2, true);
            assertEquals(0, order.cursor());
            assertEquals(0, order.at(0));
        }
    }

    @Test
    void adoptKeepsTheListWhenThePlanUsedIt() {
        var order = new PlayOrder();
        order.rebuild(0, 3, false);
        var live = order.list();
        order.adopt(live, 1);
        assertSame(live, order.list());
        assertEquals(1, order.cursor());
        order.adopt(List.of(2, 0, 1), 0);
        assertEquals(List.of(2, 0, 1), order.list());
        assertSame(live, order.list(), "the same list, refilled");
    }

    @Test
    void backReturnsToTheRememberedSongAndItsPlace() {
        var order = new PlayOrder();
        order.rebuild(0, 4, false);
        var a = track("a");
        order.remember(a, false);
        order.cursor(2);
        var visited = order.back();
        assertEquals(a, visited.track());
        assertEquals(0, order.cursor());
        assertNull(order.back());
        assertTrue(order.stepBack());
        assertEquals(3, order.cursor(), "wraps to the end");
        order.reset();
        assertFalse(order.stepBack());
    }

    @Test
    void historyIsBounded() {
        var order = new PlayOrder();
        order.rebuild(0, 1, false);
        for (int i = 0; i < 1005; i++) order.remember(track("s" + i), false);
        int count = 0;
        while (order.back() != null) count++;
        assertEquals(1000, count);
    }

    @Test
    void remapKeepsTheCurrentSongOrTheOneBeforeIt() {
        var order = new PlayOrder();
        order.rebuild(2, 5, false);
        var gone = track("gone");
        order.remember(gone, false);
        // Track 1 is deleted: 0 stays 0, 2 becomes 1, 3 becomes 2, 4 becomes 3.
        order.remap(new int[]{0, -1, 1, 2, 3}, Set.of());
        assertEquals(List.of(0, 1, 2, 3), order.list());
        assertEquals(1, order.cursor());
        // The current song (now 1) is deleted: the cursor rests on the one before it.
        order.remap(new int[]{0, -1, 1, 2}, Set.of(gone.id()));
        assertEquals(List.of(0, 1, 2), order.list());
        assertEquals(0, order.cursor());
        assertNull(order.back(), "a deleted song leaves the history");
    }

    @Test
    void anEditReplacesTheSongInTheHistory() {
        var order = new PlayOrder();
        order.rebuild(0, 2, false);
        var a = track("a");
        order.remember(a, true);
        var edited = new Track("r", "a", Path.of("a"), "Edited", "Artist", "Album", 1, 0, 0, "");
        order.replaceTrack(a.id(), edited);
        var visited = order.back();
        assertEquals(edited, visited.track());
        assertTrue(visited.manual());
    }
}
