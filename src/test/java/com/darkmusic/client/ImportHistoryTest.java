package com.darkmusic.client;

import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.MediaFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ImportHistoryTest {
    @TempDir
    Path temp;

    @Test
    void recordsQueuedBeforeLoadingFinishPersistNewestFirstWithFailuresAndUnicode() throws Exception {
        var warnings = new java.util.concurrent.CopyOnWriteArrayList<String>();
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
