package com.darkmusic.client.library;

import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.lyrics.LyricsProvider;
import com.darkmusic.client.testing.Await;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PartialRootScopeTest {
    @TempDir
    Path temp;

    @Test
    void unreadableSubtreeKeepsSavedLyricsEvenWithoutALibraryCache() throws Exception {
        Path root = Files.createDirectories(temp.resolve("no-index-library"));
        Path denied = Files.createDirectory(root.resolve("denied"));
        Path file = Files.writeString(denied.resolve("kept.dat"), "Disposable identity");
        var kept = new Track("r", "denied/kept.dat", file, "Song", "Artist", "", 180, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
        Path missing = root.resolve("gone.dat");
        var gone = new Track("r", "gone.dat", missing, "Gone", "", "", 0, 0, 0, "");
        Path prefs = temp.resolve("preferences");
        var provider = new LyricsProvider() {
            public String id() {
                return "offline";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) {
                return List.of();
            }
        };
        var document = LyricsDocument.of("import", "1", "Song", "Artist", "", 180, "ja", LyricsDocument.Variant.ORIGINAL, false, false, "[00:01]Retained");
        try (var lyrics = new LyricsLookup(prefs, provider)) {
            lyrics.select(kept, document).get(5, TimeUnit.SECONDS);
            lyrics.offset(kept, 650).get(5, TimeUnit.SECONDS);
            lyrics.language(kept, "ja").get(5, TimeUnit.SECONDS);
            lyrics.encoding(kept, "Big5").get(5, TimeUnit.SECONDS);
            lyrics.offset(gone, 100).get(5, TimeUnit.SECONDS);
        }
        try (var library = new MusicLibrary(temp.resolve("no-index-data"), p -> {
        }, 2000, p -> !p.equals(denied));
             var lyrics = new LyricsLookup(prefs, provider)) {
            library.setRoot(root, "r");
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "No-index scan completes");
            var scan = library.snapshot();
            assertTrue(scan.tracks().isEmpty());
            assertEquals(List.of("denied"), scan.unreadableScopes().get("r"));
            lyrics.prune(scan.tracks(), Set.of("r"), scan.completeRoots(), scan.unreadableScopes());
            lyrics.offset(kept, 650).get(5, TimeUnit.SECONDS); // Storage barrier, not a timing sleep.
            try (var records = Files.list(prefs.resolve("tracks"))) {
                assertEquals(1, records.count());
            }
            assertEquals(document, lyrics.choice(kept).selected());
            assertEquals(650, lyrics.choice(kept).offset());
            assertEquals("ja", lyrics.choice(kept).language());
            assertEquals("Big5", lyrics.encoding(kept));
        }
    }

    @Test
    void queuedWalkerDoesNotKeepTheLibraryScanningBehindStalledWalkers() throws Exception {
        Path first = Files.createDirectories(temp.resolve("first")), second = Files.createDirectories(temp.resolve("second")), third = Files.createDirectories(temp.resolve("third"));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var library = new MusicLibrary(temp.resolve("queue-data"), p -> {
        }, 300, p -> {
            if (p.equals(first) || p.equals(second)) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        })) {
            library.setRoots(Map.of(first, "first", second, "second", third, "third"), first);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
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
        var saved = new JsonObject();
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
            library.publish(new MusicLibrary.Snapshot(library.snapshot().revision(), List.of(blocked, deleted), false, "", List.of("@r/denied"), true, Set.of("r")));
            long revision = library.snapshot().revision();
            library.rescan();
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning() && library.snapshot().revision() > revision, "Scoped rescan");
            assertEquals(List.of(blocked), library.snapshot().tracks());
            assertTrue(library.snapshot().completeRoots().contains("r"));
            assertFalse(library.snapshot().message().isBlank());
        }
    }
}
