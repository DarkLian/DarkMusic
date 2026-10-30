package com.darkmusic.client;

import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.MediaFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportHistoryTest {
    @Test
    void resetDoesNotResurrectPriorEntriesOnTheNextImport() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        Path root = Files.createTempDirectory("darkmusic-history-reset-");
        try {
            var history = new ImportHistory(root, io, message -> {
                throw new AssertionError(message);
            });
            history.record(new ImportHistory.Batch(1, "Old", 0, List.of(), ""));
            io.submit(() -> {
            }).get();
            long before = history.revision();
            io.submit(() -> {
                try {
                    Files.delete(root.resolve("import-history.json"));
                    history.reset();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get();
            assertTrue(history.batches().isEmpty());
            assertTrue(history.revision() > before);
            history.record(new ImportHistory.Batch(2, "New", 0, List.of(), ""));
            io.submit(() -> {
            }).get();
            assertEquals(List.of(2L), history.batches().stream().map(ImportHistory.Batch::timestamp).toList());
            assertFalse(Files.readString(root.resolve("import-history.json")).contains("Old"));
        } finally {
            io.shutdown();
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
            Files.deleteIfExists(root.resolve("import-history.json"));
            Files.delete(root);
        }
    }

    @TempDir
    Path temp;

    @Test
    void recordsQueuedBeforeLoadingFinishPersistNewestFirstWithFailuresAndUnicode() throws Exception {
        var warnings = new CopyOnWriteArrayList<String>();
        var io = Executors.newSingleThreadExecutor();
        try {
            var history = new ImportHistory(temp, io, warnings::add);
            var entries = List.of(new MediaFiles.ImportEntry("外部/音樂.mp3", "library/音樂.mp3", true, ""), new MediaFiles.ImportEntry("bad.txt", "", false, "Unsupported file"));
            history.record(new ImportHistory.Batch(1, "Favorite Music", 2, entries, ""));
            history.record(new ImportHistory.Batch(2, "Subfolder", 1, List.of(), "Permission denied"));
            io.submit(() -> {
            }).get();
            var reloaded = new ImportHistory(temp, io, warnings::add);
            io.submit(() -> {
            }).get();
            assertEquals(List.of(2L, 1L), reloaded.batches().stream().map(ImportHistory.Batch::timestamp).toList());
            assertEquals(entries, reloaded.batches().get(1).entries());
            assertEquals("Permission denied", reloaded.batches().getFirst().error());
            assertTrue(warnings.isEmpty(), warnings.toString());
        } finally {
            io.shutdown();
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void removingSelectedRecordsChangesOnlyTheSavedHistory() throws Exception {
        // Stands in for an imported file; the history must never touch it.
        Path imported = Files.writeString(temp.resolve("imported.txt"), "kept");
        var io = Executors.newSingleThreadExecutor();
        try {
            var history = new ImportHistory(temp, io, message -> {
                throw new AssertionError(message);
            });
            var first = new MediaFiles.ImportEntry("a.mp3", imported.toString(), true, "");
            var second = new MediaFiles.ImportEntry("b.mp3", "", false, "Unsupported file");
            history.record(new ImportHistory.Batch(1, "Favorite Music", 2, List.of(first, second), ""));
            history.record(new ImportHistory.Batch(2, "Subfolder", 1, List.of(), "Permission denied"));
            io.submit(() -> {
            }).get();
            var files = history.batches().get(1);
            var failure = new ImportHistory.Item(history.batches().getFirst(), null);

            history.remove(List.of(new ImportHistory.Item(files, second), failure));
            io.submit(() -> {
            }).get();
            var reloaded = new ImportHistory(temp, io, message -> {
                throw new AssertionError(message);
            });
            io.submit(() -> {
            }).get();
            assertEquals(1, reloaded.batches().size());
            assertEquals(List.of(first), reloaded.batches().getFirst().entries());

            // Records already gone, as from a screen that has not refreshed yet, change nothing.
            long revision = reloaded.revision();
            reloaded.remove(List.of(failure, new ImportHistory.Item(files, second)));
            io.submit(() -> {
            }).get();
            assertEquals(revision, reloaded.revision());

            // Everything selected.
            reloaded.remove(List.of(new ImportHistory.Item(reloaded.batches().getFirst(), first)));
            io.submit(() -> {
            }).get();
            assertTrue(reloaded.batches().isEmpty());
            var empty = new ImportHistory(temp, io, message -> {
                throw new AssertionError(message);
            });
            io.submit(() -> {
            }).get();
            assertTrue(empty.batches().isEmpty());
            assertEquals("kept", Files.readString(imported));
        } finally {
            io.shutdown();
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void corruptedHistoryIsPreservedBeforeSavingAndHistoryRetentionIsBounded() throws Exception {
        Path file = Files.writeString(temp.resolve("import-history.json"), "broken history");
        var io = Executors.newSingleThreadExecutor();
        try {
            var history = new ImportHistory(temp, io, message -> {
            });
            for (int i = 0; i < 110; i++) history.record(new ImportHistory.Batch(i, "All", 0, List.of(), "cancelled"));
            io.submit(() -> {
            }).get();
            assertEquals(100, history.batches().size());
            assertEquals(109, history.batches().getFirst().timestamp());
            assertTrue(Files.readString(file).startsWith("["));
            try (var paths = Files.list(temp)) {
                var backup = paths.filter(p -> p.getFileName().toString().startsWith("import-history.invalid-")).findFirst().orElseThrow();
                assertEquals("broken history", Files.readString(backup));
            }
        } finally {
            io.shutdown();
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
