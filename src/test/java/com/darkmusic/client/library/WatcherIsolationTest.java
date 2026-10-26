package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WatcherIsolationTest {
    @TempDir
    Path temp;

    @Test
    void directoryModifyDoesNotTurnNestedBookkeepingIntoARescan() throws Exception {
        Path root = Files.createDirectory(temp.resolve("music"));
        Path album = Files.createDirectory(root.resolve("album"));
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            var replace = MusicLibrary.class.getDeclaredMethod("replaceWatcher", List.class);
            replace.setAccessible(true);
            replace.invoke(library, List.of(root, album));
            assertFalse(library.relevantWatchEvent(StandardWatchEventKinds.ENTRY_MODIFY, album));
            assertFalse(library.relevantWatchEvent(StandardWatchEventKinds.ENTRY_CREATE, album.resolve("song.lrc.darkmusic.json")));
            assertTrue(library.relevantWatchEvent(StandardWatchEventKinds.ENTRY_CREATE, album));
            assertTrue(library.relevantWatchEvent(StandardWatchEventKinds.ENTRY_DELETE, album));
            assertTrue(library.relevantWatchEvent(StandardWatchEventKinds.ENTRY_MODIFY, album.resolve("song.mp3")));
        }
    }

    @Test
    void blockedWatcherDoesNotHoldLibraryConfigurationOrClose() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        WatchService service = new WatchService() {
            public WatchKey poll() {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }

            public WatchKey poll(long timeout, TimeUnit unit) {
                return poll();
            }

            public WatchKey take() {
                return poll();
            }

            public void close() {
                release.countDown();
            }
        };
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            var watcher = MusicLibrary.class.getDeclaredField("watcher");
            watcher.setAccessible(true);
            watcher.set(library, service);
            var poll = MusicLibrary.class.getDeclaredMethod("poll");
            poll.setAccessible(true);
            Thread worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    poll.invoke(library);
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                    synchronized (library) {
                        assertNotNull(library.snapshot());
                    }
                    Path root = temp.resolve("not-mounted");
                    library.setRoots(Map.of(root, "test-root"), root);
                    library.close();
                });
            } finally {
                release.countDown();
                worker.join(2000);
            }
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertTrue(library.awaitStopped(2));
        }
    }
}
