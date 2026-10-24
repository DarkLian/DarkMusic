package com.darkmusic.client.library;

import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PartialRootScopeTest {
    @TempDir
    Path temp;

    @Test
    void queuedWalkerDoesNotKeepTheLibraryScanningBehindStalledWalkers() throws Exception {
        Path first = Files.createDirectories(temp.resolve("first")), second = Files.createDirectories(temp.resolve("second")), third = Files.createDirectories(temp.resolve("third"));
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var library = new MusicLibrary(temp.resolve("queue-data"), p -> {
        }, 300, p -> {
            if (p.equals(first) || p.equals(second)) {
                entered.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        })) {
            library.setRoots(Map.of(first, "first", second, "second", third, "third"), first);
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            Await.until(Duration.ofSeconds(3), () -> !library.snapshot().scanning(), "Queued and stalled roots time out independently");
            assertFalse(library.snapshot().complete());
            release.countDown();
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning() && library.snapshot().complete(), "Queued roots recover when permits are released");
        } finally {
            release.countDown();
        }
    }

    @Test
    void restartRetainsCachedEntriesUnderUnavailableSubtrees() throws Exception {
        Path root = Files.createDirectories(temp.resolve("restart-library"));
        Path denied = Files.createDirectories(root.resolve("denied"));
        Path data = Files.createDirectories(temp.resolve("restart-data"));
        var saved = new com.google.gson.JsonObject();
        saved.addProperty("root", "r");
        saved.addProperty("path", "denied/kept.dat");
        saved.addProperty("title", "Retained");
        saved.addProperty("artist", "");
        saved.addProperty("album", "");
        saved.addProperty("problem", "");
        saved.addProperty("size", 1);
        saved.addProperty("modified", 1);
        saved.addProperty("duration", 0);
        Files.writeString(data.resolve("library-cache.json"), "[" + saved + "]");
        try (var library = new MusicLibrary(data, p -> {
        }, 2000, p -> !p.equals(denied))) {
            library.setRoot(root, "r");
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "Restart scan");
            assertEquals("denied/kept.dat", library.snapshot().tracks().getFirst().relativePath());
        }
    }

    @Test
    void unreadableSubtreeRetainsOnlyItsPriorEntriesWhileHealthyMissingFilesDisappear() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path denied = Files.createDirectories(root.resolve("denied"));
        var blocked = new Track("r", "denied/kept.dat", denied.resolve("kept.dat"), "Retained", "", "", 0, 1, 1, "");
        var deleted = new Track("r", "gone.dat", root.resolve("gone.dat"), "Missing", "", "", 0, 1, 1, "");
        try (var library = new MusicLibrary(temp.resolve("data"), p -> {
        }, 2000, p -> !p.equals(denied))) {
            library.setRoot(root, "r");
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "Initial scan");
            var field = MusicLibrary.class.getDeclaredField("snapshot");
            field.setAccessible(true);
            field.set(library, new MusicLibrary.Snapshot(library.snapshot().revision(), List.of(blocked, deleted), false, "", List.of("@r/denied"), true, Set.of("r")));
            long revision = library.snapshot().revision();
            library.rescan();
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning() && library.snapshot().revision() > revision, "Scoped rescan");
            assertEquals(List.of(blocked), library.snapshot().tracks());
            assertTrue(library.snapshot().completeRoots().contains("r"));
            assertFalse(library.snapshot().message().isBlank());
        }
    }
}
