package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
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
            library.watcher().replace(List.of(root, album));
            assertFalse(library.watcher().relevant(StandardWatchEventKinds.ENTRY_MODIFY, album));
            assertFalse(library.watcher().relevant(StandardWatchEventKinds.ENTRY_CREATE, album.resolve("song.lrc.darkmusic.json")));
            assertTrue(library.watcher().relevant(StandardWatchEventKinds.ENTRY_CREATE, album));
            assertTrue(library.watcher().relevant(StandardWatchEventKinds.ENTRY_DELETE, album));
            assertTrue(library.watcher().relevant(StandardWatchEventKinds.ENTRY_MODIFY, album.resolve("song.mp3")));
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
            library.watcher().service(service);
            Thread worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    library.watcher().poll();
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
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
