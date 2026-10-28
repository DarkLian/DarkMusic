package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir
    Path temp;

    static Track track(String root, String path, Path file) {
        return new Track(root, path, file, path, "Artist", "Album", 1, 0, 0, "");
    }

    static void writeWav(Path path) throws Exception {
        try (var stream = TestMusic.clip().stream()) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    static void await(BooleanSupplier condition) throws Exception {
        Await.until(Duration.ofSeconds(12), condition, "asynchronous operation");
    }

    @Test
    void configurationRoundTripAndCorruptionRecovery() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        try {
            List<String> warnings = new CopyOnWriteArrayList<>();
            var store = new ConfigStore(temp, io, warnings::add);
            var settings = io.submit(store::load).get();
            assertTrue(Files.isDirectory(temp.resolve("music")));
            settings.volume = .32;
            settings.musicDirectory = temp.resolve("音樂").toString();
            store.save(settings);
            io.submit(() -> {
            }).get();
            var loaded = io.submit(store::load).get();
            assertEquals(.32, loaded.volume);
            assertEquals(settings.musicDirectory, loaded.musicDirectory);
            Files.writeString(temp.resolve("config.json"), "{broken-json");
            loaded = io.submit(store::load).get();
            assertFalse(warnings.isEmpty());
            assertEquals("{broken-json", Files.readString(temp.resolve("config.json")));
            store.save(loaded);
            io.submit(() -> {
            }).get();
            try (var files = Files.list(temp)) {
                assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("config.invalid-")));
            }
            assertNotNull(ConfigStore.JSON.fromJson(Files.readString(temp.resolve("config.json")), Settings.class));
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void atomicReplacementAndSettingsValidation() throws Exception {
        Path file = temp.resolve("a/data.json");
        ConfigStore.atomicWrite(file, "first");
        ConfigStore.atomicWrite(file, "second");
        assertEquals("second", Files.readString(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
        var settings = new Settings();
        settings.volume = Double.NaN;
        settings.repeat = null;
        settings.hudPosition = "bad";
        settings.validate();
        assertEquals(1, settings.volume);
        assertEquals(Repeat.OFF, settings.repeat);
        assertEquals("TOP_LEFT", settings.hudPosition);
    }

    @Test
    void coalescedSavesKeepTheLatestSettingsAndShortcutsAtShutdown() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        var store = new ConfigStore(temp, io, message -> fail(message));
        var settings = io.submit(store::load).get();
        for (int i = 0; i < 1000; i++) {
            settings.volume = i / 1000.0;
            settings.shortcuts.put("key.darkmusic.next", "key.keyboard.n");
            store.save(settings);
        }
        io.shutdown();
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
        var loaded = ConfigStore.JSON.fromJson(Files.readString(temp.resolve("config.json")), Settings.class);
        assertEquals(.999, loaded.volume);
        assertEquals("key.keyboard.n", loaded.shortcuts.get("key.darkmusic.next"));
    }

    @Test
    void playlistsPersistOrderingAndPreserveMissingReferences() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        try {
            var store = new PlaylistStore(temp, io, fail -> fail(fail));
            store.loaded(io.submit(store::read).get());
            Track a = track("root-a", "folder/a.mp3", temp.resolve("a.mp3")), b = track("root-a", "b.flac", temp.resolve("b.flac"));
            String id = store.create("Favorites");
            store.add(id, a);
            store.add(id, b);
            store.add(id, a);
            assertEquals(2, store.get(id).entries().size());
            store.move(id, 1, -1);
            store.rename(id, "Evening");
            io.submit(() -> {
            }).get();
            var other = new PlaylistStore(temp, io, fail -> fail(fail));
            other.loaded(io.submit(other::read).get());
            assertEquals("Evening", other.get(id).name());
            assertEquals("b.flac", other.get(id).entries().getFirst().path());
            assertEquals(List.of(b), other.resolve(id, List.of(b)));
            assertEquals(2, other.get(id).entries().size());
            assertTrue(other.resolve(id, List.of(track("different-root", "b.flac", b.file()))).isEmpty());
            other.remove(id, 1);
            other.add(id, track("root-a", "renamed.mp3", a.file()));
            other.remove(id, 0);
            assertEquals("renamed.mp3", other.get(id).entries().getFirst().path());
            other.delete(id);
            assertEquals(List.of(PlaylistStore.FAVORITES), other.all().stream().map(PlaylistStore.Playlist::id).toList());
            io.submit(() -> {
            }).get();
            assertThrows(IllegalArgumentException.class, () -> new PlaylistStore.Entry("root", "../escape.mp3"));
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void recursiveScanDetectsAddsRemovalsAndUnavailableDirectoryWithoutWritingAudio() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library/子目錄"));
        Path wav = root.resolve("音樂.wav");
        writeWav(wav);
        byte[] original = Files.readAllBytes(wav);
        assertTrue(wav.toFile().setReadOnly());
        try (var library = new MusicLibrary(temp.resolve("instance"))) {
            library.setRoot(root.getParent(), "test-root");
            await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            var track = library.snapshot().tracks().getFirst();
            assertEquals("子目錄/音樂.wav", track.relativePath());
            assertFalse(track.title().isBlank());
            assertTrue(track.duration() > 0);
            assertArrayEquals(original, Files.readAllBytes(wav));
            Path added = root.resolve("added.wav");
            writeWav(added);
            await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 2);
            Files.delete(added);
            await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            library.setRoot(temp.resolve("missing"), "other-root");
            await(() -> !library.snapshot().scanning());
            assertTrue(library.snapshot().tracks().isEmpty());
            assertTrue(library.snapshot().message().contains("unavailable"));
        } finally {
            wav.toFile().setWritable(true);
        }
    }

    @Test
    void scansRealCollectionAndCancelsAnObsoleteRoot() throws Exception {
        Path root = TestMusic.root();
        int count = TestMusic.files().size();
        Path other = Files.createDirectories(temp.resolve("other"));
        Files.copy(TestMusic.featured(), other.resolve("only.mp3"));
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            library.setRoot(root, "large-root");
            await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == count);
            assertTrue(library.snapshot().tracks().stream().allMatch(t -> t.rootId().equals("large-root")));
            library.rescan();
            library.setRoot(other, "other-root");
            await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            assertEquals("other-root", library.snapshot().tracks().getFirst().rootId());
        }
    }

    @Test
    void deletingSignatureDetectedExtensionlessAudioTriggersWatcherRescan() throws Exception {
        Path root = Files.createDirectories(temp.resolve("extensionless"));
        Path file = Files.copy(TestMusic.featured(), root.resolve("song_without_extension"));
        try (var library = new MusicLibrary(temp.resolve("extensionless-data"))) {
            library.setRoot(root, "signature-root");
            await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            long revision = library.snapshot().revision();
            Files.delete(file);
            await(() -> library.snapshot().revision() > revision && !library.snapshot().scanning() && library.snapshot().tracks().isEmpty());
        }
    }
}
