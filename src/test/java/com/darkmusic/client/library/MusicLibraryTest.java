package com.darkmusic.client.library;

import com.darkmusic.client.media.AudioFormats;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MusicLibraryTest {
    @TempDir
    Path temp;

    @Test
    void failedAndUnchangedScansPreserveExistingMetadataCache() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path song = root.resolve("song.mp3");
        Files.copy(TestMusic.featured(), song);
        Path data = temp.resolve("data");
        Path cache = data.resolve("library-cache.json");
        try (var library = new MusicLibrary(data)) {
            library.setRoot(root, "root");
            Await.of(Duration.ofSeconds(15), () -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            String original = Files.readString(cache);
            var modified = Files.getLastModifiedTime(cache);
            long revision = library.snapshot().revision();
            library.rescan();
            Await.of(Duration.ofSeconds(15), () -> !library.snapshot().scanning() && library.snapshot().revision() > revision);
            assertEquals(modified, Files.getLastModifiedTime(cache));
            Path detached = root.resolveSibling("detached");
            Files.move(root, detached);
            library.rescan();
            Await.of(Duration.ofSeconds(15), () -> !library.snapshot().scanning() && library.snapshot().message().contains("unavailable"));
            assertEquals(original, Files.readString(cache));
            Files.move(detached, root);
            library.rescan();
            Await.of(Duration.ofSeconds(15), () -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            assertEquals(original, Files.readString(cache));
            Files.delete(song);
            library.rescan();
            Await.of(Duration.ofSeconds(15), () -> !library.snapshot().scanning() && library.snapshot().tracks().isEmpty());
            assertFalse(Files.readString(cache).contains("song.mp3"));
        }
    }

    @Test
    void internalWatcherEventsAreIgnoredButSupportedHiddenSongsAreDiscovered() throws Exception {
        Path root = Files.createDirectories(temp.resolve("music"));
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            library.setRoot(root, "root");
            Await.of(Duration.ofSeconds(15), () -> !library.snapshot().scanning());
            long revision = library.snapshot().revision();
            Files.writeString(root.resolve(".darkmusic-downloads.json"), "{}");
            Files.writeString(root.resolve(".darkmusic-incomplete.tmp"), "partial");
            Files.writeString(root.resolve("notes.txt"), "unrelated");
            library.watcher().poll();
            assertEquals(revision, library.snapshot().revision());
            Files.copy(TestMusic.featured(), root.resolve(".darkmusic-song.mp3"));
            library.watcher().poll();
            Await.of(Duration.ofSeconds(15), () -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
        }
    }

    @Test
    void unsupportedImageBrandsAndVideoExtensionsDoNotBecomeLibrarySongs() throws Exception {
        for (String brand : List.of("heic", "avif", "mif1", "qt  ")) {
            Path file = Files.write(temp.resolve(brand.strip() + ".bin"), ByteBuffer.allocate(24).putInt(24).put("ftyp".getBytes()).put(brand.getBytes()).array());
            assertEquals(AudioFormats.Container.UNKNOWN, AudioFormats.detect(file));
            assertFalse(MusicLibrary.supported(file));
        }
        assertFalse(MusicLibrary.supported(temp.resolve("video.mkv")));
        assertFalse(MusicLibrary.supported(temp.resolve("video.mov")));
    }
}
