package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WatcherReliabilityTest {
    @TempDir
    Path temp;

    private Object field(MusicLibrary library, String name) throws Exception {
        var f = MusicLibrary.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(library);
    }

    private void replace(MusicLibrary library, List<Path> directories) throws Exception {
        var m = MusicLibrary.class.getDeclaredMethod("replaceWatcher", List.class);
        m.setAccessible(true);
        m.invoke(library, directories);
    }

    @Test
    void failedDirectoryBackoffPreservesHealthyKeysAndService() throws Exception {
        Path healthy = Files.createDirectory(temp.resolve("healthy")), missing = temp.resolve("later");
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            replace(library, List.of(healthy, missing));
            Object service = field(library, "watcher");
            var keys = (Map<Path, WatchKey>) field(library, "watchKeys");
            WatchKey original = keys.get(healthy);
            Files.createDirectory(missing);
            replace(library, List.of(healthy, missing));
            assertSame(service, field(library, "watcher"));
            assertSame(original, keys.get(healthy));
            assertFalse(keys.containsKey(missing));
            ((Map<Path, Long>) field(library, "failedWatches")).put(missing, 0L);
            replace(library, List.of(healthy, missing));
            assertSame(service, field(library, "watcher"));
            assertEquals(Set.of(healthy, missing), field(library, "watchedDirectories"));
            assertTrue(original.isValid());
            WatchKey removed = keys.get(missing);
            replace(library, List.of(healthy));
            assertFalse(removed.isValid());
            assertTrue(original.isValid());
        }
    }

    @Test
    void bookkeepingDeletesAreIgnoredButKnownExtensionlessAudioAndDirectoriesRemainRelevant() throws Exception {
        Path root = Files.createDirectory(temp.resolve("music"));
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            for (String name : List.of(".darkmusic-work-example", ".darkmusic-lyrics-recovery-" + UUID.randomUUID() + ".json", "song.lrc.darkmusic-backup", "song.lrc.darkmusic.json", ".darkmusic-sources.json"))
                assertTrue(library.ignoredWatchPath(root.resolve(name)), name);
            assertFalse(library.ignoredWatchPath(root.resolve("song.mp3")));
            replace(library, List.of(root));
            assertTrue(library.knownWatchPath(root));
            assertFalse(library.knownWatchPath(root.resolve("unrelated.txt")));
            Path audio = root.resolve("extensionless");
            var track = new Track("root", "extensionless", audio, "Song", "", "", 1, 10, 1, "");
            var snapshot = MusicLibrary.class.getDeclaredField("snapshot");
            snapshot.setAccessible(true);
            snapshot.set(library, new MusicLibrary.Snapshot(1, List.of(track), true, ""));
            assertTrue(library.knownWatchPath(audio));
            snapshot.set(library, new MusicLibrary.Snapshot(2, List.of(track), false, ""));
            ((java.util.concurrent.atomic.AtomicBoolean)field(library, "running")).set(true);
            assertTrue(library.knownWatchPath(audio), "Cache publication cannot hide a deleted extensionless song");
            ((java.util.concurrent.atomic.AtomicBoolean)field(library, "running")).set(false);
            assertFalse(library.knownWatchPath(root.resolve("never-indexed.mp3")));
        }
    }

    @Test
    void unexpectedPollingErrorDoesNotDisableFuturePolls() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        WatchService service = new WatchService() {
            public WatchKey poll() {
                if (attempts.getAndIncrement() == 0) throw new AssertionError("injected watcher failure");
                return null;
            }

            public WatchKey poll(long timeout, TimeUnit unit) {
                return poll();
            }

            public WatchKey take() {
                return poll();
            }

            public void close() {
            }
        };
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            var f = MusicLibrary.class.getDeclaredField("watcher");
            f.setAccessible(true);
            f.set(library, service);
            var poll = MusicLibrary.class.getDeclaredMethod("poll");
            poll.setAccessible(true);
            assertDoesNotThrow(() -> poll.invoke(library));
            assertDoesNotThrow(() -> poll.invoke(library));
            assertEquals(2, attempts.get());
        }
    }
}
