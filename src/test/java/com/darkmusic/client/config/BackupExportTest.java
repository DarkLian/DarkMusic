package com.darkmusic.client.config;

import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BackupExportTest {
    @TempDir
    Path directory;

    @Test
    void exportPreparationRunsOnTheStorageWorkerAndKeepsCapturedSettingsAndCollections() throws Exception {
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new ConfigStore(directory.resolve("config/darkmusic"), io, message -> fail(message));
            var settings = new Settings();
            settings.volume = .31;
            store.save(settings);
            var history = new ListeningHistory(directory, io, message -> fail(message));
            var records = Map.of("root:song.opus", new ListeningHistory.Entry(3, 10, 45));
            history.replace(records);
            var source = history.snapshotSource();
            var playlists = new ArrayList<>(List.of(new PlaylistStore.Playlist("list", "Captured", List.of(new PlaylistStore.Entry("root", "song.opus")))));
            var bindings = new LinkedHashMap<>(Map.of("key.darkmusic.open", "key.keyboard.k"));
            long caller = Thread.currentThread().threadId();
            var copied = new AtomicInteger();
            var job = new BackupArchive.Export(store.snapshotJson(), "root", playlists, bindings, () -> {
                assertNotEquals(caller, Thread.currentThread().threadId());
                copied.incrementAndGet();
                return source.get();
            });
            assertEquals(0, copied.get());
            settings.volume = .8;
            store.save(settings);
            playlists.clear();
            bindings.put("key.darkmusic.open", "key.keyboard.b");
            history.replace(Map.of());
            Path target = directory.resolve("export.zip");
            io.submit(() -> {
                try {
                    job.write(target);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).get(10, TimeUnit.SECONDS);
            var loaded = BackupArchive.read(target);
            assertEquals(1, copied.get());
            assertEquals(.31, loaded.settings().get("volume").getAsDouble());
            assertEquals("key.keyboard.k", loaded.settings().getAsJsonObject("shortcuts").get("key.darkmusic.open").getAsString());
            assertEquals("Captured", loaded.playlists().getFirst().name());
            assertEquals(records, loaded.history());
            assertEquals(.8, ConfigStore.JSON.fromJson(store.snapshotJson(), Settings.class).volume);
        }
    }

    @Test
    void aCommittedRestoreReplacesTheImmutableExportPreferences() throws Exception {
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new ConfigStore(directory, io, message -> fail(message));
            var old = new Settings();
            old.volume = .2;
            store.save(old);
            String captured = store.snapshotJson();
            var restored = new Settings();
            restored.volume = .7;
            store.committedSnapshot(ConfigStore.JSON.toJson(restored));
            assertEquals(.2, ConfigStore.JSON.fromJson(captured, Settings.class).volume);
            assertEquals(.7, ConfigStore.JSON.fromJson(store.snapshotJson(), Settings.class).volume);
        }
    }

    @Test
    void oversizedExportFailureDoesNotReplaceAnExistingArchive() throws Exception {
        var settings = new Settings();
        Path archive = directory.resolve("kept.zip");
        var empty = BackupArchive.snapshot(settings, "root", List.of(), Map.of());
        BackupArchive.write(archive, empty);
        var many = new HashMap<String, ListeningHistory.Entry>();
        for (int i = 0; i < 100_000; i++)
            many.put("root:" + "long-".repeat(20) + i + ".opus", new ListeningHistory.Entry(1, 1, 1));
        var job = new BackupArchive.Export(ConfigStore.JSON.toJson(settings), "root", List.of(), Map.of(), () -> many);
        try (var io = Executors.newSingleThreadExecutor()) {
            assertThrows(ExecutionException.class, () -> io.submit(() -> {
                try {
                    job.write(archive);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).get(15, TimeUnit.SECONDS));
        }
        assertEquals(empty, BackupArchive.read(archive));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "Failed exports leave no partial files");
        }
    }
}