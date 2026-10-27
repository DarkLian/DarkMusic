package com.darkmusic.client.library;

import com.darkmusic.client.audio.AudioFormats;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReviewReliabilityTest {
    @TempDir
    Path temp;

    @Test
    void controlCharactersCannotReachTheMediaTransaction() {
        var track = new Track("r", "file.mp3", temp.resolve("missing.mp3"), "T", "A", "", 1, 0, 0, "");
        var fields = new MediaFiles.Fields("T", "A", "", "file", ".mp3");
        for (String name : List.of("a\r\nb", "a\nb\nc", "x\u0085y\u0085z")) {
            var error = assertThrows(java.io.IOException.class, () -> new MediaFiles().edit(track, fields, new MediaFiles.Fields("T", "A", "", name, ".mp3"), new ProcessRunner.Token()));
            assertEquals(Text.get("library.darkmusic.invalid_filename"), error.getMessage());
        }
    }

    @Test
    void normalizedSearchAndIdsAreCachedWithoutChangingPersistedMetadata() {
        var track = new Track("root", "Ｆｏｌｄｅｒ/song.mp3", null, "Beyoncé ガ", "Artist", "Album", 5, 10, 20, "");
        assertTrue(track.matches("BEYONCE"));
        assertTrue(track.matches("folder"));
        assertTrue(track.matches("ガ"));
        assertFalse(track.matches("カ"));
        assertSame(track.id(), track.id());
        String json = ConfigStore.JSON.toJson(track);
        assertFalse(json.contains("searchKey"));
        assertFalse(json.contains("cachedId"));
        assertEquals(track, ConfigStore.JSON.fromJson(json, Track.class));
        assertTrue(ConfigStore.JSON.fromJson(json, Track.class).matches("beyonce"));
    }

    @Test
    void importedPermissionsPreservePrivacyAndDownloadsFollowOrdinaryCreation() throws Exception {
        Path normal = Files.createFile(temp.resolve("normal"));
        Path incoming = LibraryFiles.incoming(temp, ".darkmusic-");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileAttributeView(normal, java.nio.file.attribute.PosixFileAttributeView.class) != null);
        assertEquals(Files.getPosixFilePermissions(normal), Files.getPosixFilePermissions(incoming));
        Files.setPosixFilePermissions(normal, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        LibraryFiles.preservePermissions(normal, incoming);
        assertEquals(Files.getPosixFilePermissions(normal), Files.getPosixFilePermissions(incoming));
    }

    @Test
    void unsupportedImageBrandsAndVideoExtensionsDoNotBecomeLibrarySongs() throws Exception {
        for (String brand : List.of("heic", "avif", "mif1", "qt  ")) {
            Path file = Files.write(temp.resolve(brand.strip() + ".bin"), java.nio.ByteBuffer.allocate(24).putInt(24).put("ftyp".getBytes()).put(brand.getBytes()).array());
            assertEquals(AudioFormats.Container.UNKNOWN, AudioFormats.detect(file));
            assertFalse(MusicLibrary.supported(file));
        }
        assertFalse(MusicLibrary.supported(temp.resolve("video.mkv")));
        assertFalse(MusicLibrary.supported(temp.resolve("video.mov")));
    }
}
