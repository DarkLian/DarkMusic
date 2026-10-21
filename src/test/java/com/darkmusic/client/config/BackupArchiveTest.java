package com.darkmusic.client.config;

import com.darkmusic.client.library.*;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class BackupArchiveTest {
    @TempDir
    Path directory;

    private BackupArchive.Data data() {
        var s = new Settings();
        s.volume = .4;
        s.musicDirectory = "private/source";
        s.ffmpegPath = "private/tools";
        s.roots.put("private/source", "source");
        s.shortcuts.put("key.darkmusic.open", "key.keyboard.k");
        return BackupArchive.snapshot(s, "source", List.of(new PlaylistStore.Playlist("list", "旅行", List.of(new PlaylistStore.Entry("source", "曲/song.opus"), new PlaylistStore.Entry("source", "missing.opus")))), Map.of("source:曲/song.opus", new ListeningHistory.Entry(3, 100, 60)));
    }

    @Test
    void archiveRoundTripContainsOnlyPortableData() throws Exception {
        Path archive = directory.resolve("backup.zip");
        BackupArchive.write(archive, data());
        var read = BackupArchive.read(archive);
        assertEquals(data(), read);
        assertFalse(read.settings().has("musicDirectory"));
        assertFalse(read.settings().has("roots"));
        assertFalse(read.settings().has("ffmpegPath"));
        assertFalse(read.settings().has("outputDevice"));
        try (var zip = new ZipFile(archive.toFile())) {
            assertEquals(1, zip.size());
            assertNotNull(zip.getEntry("setup.json"));
        }
    }

    @Test
    void remappingMergingAndReplacingPreserveLocalPathsAndUnmatchedReferences() {
        var local = new Settings();
        local.musicDirectory = "destination";
        local.ffmpegPath = "installed";
        local.volume = .9;
        local.outputDevice = "a".repeat(64);
        local.shortcuts.put("key.darkmusic.open", "key.keyboard.b");
        var available = new Track("destination", "曲/song.opus", directory.resolve("unused"), "", "", "", 60, 1, 1, "");
        var original = List.of(new PlaylistStore.Playlist("existing", "旅行", List.of(new PlaylistStore.Entry("destination", "existing.opus"))));
        var restored = BackupArchive.prepare(data(), new BackupArchive.Selection(true, true, false, true, false, true), local, "destination", List.of(available), original, Map.of(), Set.of());
        assertEquals("destination", restored.settings().musicDirectory);
        assertEquals("installed", restored.settings().ffmpegPath);
        assertEquals(local.outputDevice, restored.settings().outputDevice);
        assertEquals(.4, restored.settings().volume);
        assertEquals(local.shortcuts, restored.settings().shortcuts);
        assertEquals(1, restored.playlists().size());
        assertEquals("existing", restored.playlists().getFirst().id());
        assertEquals(3, restored.playlists().getFirst().entries().size());
        assertEquals(Set.of(new PlaylistStore.Entry("destination", "missing.opus")), restored.retained());
        assertEquals(1, restored.unresolved());
        assertEquals(3, restored.history().get(available.id()).plays());
        var replacement = BackupArchive.prepare(data(), new BackupArchive.Selection(false, true, true, false, true, false), local, "destination", List.of(), original, Map.of(), Set.of());
        assertEquals(.9, replacement.settings().volume);
        assertEquals("key.keyboard.k", replacement.settings().shortcuts.get("key.darkmusic.open"));
        assertEquals("list", replacement.playlists().getFirst().id());
        assertEquals("source", replacement.playlists().getFirst().entries().getFirst().rootId());
    }

    @Test
    void importingTheSameStatisticsTwiceDoesNotInflateCounts() {
        var local = new Settings();
        var select = new BackupArchive.Selection(false, false, false, true, false, true);
        var first = BackupArchive.prepare(data(), select, local, "local", List.of(), List.of(), Map.of(), Set.of());
        var again = BackupArchive.prepare(data(), select, local, "local", List.of(), List.of(), first.history(), Set.of());
        assertEquals(first.history(), again.history());
    }

    @Test
    void rejectsTraversalExtraEntriesOversizedPayloadsAndUnknownVersions() throws Exception {
        for (String entry : List.of("../config.json", "folder/setup.json")) {
            Path file = directory.resolve(UUID.randomUUID() + ".zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("{}".getBytes());
            }
            assertThrows(java.io.IOException.class, () -> BackupArchive.read(file));
        }
        Path extra = directory.resolve("extra.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(extra))) {
            zip.putNextEntry(new ZipEntry("setup.json"));
            zip.write(ConfigStore.JSON.toJson(data()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("other"));
        }
        assertThrows(java.io.IOException.class, () -> BackupArchive.read(extra));
        Path bomb = directory.resolve("oversized.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(bomb))) {
            zip.putNextEntry(new ZipEntry("setup.json"));
            zip.write(new byte[8 * 1024 * 1024 + 1]);
        }
        assertThrows(java.io.IOException.class, () -> BackupArchive.read(bomb));
        Path future = directory.resolve("future.zip");
        var d = data();
        BackupArchive.write(future, new BackupArchive.Data(99, d.root(), d.settings(), d.playlists(), d.history()));
        assertThrows(java.io.IOException.class, () -> BackupArchive.read(future));
    }

    @Test
    void aFailedRestoreRollsBackEveryAffectedFileAndKeepsDurableCopies() throws Exception {
        Files.writeString(directory.resolve("config.json"), "original settings");
        Files.writeString(directory.resolve("playlists.json"), "original playlists");
        var contents = new LinkedHashMap<String, String>();
        contents.put("config.json", "replacement");
        contents.put("playlists.json", "replacement");
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.IOException.class, () -> BackupArchive.install(directory, contents, (path, value) -> {
            if (writes.incrementAndGet() == 2) throw new java.io.IOException("Injected full disk");
            ConfigStore.atomicWrite(path, value);
        }));
        assertEquals("original settings", Files.readString(directory.resolve("config.json")));
        assertEquals("original playlists", Files.readString(directory.resolve("playlists.json")));
        try (var files = Files.walk(directory.resolve("backups"))) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }
        assertThrows(java.io.IOException.class, () -> BackupArchive.install(directory, Map.of("../other", "bad")));
    }

    @Test
    void unmatchedImportsSurviveCleanupAndRestartUntilTheyAppear() throws Exception {
        try (var io = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var entry = new PlaylistStore.Entry("root", "later.opus");
            var store = new PlaylistStore(directory, io, s -> fail(s));
            store.restore(List.of(new PlaylistStore.Playlist("id", "Imported", List.of(entry))), Set.of(entry));
            store.removeMissing(Set.of(entry));
            assertEquals(1, store.get("id").entries().size());
            io.submit(() -> {
            }).get();
            var restarted = new PlaylistStore(directory, io, s -> fail(s));
            restarted.loaded(restarted.read());
            restarted.removeMissing(Set.of(entry));
            assertEquals(1, restarted.get("id").entries().size());
            restarted.acknowledgeTracks(List.of(new Track("root", "later.opus", directory.resolve("unused"), "", "", "", 10, 1, 1, "")));
            restarted.removeMissing(Set.of(entry));
            assertTrue(restarted.get("id").entries().isEmpty());
        }
    }
}
