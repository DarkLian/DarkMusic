package com.darkmusic.client.library;

import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IndependentRootsTest {
    @TempDir
    Path directory;

    @Test
    void blockedRootDoesNotDelayOtherRootCompletionOrStartDuplicateScans() throws Exception {
        Path slow = Files.createDirectories(directory.resolve("slow")), local = Files.createDirectories(directory.resolve("local/nested")).getParent();
        var release = new CountDownLatch(1);
        var started = new AtomicInteger();
        try (var library = new MusicLibrary(directory.resolve("config"), path -> {
            if (path.equals(slow)) {
                started.incrementAndGet();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 700)) {
            var roots = new LinkedHashMap<Path, String>();
            roots.put(slow, "slow");
            roots.put(local, "local");
            library.setRoots(roots, local);
            Await.until(Duration.ofSeconds(5), () -> library.snapshot().completeRoots().contains("local"), "Local completion while another root is blocked");
            assertTrue(library.snapshot().scanning());
            assertTrue(library.snapshot().folders().contains("@local/nested"));
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "Inactive root timeout");
            assertFalse(library.snapshot().complete());
            assertEquals(Set.of("local"), library.snapshot().completeRoots());
            library.rescan();
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "Repeated scan remains bounded");
            assertEquals(1, started.get(), "A blocked root must not accumulate another worker each scan");
            release.countDown();
            Await.until(Duration.ofSeconds(5), () -> library.snapshot().completeRoots().containsAll(Set.of("local", "slow")), "Late root recovery");
        } finally {
            release.countDown();
        }
    }

    @Test
    void changingTheSaveDestinationDuringAScanRestartsTheCancelledGeneration() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first")), second = Files.createDirectory(directory.resolve("second"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var library = new MusicLibrary(directory.resolve("config"), path -> {
            if (path.equals(first)) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 1500)) {
            var roots = Map.of(first, "first", second, "second");
            library.setRoots(roots, first);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            library.setRoots(roots, second);
            release.countDown();
            Await.until(Duration.ofSeconds(5), () -> library.snapshot().completeRoots().containsAll(Set.of("first", "second")), "Scan after destination change");
            assertEquals(second, library.root());
        } finally {
            release.countDown();
        }
    }

    @Test
    void watchRegistrationFailureKeepsSuccessfulRegistrations() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first")), second = Files.createDirectory(directory.resolve("second"));
        try (var library = new MusicLibrary(directory.resolve("config"))) {
            var replace = MusicLibrary.class.getDeclaredMethod("replaceWatcher", List.class);
            replace.setAccessible(true);
            replace.invoke(library, List.of(first, directory.resolve("missing"), second));
            var field = MusicLibrary.class.getDeclaredField("watchedDirectories");
            field.setAccessible(true);
            assertEquals(Set.of(first, second), field.get(library));
            var watcher = MusicLibrary.class.getDeclaredField("watcher");
            watcher.setAccessible(true);
            assertNotNull(watcher.get(library));
        }
    }
}
