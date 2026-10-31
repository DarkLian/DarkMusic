package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WatcherReliabilityTest {
    @TempDir
    Path temp;

    private void replace(MusicLibrary library, List<Path> directories) throws Exception {
        library.watcher().replace(directories);
    }

    @Test
    void failedDirectoryBackoffPreservesHealthyKeysAndService() throws Exception {
        Path healthy = Files.createDirectory(temp.resolve("healthy")), missing = temp.resolve("later");
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            replace(library, List.of(healthy, missing));
            Object service = library.watcher().service();
            WatchKey original = library.watcher().key(healthy);
            Files.createDirectory(missing);
            replace(library, List.of(healthy, missing));
            assertSame(service, library.watcher().service());
            assertSame(original, library.watcher().key(healthy));
            assertFalse(library.watcher().watched().contains(missing));
            library.watcher().retryNow(missing);
            replace(library, List.of(healthy, missing));
            assertSame(service, library.watcher().service());
            assertEquals(Set.of(healthy, missing), library.watcher().watched());
            assertTrue(original.isValid());
            WatchKey removed = library.watcher().key(missing);
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
                assertTrue(library.watcher().ignored(root.resolve(name)), name);
            assertFalse(library.watcher().ignored(root.resolve("song.mp3")));
            replace(library, List.of(root));
            assertTrue(library.watcher().known(root));
            assertFalse(library.watcher().known(root.resolve("unrelated.txt")));
            Path audio = root.resolve("extensionless");
            var track = new Track("root", "extensionless", audio, "Song", "", "", 1, 10, 1, "");
            library.publish(new MusicLibrary.Snapshot(1, List.of(track), true, ""));
            assertTrue(library.watcher().known(audio));
            library.publish(new MusicLibrary.Snapshot(2, List.of(track), false, ""));
            library.scanRunning(true);
            assertTrue(library.watcher().known(audio), "Cache publication cannot hide a deleted extensionless song");
            library.scanRunning(false);
            assertFalse(library.watcher().known(root.resolve("never-indexed.mp3")));
        }
    }

    @Test
    void unexpectedPollingErrorDoesNotDisableFuturePolls() throws Exception {
        var attempts = new AtomicInteger();
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
            library.watcher().service(service);
            assertDoesNotThrow(() -> library.watcher().poll());
            assertDoesNotThrow(() -> library.watcher().poll());
            assertEquals(2, attempts.get());
        }
    }
}
