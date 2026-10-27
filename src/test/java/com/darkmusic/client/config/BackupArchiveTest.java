package com.darkmusic.client.config;

import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.library.*;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class BackupArchiveTest {
    @Test
    void recordConstructorsRejectMalformedPlaylistFieldsDuringRead() throws Exception {
        var entry = new PlaylistStore.Entry("library", "song.opus");
        var valid = BackupArchive.snapshot(new Settings(), "library", List.of(new PlaylistStore.Playlist("p", "List", List.of(entry))), Map.of());
        for (String field : List.of("id", "name", "entries", "rootId", "path")) {
            var json = ConfigStore.JSON.toJsonTree(valid).getAsJsonObject();
            var playlist = json.getAsJsonArray("playlists").get(0).getAsJsonObject();
            if (field.equals("rootId") || field.equals("path"))
                playlist.getAsJsonArray("entries").get(0).getAsJsonObject().add(field, com.google.gson.JsonNull.INSTANCE);
            else playlist.add(field, com.google.gson.JsonNull.INSTANCE);
            Path file = directory.resolve("malformed-" + field + ".zip");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("setup.json"));
                zip.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            assertThrows(java.io.IOException.class, () -> BackupArchive.read(file), field);
        }
    }

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
        assertThrows(java.io.IOException.class, () -> RestoreJournal.install(directory, contents, (path, value) -> {
            if (writes.incrementAndGet() == 2) throw new java.io.IOException("Injected full disk");
            ConfigStore.atomicWrite(path, value);
        }));
        assertEquals("original settings", Files.readString(directory.resolve("config.json")));
        assertEquals("original playlists", Files.readString(directory.resolve("playlists.json")));
        try (var files = Files.walk(directory.resolve("backups"))) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }
        assertThrows(java.io.IOException.class, () -> RestoreJournal.install(directory, Map.of("../other", "bad")));
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

    @Test
    void largeRemappedImportsDeduplicateInOriginalOrder() {
        var entries = new ArrayList<PlaylistStore.Entry>();
        for (int i = 0; i < 50_000; i++) entries.add(new PlaylistStore.Entry("source", i + ".opus"));
        for (int i = 0; i < 50_000; i++) entries.add(new PlaylistStore.Entry("local", i + ".opus"));
        var input = new BackupArchive.Data(1, "source", data().settings(),
            List.of(new PlaylistStore.Playlist("large", "Large", entries)), Map.of());
        long started = System.nanoTime();
        var restored = assertTimeout(java.time.Duration.ofSeconds(10), () -> BackupArchive.prepare(input,
            new BackupArchive.Selection(false, true, false, false, true, true), new Settings(), "local", List.of(), List.of(), Map.of(), Set.of()));
        System.out.println("BACKUP_100K_PREPARE_MS=" + (System.nanoTime() - started) / 1_000_000);
        var result = restored.playlists().getFirst().entries();
        assertEquals(50_000, result.size());
        for (int i = 0; i < result.size(); i++)
            assertEquals(new PlaylistStore.Entry("local", i + ".opus"), result.get(i));
    }

    @Test
    void everyWriteFailureRestoresAllFilesIncludingNativeShortcuts() throws Exception {
        for (int failAt = 1; failAt <= 5; failAt++) {
            Path root = directory.resolve("case" + failAt + "/config/darkmusic");
            Files.createDirectories(root);
            Path options = root.getParent().getParent().resolve("options.txt");
            var replacement = new LinkedHashMap<String, String>();
            replacement.put("config.json", "new settings");
            replacement.put("playlists.json", "new playlists");
            replacement.put("playlist-retained.json", "new retained");
            replacement.put("listening-history.json", "new history");
            replacement.put("options.txt", "new shortcuts");
            for (String name : replacement.keySet())
                Files.writeString(name.equals("options.txt") ? options : root.resolve(name), "original " + name);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            int selected = failAt;
            assertThrows(java.io.IOException.class, () -> RestoreJournal.install(root, replacement, (path, value) -> {
                if (count.incrementAndGet() == selected) {
                    if (selected % 2 == 0) throw new AccessDeniedException(path.toString());
                    throw new FileSystemException(path.toString(), null, "No space left on device");
                }
                ConfigStore.atomicWrite(path, value);
            }));
            for (String name : replacement.keySet())
                assertEquals("original " + name,
                    Files.readString(name.equals("options.txt") ? options : root.resolve(name)));
            assertFalse(RestoreJournal.recoveryPending(root));
        }
    }

    @Test
    void interruptedRestoreRecoversBeforeConfigAndOtherStoresLoadAfterRestart() throws Exception {
        Path root = directory.resolve("restart/config/darkmusic");
        Files.createDirectories(root);
        Path options = root.getParent().getParent().resolve("options.txt");
        var original = new Settings();
        original.volume = .37;
        Files.writeString(root.resolve("config.json"), ConfigStore.JSON.toJson(original));
        Files.writeString(root.resolve("playlists.json"), ConfigStore.JSON.toJson(List.of(new PlaylistStore.Playlist("kept", "Kept", List.of()))));
        Files.writeString(options, "key_key.darkmusic.open:key.keyboard.k\n");
        var update = new LinkedHashMap<String, String>();
        update.put("config.json", ConfigStore.JSON.toJson(new Settings()));
        update.put("playlists.json", "[]");
        update.put("listening-history.json", "newly created");
        update.put("options.txt", "changed");
        var count = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(AssertionError.class, () -> RestoreJournal.install(root, update, (path, value) -> {
            ConfigStore.atomicWrite(path, value);
            if (count.incrementAndGet() == 4) throw new AssertionError("Simulated process termination");
        }));
        assertTrue(RestoreJournal.recoveryPending(root));
        try (var io = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var warnings = new ArrayList<String>();
            var config = new ConfigStore(root, io, warnings::add);
            var loaded = config.load();
            assertEquals(.37, loaded.volume);
            assertEquals("key.keyboard.k", loaded.shortcuts.get("key.darkmusic.open"));
            assertTrue(loaded.nativeShortcutNames.isEmpty(), "Recovered bindings must replace Minecraft's pre-recovery in-memory values");
            var store = new PlaylistStore(root, io, warnings::add);
            assertEquals("kept", store.read().getFirst().id());
        }
        assertEquals("key_key.darkmusic.open:key.keyboard.k\n", Files.readString(options));
        assertFalse(Files.exists(root.resolve("listening-history.json")));
        assertFalse(RestoreJournal.recoveryPending(root));
        assertFalse(RestoreJournal.recover(root), "Recovery is idempotent");
    }

    @Test
    void malformedArchivesAndRecoveryJournalsNeverModifyExistingData() throws Exception {
        Path archive = directory.resolve("corrupt.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("setup.json"));
            zip.write("{\"version\":".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThrows(java.io.IOException.class, () -> BackupArchive.read(archive));
        Files.write(archive, new byte[]{80, 75, 3, 4, 0});
        assertThrows(java.io.IOException.class, () -> BackupArchive.read(archive));
        Files.writeString(directory.resolve("config.json"), "keep");
        Files.writeString(directory.resolve("restore-pending.json"),
            "{\"version\":1,\"backup\":\"../../outside\",\"existed\":{\"config.json\":true}}");
        assertThrows(java.io.IOException.class, () -> RestoreJournal.recover(directory));
        assertEquals("keep", Files.readString(directory.resolve("config.json")));
        assertTrue(RestoreJournal.recoveryPending(directory));
    }

    @Test
    void failedRollbackKeepsItsJournalAndBlocksLoadingUntilRecoveryCanComplete() throws Exception {
        Path root = directory.resolve("blocked/config/darkmusic");
        Files.createDirectories(root);
        var settings = new Settings();
        settings.volume = .42;
        String original = ConfigStore.JSON.toJson(settings);
        Files.writeString(root.resolve("config.json"), original);
        Files.writeString(root.resolve("playlists.json"), "[]");
        var replacements = new LinkedHashMap<String, String>();
        replacements.put("config.json", "changed");
        replacements.put("playlists.json", "changed");
        var failure = assertThrows(java.io.IOException.class, () -> RestoreJournal.install(root, replacements, (path, value) -> {
            if (path.getFileName().toString().equals("playlists.json")) {
                Files.delete(path);
                Files.createDirectory(path);
                throw new AccessDeniedException(path.toString());
            }
            ConfigStore.atomicWrite(path, value);
        }));
        assertTrue(failure.getSuppressed().length > 0);
        assertTrue(RestoreJournal.recoveryPending(root));
        try (var io = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var store = new ConfigStore(root, io, message -> fail("Partial user data must not be loaded"));
            assertThrows(java.util.concurrent.CompletionException.class, store::load);
            Files.delete(root.resolve("playlists.json"));
            assertTrue(RestoreJournal.recover(root));
            assertEquals(.42, store.load().volume);
        }
        assertEquals(original, Files.readString(root.resolve("config.json")));
        assertEquals("[]", Files.readString(root.resolve("playlists.json")));
        assertFalse(RestoreJournal.recoveryPending(root));
    }

    @Test
    void publishingCommittedPlaylistsDoesNotQueueAnotherDiskWrite() {
        var io = java.util.concurrent.Executors.newSingleThreadExecutor();
        io.shutdown(); // Any hidden serialization/write submission would fail this test.
        var entry = new PlaylistStore.Entry("library", "favorite.opus");
        var values = List.of(new PlaylistStore.Playlist(PlaylistStore.FAVORITES, "Favorite Music", List.of(entry)));
        var store = new PlaylistStore(directory, io, message -> fail(message));
        store.restoredFromDisk(values, Set.of(entry));
        assertEquals(values, store.all());
        assertEquals(Set.of(entry), store.retainedImports());
        assertFalse(Files.exists(directory.resolve("playlists.json")));
    }

    @Test
    void interruptedRecoveryPreservesExternalChangesAndReportsConflict() throws Exception {
        Path root = directory.resolve("conflict/config/darkmusic");
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.json"), "original config");
        var update = new LinkedHashMap<String, String>();
        update.put("config.json", "transaction config");
        update.put("playlists.json", "transaction playlists");
        assertThrows(AssertionError.class, () -> RestoreJournal.install(root, update, (path, value) -> {
            ConfigStore.atomicWrite(path, value);
            if (path.getFileName().toString().equals("playlists.json"))
                throw new AssertionError("Simulated termination");
        }));
        Files.writeString(root.resolve("config.json"), "external newer config");
        Files.writeString(root.resolve("playlists.json"), "external newly created playlists");
        assertThrows(java.io.IOException.class, () -> RestoreJournal.recover(root));
        assertEquals("external newer config", Files.readString(root.resolve("config.json")));
        assertEquals("external newly created playlists", Files.readString(root.resolve("playlists.json")));
        assertTrue(RestoreJournal.recoveryPending(root));
        try (var paths = Files.walk(root.resolve("backups"))) {
            assertTrue(paths.filter(Files::isRegularFile).anyMatch(p -> {
                try {
                    return Files.readString(p).equals("external newer config");
                } catch (java.io.IOException e) {
                    return false;
                }
            }));
        }
        // An explicit user resolution to a recognized state permits recovery to resume.
        Files.writeString(root.resolve("config.json"), "transaction config");
        Files.writeString(root.resolve("playlists.json"), "transaction playlists");
        assertTrue(RestoreJournal.recover(root));
        assertEquals("original config", Files.readString(root.resolve("config.json")));
        assertFalse(Files.exists(root.resolve("playlists.json")));
    }

    @Test
    void legacyJournalDoesNotAuthorizeOverwritingUnknownContent() throws Exception {
        String id = UUID.randomUUID().toString();
        Path rollback = Files.createDirectories(directory.resolve("backups").resolve(id));
        Files.writeString(rollback.resolve("config.json"), "original");
        Files.writeString(directory.resolve("config.json"), "unrecognized");
        Files.writeString(directory.resolve("restore-pending.json"),
            "{\"version\":1,\"backup\":\"" + id + "\",\"existed\":{\"config.json\":true}}");
        assertThrows(java.io.IOException.class, () -> RestoreJournal.recover(directory));
        assertEquals("unrecognized", Files.readString(directory.resolve("config.json")));
    }

    @Test
    void recoveryResumesAfterClaimingARecognizedReplacement() throws Exception {
        Files.writeString(directory.resolve("config.json"), "original");
        assertThrows(AssertionError.class, () -> RestoreJournal.install(directory, Map.of("config.json", "replacement"), (path, value) -> {
            ConfigStore.atomicWrite(path, value);
            throw new AssertionError("Interrupted");
        }));
        var journal = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("restore-pending.json"))).getAsJsonObject();
        Path originals = directory.resolve("backups").resolve(journal.get("backup").getAsString());
        Files.move(directory.resolve("config.json"), originals.resolve("recover-config.json"));
        assertTrue(RestoreJournal.recover(directory));
        assertEquals("original", Files.readString(directory.resolve("config.json")));
        assertFalse(Files.exists(originals.resolve("recover-config.json")));
        assertFalse(RestoreJournal.recoveryPending(directory));
    }

}
