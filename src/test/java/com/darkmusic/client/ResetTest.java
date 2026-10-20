package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.ResetStorage;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ResetTest {
    @TempDir
    Path temp;

    private String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test
    void restoresEveryPreferenceButRetainsIndependentPlaylistRootIdentities() throws Exception {
        var old = new Settings();
        old.musicDirectory = "custom";
        old.volume = 0;
        old.lastAudibleVolume = .2;
        old.speed = 3;
        old.autoplay = old.shuffle = old.hudEnabled = true;
        old.hudArtwork = old.hudProgress = old.hudVisualizer = false;
        old.hudScale = 2.3;
        old.hudX = .4;
        old.hudY = .8;
        old.visualizerBars = 64;
        old.visualizerSensitivity = 4;
        old.visualizerSmoothing = .95;
        old.hudBackgroundTransparency = 1;
        old.spectrumDirection = Settings.SpectrumDirection.DOWNWARD;
        old.spectrumPosition = Settings.SpectrumPosition.ABOVE;
        old.repeat = com.darkmusic.client.audio.PlaybackController.Repeat.ALL;
        old.ytDlpPath = old.ffmpegPath = old.ffprobePath = old.javascriptPath = "custom";
        old.shortcuts.put("key.darkmusic.next", "key.keyboard.n");
        old.roots.put("old music", "stable-playlist-id");
        var reset = Settings.defaults(old);
        var expected = new Settings();
        expected.validate();
        expected.roots.putAll(old.roots);
        expected.ytDlpPath = expected.ffmpegPath = expected.ffprobePath = expected.javascriptPath = "custom";
        assertEquals(ConfigStore.JSON.toJson(expected), ConfigStore.JSON.toJson(reset));
        reset.roots.put("new music", "new-id");
        assertFalse(old.roots.containsKey("new music"));
        var io = Executors.newSingleThreadExecutor();
        try {
            var store = new ConfigStore(temp, io, message -> fail(message));
            store.save(reset);
            io.submit(() -> {
            }).get();
            var loaded = io.submit(store::load).get();
            assertEquals(ConfigStore.JSON.toJson(reset), ConfigStore.JSON.toJson(loaded));
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void shortcutPersistenceChangesOnlyDarkMusicLinesWithoutBroadcastingOptions() throws Exception {
        Path game = Files.createDirectories(temp.resolve("game"));
        Path data = Files.createDirectories(game.resolve("config/darkmusic"));
        Path options = game.resolve("options.txt");
        Files.writeString(options, "guiScale:3\nkey_key.jump:key.keyboard.space\nkey_key.darkmusic.next:key.keyboard.n\nkey_key.darkmusic.next:key.keyboard.x\nlanguage:zh_tw\n");
        var io = Executors.newSingleThreadExecutor();
        try {
            var config = new ConfigStore(data, io, message -> fail(message));
            var bindings = new LinkedHashMap<String, String>();
            bindings.put("key_key.darkmusic.next", "key.keyboard.right");
            bindings.put("key_key.darkmusic.mute", "key.keyboard.unknown");
            config.resetShortcuts(bindings);
            assertEquals("guiScale:3\nkey_key.jump:key.keyboard.space\nkey_key.darkmusic.next:key.keyboard.right\nlanguage:zh_tw\nkey_key.darkmusic.mute:key.keyboard.unknown\n", Files.readString(options));
            var loaded = config.load();
            assertTrue(loaded.nativeShortcutNames.contains("key.darkmusic.next"));
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void clearsOnlyRecognizedRecordsAndPreservesToolsAndEveryMediaByte() throws Exception {
        Path real = TestMusic.featured();
        String originalHash = hash(real);
        var modified = Files.getLastModifiedTime(real);
        Path data = Files.createDirectories(temp.resolve("data"));
        Path custom = Files.createDirectories(temp.resolve("custom music"));
        Path other = Files.createDirectories(temp.resolve("another music root"));
        Path bundle = Files.createDirectories(data.resolve("tools/managed/bundle-1234-abcd"));
        Path scratch = Files.createDirectories(data.resolve("downloads/work/item-user-folder"));
        var kept = new LinkedHashMap<Path, String>();
        for (Path file : List.of(custom.resolve("downloaded song.mp3"), custom.resolve("user video.mp4"),
            data.resolve("music/song.mp3"), other.resolve(".darkmusic-downloads.json"), other.resolve("downloaded.txt"),
            bundle.resolve("ffmpeg.exe"), bundle.resolve("private song.mp3"), scratch.resolve("original.mp3"), temp.resolve("external-ffmpeg.exe"))) {
            Files.createDirectories(file.getParent());
            Files.copy(real, file);
            kept.put(file, hash(file));
        }
        Files.writeString(data.resolve("playlists.json"), "[]");
        Files.writeString(data.resolve("library-cache.json"), "[]");
        Files.writeString(custom.resolve(".darkmusic-downloads.json"), "{\"youtube:abc\":\"downloaded song.mp3\"}");
        Files.writeString(custom.resolve("downloaded.txt"), "youtube abc\nbilibili BV123\n");
        Files.writeString(bundle.resolve("manifest.json"), "{\"ytDlpRelease\":\"v1\",\"ffmpegRelease\":\"v2\",\"digests\":{}}");
        Files.writeString(bundle.resolve("FFmpeg.LICENSE"), "GNU GENERAL PUBLIC LICENSE");
        Files.writeString(bundle.getParent().resolve("current.json"), "{\"directory\":\"bundle-1234-abcd\"}");
        // This is the actual development tool, not an executable or media fixture.
        Path developmentTool = Path.of(System.getProperty("darkmusic.test.ytdlp", "Music/yt-dlp.exe"));
        boolean localTool = Files.isRegularFile(developmentTool);
        if (localTool) Files.copy(developmentTool, bundle.resolve("yt-dlp.exe"));
        var retained = ResetStorage.clear(data, List.of(custom, other));
        assertFalse(Files.exists(data.resolve("library-cache.json")));
        assertFalse(Files.exists(custom.resolve(".darkmusic-downloads.json")));
        assertFalse(Files.exists(custom.resolve("downloaded.txt")));
        assertTrue(Files.exists(bundle.resolve("manifest.json")));
        assertTrue(Files.exists(bundle.resolve("FFmpeg.LICENSE")));
        assertTrue(Files.exists(bundle.getParent().resolve("current.json")));
        if (localTool) assertTrue(Files.exists(bundle.resolve("yt-dlp.exe")));
        assertEquals("[]", Files.readString(data.resolve("playlists.json")));
        for (var entry : kept.entrySet())
            assertEquals(entry.getValue(), hash(entry.getKey()), entry.getKey().toString());
        assertEquals(originalHash, hash(real));
        assertEquals(modified, Files.getLastModifiedTime(real));
        assertEquals(2, retained.size(), retained.toString());
    }

    @Test
    void invalidCacheHistoryAndUserNotesArePreserved() throws Exception {
        Files.writeString(temp.resolve("library-cache.json"), "user notes, not a cache");
        Files.writeString(temp.resolve(".darkmusic-downloads.json"), "{broken");
        Files.writeString(temp.resolve("downloaded.txt"), "Do not delete my notes.");
        assertEquals(3, ResetStorage.clear(temp, List.of(temp)).size());
        assertEquals("user notes, not a cache", Files.readString(temp.resolve("library-cache.json")));
        assertEquals("{broken", Files.readString(temp.resolve(".darkmusic-downloads.json")));
        assertEquals("Do not delete my notes.", Files.readString(temp.resolve("downloaded.txt")));
    }

    @Test
    void activeWorkspacePreventsAnyDiskCleanup() throws Exception {
        for (String name : List.of("downloads/workspace.lock")) {
            Path lockPath = temp.resolve(name);
            Files.createDirectories(lockPath.getParent());
            Files.writeString(temp.resolve("library-cache.json"), "[]");
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                assertThrows(java.io.IOException.class, () -> ResetStorage.clear(temp, List.of()));
                assertEquals("[]", Files.readString(temp.resolve("library-cache.json")));
            }
        }
    }

    @Test
    void activeDownloadBlocksCleanupAndCancellationReleasesItsLock() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var token = new com.darkmusic.client.download.ProcessRunner.Token();
        var runner = new com.darkmusic.client.download.ProcessRunner() {
            @Override
            public Result run(List<String> command, Path directory, Token cancellation, java.time.Duration timeout, int maximum, java.util.function.Consumer<String> output) {
                entered.countDown();
                while (true) {
                    cancellation.check();
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                }
            }
        };
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pipeline = new com.darkmusic.client.download.DownloadPipeline(runner, temp);
            var tools = new com.darkmusic.client.download.Toolchain(temp.resolve("yt-dlp"), temp.resolve("ffmpeg"), temp.resolve("ffprobe"), "", "");
            var entry = new com.darkmusic.client.download.DownloadModels.Entry(1, "Authorized entry", "https://example.com/video", "generic", "one", true);
            var active = worker.submit(() -> {
                try {
                    pipeline.execute(entry, temp.resolve("music"), tools, token, (status, detail) -> {
                    });
                    fail("Cancelled download completed");
                } catch (java.util.concurrent.CancellationException expected) {
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            Files.writeString(temp.resolve("library-cache.json"), "[]");
            assertThrows(java.io.IOException.class, () -> ResetStorage.clear(temp, List.of()));
            assertTrue(Files.exists(temp.resolve("library-cache.json")));
            token.cancel();
            active.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(ResetStorage.clear(temp, List.of()).isEmpty());
            assertFalse(Files.exists(temp.resolve("library-cache.json")));
        } finally {
            token.cancel();
            worker.shutdownNow();
        }
    }

    @Test
    void mediaInReservedCacheAndLockPathsIsNeverRemoved() throws Exception {
        Path real = TestMusic.featured();
        for (String name : List.of("library-cache.json", "downloads/workspace.lock")) {
            Path data = Files.createDirectories(temp.resolve(name.replace('/', '_')));
            Path target = data.resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(real, target);
            String before = hash(target);
            if (name.endsWith(".lock"))
                assertThrows(java.io.IOException.class, () -> ResetStorage.clear(data, List.of()));
            else assertEquals(1, ResetStorage.clear(data, List.of()).size());
            assertEquals(before, hash(target));
        }
    }

    @Test
    void deletingPlaylistOnlyChangesItsReferencesNotItsMedia() throws Exception {
        Path source = TestMusic.featured();
        String before = hash(source);
        var io = Executors.newSingleThreadExecutor();
        try {
            var store = new PlaylistStore(temp, io, message -> fail(message));
            store.loaded(store.read());
            String first = store.create("delete me"), second = store.create("keep me");
            store.addAll(first, List.of(StorageTest.track("root", source.getFileName().toString(), source)));
            store.delete(first);
            io.submit(() -> {
            }).get();
            assertNull(store.get(first));
            assertNotNull(store.get(second));
            assertEquals(2, store.read().size());
            assertEquals(before, hash(source));
        } finally {
            io.shutdownNow();
        }
    }
}
