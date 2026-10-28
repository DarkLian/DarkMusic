package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.download.DownloadJson;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class AuditRegressionTest {
    @TempDir
    Path temp;

    @Test
    void validationAndUnrelatedNumbersAreNotAuthenticationOrHttpErrors() throws Exception {
        String unrelated = DownloadJson.friendly("Track 429 is private network music");
        assertEquals(Text.get("download.error.unknown"), unrelated);
        assertFalse(unrelated.contains("HTTP 429"));
        assertFalse(unrelated.contains("authentication"));
        assertTrue(DownloadJson.friendly("HTTP Error 429: Too Many Requests").contains("HTTP 429"));
        assertTrue(DownloadJson.friendly("HTTP Error 403: Forbidden").contains("403"));
        try (var service = new DownloadService(temp, () -> {
        })) {
            service.analyze("file:///music", 0);
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertTrue(service.snapshot().message().contains("valid HTTP"));
            assertFalse(service.snapshot().message().contains("authentication"));
            assertSame(service.snapshot(), service.snapshot());
        }
    }

    @Test
    void explicitToolRechecksProbeSamePathsAndClearStaleSuccess() throws Exception {
        var runner = new ToolsRunner();
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        try (var service = new DownloadService(temp, () -> {
        }, runner)) {
            service.configure(new ToolPaths(executable, executable, executable, ""));
            service.checkTools();
            DownloadTest.await(() -> !service.snapshot().analyzing());
            int first = runner.versions.get();
            service.checkTools();
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertTrue(runner.versions.get() > first);
            runner.fail = true;
            service.checkTools();
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertTrue(service.snapshot().toolStatus().contains("failed"));
        }
    }

    @Test
    void damagedPlaylistRetainsOtherPlaylistsAndValidEntries() throws Exception {
        Path file = temp.resolve("playlists.json");
        String original = "[{\"id\":\"a\",\"name\":\"Keep\",\"entries\":[{\"rootId\":\"r\",\"path\":\"song.mp3\"},{\"rootId\":\"r\",\"path\":\"../bad\"}]},{\"id\":\"b\",\"name\":\"Other\",\"entries\":[]},{\"id\":null,\"name\":\"Bad\",\"entries\":[]}]";
        Files.writeString(file, original);
        var warnings = new CopyOnWriteArrayList<String>();
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new PlaylistStore(temp, io, warnings::add);
            var loaded = io.submit(store::read).get();
            assertEquals(2, loaded.size());
            assertEquals(1, loaded.getFirst().entries().size());
            assertFalse(warnings.isEmpty());
            assertEquals(original, Files.readString(file));
            store.loaded(loaded);
            store.rename("a", "Recovered");
            io.submit(() -> {
            }).get();
            try (var paths = Files.list(temp)) {
                Path backup = paths.filter(f -> f.getFileName().toString().startsWith("playlists.invalid-")).findFirst().orElseThrow();
                assertEquals(original, Files.readString(backup));
            }
        }
    }

    @Test
    void failedAndUnchangedScansPreserveExistingMetadataCache() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path song = root.resolve("song.mp3");
        Files.copy(TestMusic.featured(), song);
        Path data = temp.resolve("data");
        Path cache = data.resolve("library-cache.json");
        try (var library = new MusicLibrary(data)) {
            library.setRoot(root, "root");
            DownloadTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            String original = Files.readString(cache);
            var modified = Files.getLastModifiedTime(cache);
            long revision = library.snapshot().revision();
            library.rescan();
            DownloadTest.await(() -> !library.snapshot().scanning() && library.snapshot().revision() > revision);
            assertEquals(modified, Files.getLastModifiedTime(cache));
            Path detached = root.resolveSibling("detached");
            Files.move(root, detached);
            library.rescan();
            DownloadTest.await(() -> !library.snapshot().scanning() && library.snapshot().message().contains("unavailable"));
            assertEquals(original, Files.readString(cache));
            Files.move(detached, root);
            library.rescan();
            DownloadTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            assertEquals(original, Files.readString(cache));
            Files.delete(song);
            library.rescan();
            DownloadTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().isEmpty());
            assertFalse(Files.readString(cache).contains("song.mp3"));
        }
    }

    @Test
    void cancelledAnalysisCannotInsertBatchAfterCancelAll() throws Exception {
        try (var service = new DownloadService(temp, () -> {
        })) {
            var tokenField = DownloadService.class.getDeclaredField("analysisToken");
            tokenField.setAccessible(true);
            var token = (ProcessRunner.Token) tokenField.get(service);
            service.cancelAll();
            var add = DownloadService.class.getDeclaredMethod("add", List.class, Path.class, ToolPaths.class, ProcessRunner.Token.class);
            add.setAccessible(true);
            var failure = assertThrows(InvocationTargetException.class, () -> add.invoke(service, List.of(new DownloadModels.Entry(1, "Entry", "https://example.com/a", "Test", "a", true)), temp, new ToolPaths("", "", "", ""), token));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertTrue(service.snapshot().items().isEmpty());
        }
    }

    @Test
    void thousandEntryQueueReusesSnapshotsAndEnforcesItsLimit() throws Exception {
        var runner = new ToolsRunner();
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        try (var service = new DownloadService(temp, () -> {
        }, runner)) {
            service.configure(new ToolPaths(executable, executable, executable, ""));
            var entries = IntStream.range(0, 1000).mapToObj(i -> new DownloadModels.Entry(i + 1, "Unavailable " + i, "", "Test", "" + i, false)).toList();
            service.enqueueSelected(entries, temp.resolve("music"));
            DownloadTest.await(() -> !service.snapshot().analyzing());
            var snapshot = service.snapshot();
            assertEquals(1000, snapshot.items().size());
            for (int i = 0; i < 1000; i++) assertSame(snapshot, service.snapshot());
            service.enqueueSelected(entries.subList(0, 1), temp);
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertTrue(service.snapshot().message().contains("queue holds"));
            service.clearFinished();
            assertEquals(1000, service.snapshot().items().size(), "Failed entries must be retained");
            service.clearFailed();
            assertTrue(service.snapshot().items().isEmpty());
        }
    }

    @Test
    void nativeShortcutMigrationDetectsNativePreferencesWithoutSerializingItsState() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("config/darkmusic"));
        Files.writeString(temp.resolve("options.txt"), "key_key.darkmusic.volume_up:key.keyboard.f10\nkey_key.other.mod:key.keyboard.f9\n");
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new ConfigStore(directory, io, message -> fail(message));
            var settings = io.submit(store::load).get();
            assertEquals(Set.of("key.darkmusic.volume_up"), settings.nativeShortcutNames);
            assertFalse(ConfigStore.JSON.toJson(settings).contains("nativeShortcutNames"));
        }
    }

    @Test
    void undecodableOptionsFileWarnsButStillLoadsSettings() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("legacy/config/darkmusic"));
        // A Big5-encoded server name, as written by a legacy editor: invalid UTF-8 midway through the file.
        byte[] legacy = {'l', 'a', 's', 't', 'S', 'e', 'r', 'v', 'e', 'r', ':', (byte) 0xA4, (byte) 0xA4, '\n',
            'k', 'e', 'y', '_', 'k', 'e', 'y', '.', 'd', 'a', 'r', 'k', 'm', 'u', 's', 'i', 'c', '.', 'n', 'e', 'x', 't', ':', 'k', '\n'};
        Files.write(temp.resolve("legacy/options.txt"), legacy);
        var warnings = new CopyOnWriteArrayList<String>();
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new ConfigStore(directory, io, warnings::add);
            var settings = io.submit(store::load).get();
            assertNotNull(settings, "Settings must load even when shortcut detection cannot read options.txt");
            assertEquals(1, warnings.size(), "The unreadable shortcut file is reported once");
            assertNotNull(store.snapshotJson());
        }
    }

    @Test
    void internalWatcherEventsAreIgnoredButSupportedHiddenSongsAreDiscovered() throws Exception {
        Path root = Files.createDirectories(temp.resolve("music"));
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            library.setRoot(root, "root");
            DownloadTest.await(() -> !library.snapshot().scanning());
            long revision = library.snapshot().revision();
            Files.writeString(root.resolve(".darkmusic-downloads.json"), "{}");
            Files.writeString(root.resolve(".darkmusic-incomplete.tmp"), "partial");
            Files.writeString(root.resolve("notes.txt"), "unrelated");
            var poll = library.getClass().getDeclaredMethod("poll");
            poll.setAccessible(true);
            poll.invoke(library);
            assertEquals(revision, library.snapshot().revision());
            Files.copy(TestMusic.featured(), root.resolve(".darkmusic-song.mp3"));
            poll.invoke(library);
            DownloadTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
        }
    }

    static class ToolsRunner extends ProcessRunner {
        final AtomicInteger versions = new AtomicInteger();
        volatile boolean fail;

        @Override
        public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) {
            token.check();
            if (args.contains("--version") || args.contains("-version")) {
                int n = versions.incrementAndGet();
                return new Result(fail ? 1 : 0, "tool version 2026.1." + n);
            }
            if (args.contains("--help"))
                return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --no-plugin-dirs");
            return new Result(0, "libmp3lame");
        }
    }

}
