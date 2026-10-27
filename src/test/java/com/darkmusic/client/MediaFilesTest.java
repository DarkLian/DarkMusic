package com.darkmusic.client;

import com.darkmusic.client.audio.ExternalAudio;
import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.MetadataReader;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsFiles;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class MediaFilesTest {
    @TempDir
    Path temp;
    private final MediaFiles files = new MediaFiles();
    private final MetadataReader metadata = new MetadataReader();

    @AfterEach
    void restoreTools() {
        ExternalAudio.configure(null);
    }

    private Track track(Path file) throws Exception {
        return metadata.read("root", file.getFileName().toString(), file, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void caseOnlyRenamesPreserveAudioLyricsAndDownloadReferences() throws Exception {
        Path original = Files.copy(TestMusic.featured(), temp.resolve("Original.mp3"));
        Files.writeString(LyricsFiles.sidecar(original), "[00:01.00]Real collection lyric sidecar");
        Files.writeString(temp.resolve(".darkmusic-downloads.json"), "{\"source:id\":\"Original.mp3\"}");
        byte[] bytes = Files.readAllBytes(original);
        var before = track(original);
        var fields = files.fields(before);
        var edited = files.edit(before, fields, new MediaFiles.Fields(fields.title(), fields.artist(), fields.album(), "original", fields.extension()), new ProcessRunner.Token());
        assertEquals("original.mp3", edited.file().getFileName().toString());
        assertArrayEquals(bytes, Files.readAllBytes(edited.file()));
        assertTrue(Files.isRegularFile(LyricsFiles.sidecar(edited.file())));
        assertEquals("original.mp3", com.google.gson.JsonParser.parseString(Files.readString(temp.resolve(".darkmusic-downloads.json"))).getAsJsonObject().get("source:id").getAsString());
        try (var entries = Files.list(temp)) {
            assertTrue(entries.anyMatch(p -> p.getFileName().toString().equals("original.mp3")));
        }
    }

    @Test
    void defaultFavoritesPersistProtectTheirIdentityAndFollowRenamesAcrossPlaylists() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        try {
            var store = new PlaylistStore(temp, io, MediaFilesTest::failWarning);
            store.loaded(List.of());
            Track original = StorageTest.track("root", "old.mp3", temp.resolve("old.mp3"));
            Track edited = StorageTest.track("root", "new.mp3", temp.resolve("new.mp3"));
            String custom = store.create("Evening");
            store.add(custom, original);
            store.toggleFavorite(original);
            assertTrue(store.favorite(original));
            store.rename(PlaylistStore.FAVORITES, "renamed");
            store.delete(PlaylistStore.FAVORITES);
            assertEquals("Favorite Music", store.get(PlaylistStore.FAVORITES).name());
            store.replaceTrack(original, edited);
            assertTrue(store.favorite(edited));
            assertFalse(store.favorite(original));
            assertEquals(List.of(edited), store.resolve(custom, List.of(edited)));
            io.submit(() -> {
            }).get();
            var loaded = new PlaylistStore(temp, io, MediaFilesTest::failWarning);
            loaded.loaded(loaded.read());
            assertTrue(loaded.favorite(edited));
            loaded.toggleFavorite(edited);
            assertFalse(loaded.favorite(edited));
            assertEquals(1, loaded.get(custom).entries().size());
        } finally {
            io.shutdown();
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void failWarning(String warning) {
        fail(warning);
    }

    @Test
    void importsCopyToSelectedFolderDeduplicateSameFileAndNeverOverwriteCollisions() throws Exception {
        Path source = TestMusic.featured();
        byte[] original = Files.readAllBytes(source);
        Path root = Files.createDirectories(temp.resolve("library"));
        Path folder = Files.createDirectories(root.resolve("音樂"));
        Path invalid = Files.writeString(temp.resolve("not-a-song.txt"), "not audio");
        var first = files.importFiles(root, "root", "音樂", List.of(source, invalid), new ProcessRunner.Token());
        assertEquals(1, first.tracks().size());
        assertEquals(1, first.errors().size());
        assertEquals(2, first.entries().size());
        assertTrue(first.entries().getFirst().success());
        assertFalse(first.entries().get(1).success());
        Track imported = first.tracks().getFirst();
        assertEquals(folder, imported.file().getParent());
        assertArrayEquals(original, Files.readAllBytes(imported.file()));
        var second = files.importFiles(root, "root", "音樂", List.of(source), new ProcessRunner.Token());
        assertNotEquals(imported.file(), second.tracks().getFirst().file());
        assertArrayEquals(original, Files.readAllBytes(imported.file()));
        var same = files.importFiles(root, "root", "音樂", List.of(imported.file()), new ProcessRunner.Token());
        assertEquals(imported.file(), same.tracks().getFirst().file());
        for (String unavailable : List.of("removed-folder", "../escape")) {
            var fallback = files.importFiles(root, "root", unavailable, List.of(source), new ProcessRunner.Token());
            assertEquals(root, fallback.tracks().getFirst().file().getParent());
        }
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var paths = Files.list(folder)) {
            assertEquals(2, paths.count());
        }
        Path secondSource = Files.copy(source, temp.resolve("second.mp3"));
        Path bulkRoot = Files.createDirectories(temp.resolve("bulk"));
        var bulk = files.importFiles(bulkRoot, "root", "", List.of(source, secondSource), new ProcessRunner.Token());
        assertEquals(2, bulk.tracks().size());
        assertTrue(bulk.errors().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(secondSource));
        for (Track item : bulk.tracks()) assertArrayEquals(original, Files.readAllBytes(item.file()));
    }

    @Test
    void mp3EditingChangesRealUnicodeTagsKeepsArtworkAndAudioAndRenamesLyricsWithFixedExtension() throws Exception {
        Path source = TestMusic.featured();
        Path copy = Files.copy(source, temp.resolve("before.mp3"));
        byte[] original = Files.readAllBytes(source), pcm = pcm(copy), cover = metadata.artwork(copy);
        Track before = track(copy);
        var tags = files.fields(before);
        Path history = temp.resolve(".darkmusic-downloads.json");
        Files.writeString(history, "{\"youtube:downloaded\":\"before.mp3\",\"youtube:downloaded:original\":\"before.mp3\"}");
        Files.writeString(LyricsFiles.sidecar(copy), "[00:00.00]Real track lyric sidecar");
        var edited = files.edit(before, tags, new MediaFiles.Fields("新しい曲名", "演出者", "專輯", "更新後", ".exe"), new ProcessRunner.Token());
        assertEquals("更新後.mp3", edited.file().getFileName().toString());
        var archive = com.google.gson.JsonParser.parseString(Files.readString(history)).getAsJsonObject();
        assertEquals("更新後.mp3", archive.get("youtube:downloaded").getAsString());
        assertEquals("更新後.mp3", archive.get("youtube:downloaded:original").getAsString());
        assertFalse(Files.exists(copy));
        assertFalse(Files.exists(LyricsFiles.sidecar(copy)));
        assertEquals("[00:00.00]Real track lyric sidecar", Files.readString(LyricsFiles.sidecar(edited.file())));
        assertEquals("新しい曲名", edited.title());
        assertEquals("演出者", edited.artist());
        assertEquals("專輯", edited.album());
        assertArrayEquals(pcm, pcm(edited.file()));
        assertArrayEquals(cover, metadata.artwork(edited.file()));
        var cleared = files.edit(edited, files.fields(edited), new MediaFiles.Fields("", "", "", "更新後", ".mp3"), new ProcessRunner.Token());
        assertEquals("", files.fields(cleared).artist());
        assertEquals("", files.fields(cleared).album());
        assertArrayEquals(pcm, pcm(cleared.file()));
        assertArrayEquals(cover, metadata.artwork(cleared.file()));
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var paths = Files.list(temp)) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".darkmusic-") && !p.equals(history)));
        }
    }

    private static byte[] pcm(Path file) throws Exception {
        try (var stream = PcmDecoder.open(file)) {
            return stream.readNBytes(32768);
        }
    }

    @Test
    void sourceUrlCanBeReadFromExistingEmbeddedMetadataOffThread() throws Exception {
        String ff = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeFalse(ff.isBlank(), "Real FFmpeg integration is opt-in");
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, ff, Path.of(ff).resolveSibling("ffprobe.exe").toString()));
        String url = "https://www.bilibili.com/video/BV1abc123456";
        Path file = temp.resolve("source-url.m4a");
        var result = new ProcessRunner().run(List.of(ff, "-v", "error", "-i", TestMusic.featured().toString(),
                "-t", "2", "-map", "0:a:0", "-c:a", "aac", "-metadata", "comment=" + url, file.toString()),
            temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 4096, line -> {
            });
        assertEquals(0, result.exit(), result.output());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var links = new SourceLinks(worker);
            var track = track(file);
            assertEquals("", links.get(track));
            worker.submit(() -> {
            }).get();
            assertEquals(url, links.get(track));
        }
        var unavailable = new SourceLinks(task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        assertEquals("", unavailable.get(track(file)));
    }

    @Test
    void sourceVideoFollowsImportedAndRenamedRealAudioWithoutChangingOriginal() throws Exception {
        Path source = Files.copy(TestMusic.featured(), temp.resolve("source.mp3"));
        byte[] original = Files.readAllBytes(source);
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        SourceLinks.record(source, url);
        Path root = Files.createDirectory(temp.resolve("imported"));
        var imported = files.importFiles(root, "root", "", List.of(source), new ProcessRunner.Token()).tracks().getFirst();
        assertEquals(url, SourceLinks.read(imported.file()));
        var fields = files.fields(imported);
        var renamed = files.edit(imported, fields, new MediaFiles.Fields(fields.title(), fields.artist(), fields.album(), "renamed", fields.extension()), new ProcessRunner.Token());
        assertEquals(url, SourceLinks.read(renamed.file()));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void invalidNamesCollisionsAndChangedFilesLeaveOriginalUntouched() throws Exception {
        Path copy = Files.copy(TestMusic.featured(), temp.resolve("before.mp3"));
        Track before = track(copy);
        var tags = files.fields(before);
        byte[] bytes = Files.readAllBytes(copy);
        for (String invalid : List.of("../escape", "CON", "", "bad:name", "trailing."))
            assertThrows(Exception.class, () -> files.edit(before, tags, new MediaFiles.Fields(tags.title(), tags.artist(), tags.album(), invalid, tags.extension()), new ProcessRunner.Token()));
        Path collision = Files.copy(copy, temp.resolve("exists.mp3"));
        assertThrows(Exception.class, () -> files.edit(before, tags, new MediaFiles.Fields(tags.title(), tags.artist(), tags.album(), "exists", tags.extension()), new ProcessRunner.Token()));
        Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.fromMillis(before.modified() + 2000));
        assertThrows(Exception.class, () -> files.edit(before, tags, new MediaFiles.Fields("changed", "", "", "before", tags.extension()), new ProcessRunner.Token()));
        assertArrayEquals(bytes, Files.readAllBytes(copy));
        assertArrayEquals(bytes, Files.readAllBytes(collision));
    }

    @Test
    void originalOpusRetainsItsAudioAndEmbeddedCoverDuringLosslessMetadataEdit() throws Exception {
        String ff = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeFalse(ff.isBlank(), "Real FFmpeg integration is opt-in");
        Path tool = Path.of(ff);
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, ff, tool.resolveSibling("ffprobe.exe").toString()));
        Path collection = Path.of("run/config/darkmusic/music");
        Assumptions.assumeTrue(Files.isDirectory(collection), "Private original collection unavailable");
        Path source;
        try (var paths = Files.walk(collection)) {
            source = paths.filter(p -> p.toString().endsWith(".opus")).findFirst().orElseThrow();
        }
        byte[] original = Files.readAllBytes(source);
        Path copy = Files.copy(source, temp.resolve("before.opus"));
        byte[] cover = metadata.artwork(copy);
        assertNotNull(cover, "Use original music with its real cover");
        String audio = audioHash(tool, copy);
        Track before = track(copy);
        Track edited = files.edit(before, files.fields(before), new MediaFiles.Fields("新しい曲名", "演出者", "專輯", "after", ".opus"), new ProcessRunner.Token());
        assertEquals("新しい曲名", edited.title());
        assertEquals("演出者", edited.artist());
        assertEquals("專輯", edited.album());
        assertArrayEquals(cover, metadata.artwork(edited.file()));
        assertEquals(audio, audioHash(tool, edited.file()));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    private String audioHash(Path tool, Path file) throws Exception {
        var result = new ProcessRunner().run(List.of(tool.toString(), "-v", "error", "-i", file.toAbsolutePath().toString(), "-map", "0:a:0", "-c:a", "copy", "-f", "hash", "-hash", "sha256", "pipe:1"), temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 4096, s -> {
        });
        assertEquals(0, result.exit(), result.output());
        return result.output();
    }

    @Test
    void realAudioTagsCanBeEditedAcrossOtherLibraryContainersWithoutChangingAudio() throws Exception {
        String ff = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeFalse(ff.isBlank(), "Real FFmpeg integration is opt-in");
        Path tool = Path.of(ff);
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, ff, tool.resolveSibling("ffprobe.exe").toString()));
        for (String[] format : List.of(new String[]{"flac", "flac"}, new String[]{"libvorbis", "ogg"}, new String[]{"aac", "m4a"}, new String[]{"pcm_s16le", "wav"}, new String[]{"pcm_s16be", "aiff"}, new String[]{"libopus", "webm"})) {
            Path file = temp.resolve("real-clip." + format[1]);
            var converted = new ProcessRunner().run(List.of(ff, "-v", "error", "-i", TestMusic.featured().toString(), "-t", "4", "-map", "0:a:0", "-c:a", format[0], "-metadata", "title=Original title", "-metadata", "artist=Original artist", "-metadata", "album=Original album", file.toString()), temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 4096, line -> {
            });
            assertEquals(0, converted.exit(), converted.output());
            String hash = audioHash(tool, file);
            Track before = track(file);
            var fields = files.fields(before);
            Track edited = files.edit(before, fields, new MediaFiles.Fields("新曲", "歌手", "專輯", fields.filename(), fields.extension()), new ProcessRunner.Token());
            assertEquals("新曲", files.fields(edited).title(), format[1]);
            assertEquals("歌手", files.fields(edited).artist(), format[1]);
            assertEquals("專輯", files.fields(edited).album(), format[1]);
            assertEquals(hash, audioHash(tool, edited.file()), format[1]);
        }
    }
}
