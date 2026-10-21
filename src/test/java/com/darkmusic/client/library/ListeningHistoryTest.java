package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ListeningHistoryTest {
    @TempDir
    Path directory;

    private Track track(String name, double duration) {
        return new Track("root", name, directory.resolve(name), name, "", "", duration, 1, 1, "");
    }

    private ListeningHistory history() {
        var history = new ListeningHistory(directory, Runnable::run, s -> fail(s));
        history.load();
        return history;
    }

    @Test
    void historyPlaybackKeepsBothViewsStableUntilAnotherCollectionStarts() {
        var h = history();
        var a = track("a.opus", 60);
        var b = track("b.opus", 60);
        var c = track("c.opus", 60);
        var library = List.of(a, b, c);
        h.replace(Map.of(a.id(), new ListeningHistory.Entry(1, 100, 30), b.id(), new ListeningHistory.Entry(2, 200, 60), c.id(), new ListeningHistory.Entry(0, 50, 1)));
        var recent = h.collection(library, false);
        var most = h.collection(library, true);
        h.playbackSource("root", "recent", recent, library);
        h.heard(a, 1, 30);
        h.heard(c, 2, 30);
        assertEquals(recent, h.collection(library, false));
        assertEquals(most, h.collection(library, true));
        assertEquals(2, h.snapshot().get(a.id()).plays());
        assertEquals(1, h.snapshot().get(c.id()).plays());
        // Switching History views or reopening the player does not start a fresh ranking.
        h.playbackSource("root", "most", most, library);
        assertEquals(recent, h.collection(library, false));
        assertEquals(most, h.collection(library, true));
        var renamed = track("renamed.opus", 60);
        h.rename(b, renamed);
        var changedLibrary = List.of(a, renamed, c);
        assertEquals(List.of(renamed, a, c), h.collection(changedLibrary, false));
        h.playbackSource("root", null, changedLibrary, changedLibrary);
        assertEquals(renamed, h.collection(changedLibrary, false).getLast());
        assertEquals(List.of(a, renamed, c), h.collection(changedLibrary, true));
    }

    @Test
    void qualifiedPlaysCountOncePerSessionAndShortTracksUseHalfTheirDuration() {
        var h = history();
        var longSong = track("long.opus", 300);
        var shortSong = track("short.opus", 10);
        h.heard(longSong, 1, 29);
        assertEquals(0, h.snapshot().get(longSong.id()).plays());
        h.heard(longSong, 1, 1);
        h.heard(longSong, 1, 60);
        assertEquals(1, h.snapshot().get(longSong.id()).plays());
        h.heard(longSong, 2, 30);
        assertEquals(2, h.snapshot().get(longSong.id()).plays());
        h.heard(shortSong, 3, 4);
        assertEquals(0, h.snapshot().get(shortSong.id()).plays());
        h.heard(shortSong, 3, 1);
        assertEquals(1, h.snapshot().get(shortSong.id()).plays());
        assertEquals(120, h.snapshot().get(longSong.id()).seconds());
        h.heard(longSong, 4, Double.NaN);
        h.heard(longSong, 4, -5);
        assertEquals(120, h.snapshot().get(longSong.id()).seconds());
    }

    @Test
    void persistenceRenameClearAndStableCollectionSnapshots() {
        var h = history();
        var a = track("a.opus", 60);
        var b = track("b.opus", 60);
        h.heard(a, 1, 30);
        h.heard(b, 2, 30);
        h.heard(b, 3, 30);
        var queue = h.collection(List.of(a, b), true);
        assertEquals(List.of(b, a), queue);
        h.heard(a, 4, 30);
        h.heard(a, 5, 30);
        assertEquals(List.of(b, a), queue);
        assertEquals(a, h.collection(List.of(a, b), true).getFirst());
        var renamed = track("renamed.opus", 60);
        h.rename(a, renamed);
        assertFalse(h.snapshot().containsKey(a.id()));
        h.flush(true);
        var restart = history();
        assertEquals(h.snapshot(), restart.snapshot());
        restart.replace(Map.of());
        restart.flush(true);
        assertTrue(history().snapshot().isEmpty());
    }

    @Test
    void storageWritesAreDebouncedAndRestoreSuspendsRecording() {
        var tasks = new ArrayList<Runnable>();
        var h = new ListeningHistory(directory, tasks::add, s -> fail(s));
        h.load();
        var a = track("a.opus", 60);
        h.heard(a, 1, 1);
        h.flush(true);
        assertEquals(1, tasks.size());
        for (int i = 0; i < 100; i++) {
            h.heard(a, 1, .01);
            h.flush(false);
        }
        assertEquals(1, tasks.size());
        h.suspended(true);
        var before = h.snapshot();
        h.heard(a, 2, 30);
        assertEquals(before, h.snapshot());
        h.suspended(false);
        h.flush(true);
        assertEquals(2, tasks.size());
        tasks.forEach(Runnable::run);
        assertEquals(h.snapshot(), history().snapshot());
    }

    @Test
    void corruptOrUnsupportedDataIsPreservedUntilExplicitReplacement() throws Exception {
        Path file = directory.resolve("listening-history.json");
        Files.writeString(file, "{\"version\":99}");
        var errors = new ArrayList<String>();
        var h = new ListeningHistory(directory, Runnable::run, errors::add);
        h.load();
        assertTrue(h.ready());
        assertEquals(1, errors.size());
        h.heard(track("a.opus", 60), 1, 30);
        h.flush(true);
        assertEquals("{\"version\":99}", Files.readString(file));
        h.replace(Map.of());
        h.flush(true);
        assertTrue(history().snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ListeningHistory.validate(Map.of("root:a", new ListeningHistory.Entry(-1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> ListeningHistory.validate(Map.of("root:a", new ListeningHistory.Entry(1, 0, Double.POSITIVE_INFINITY))));
    }
}
