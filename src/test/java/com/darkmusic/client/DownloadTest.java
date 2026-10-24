package com.darkmusic.client;

import com.darkmusic.client.download.*;
import com.darkmusic.client.library.MusicLibrary;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.darkmusic.client.download.DownloadModels.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadTest {
    @TempDir
    Path temp;

    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out");
    }

    Tools tools() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new Tools(executable, executable, executable, "");
    }

    Entry entry(String id) {
        return new Entry(1, "歌曲 音楽", "https://example.com/" + id, "Youtube", id, true);
    }

    @Test
    void previewAnalysisUsesOneFlatRequestWithoutCaptionChecks() throws Exception {
        var fake = new FakeRunner();
        try (var service = new DownloadService(temp.resolve("preview-data"), () -> {
        }, fake)) {
            service.configure(tools());
            service.lyrics(true, "ja, en", "en-US");
            service.analyze("https://www.youtube.com/playlist?list=PL1", 1);
            await(() -> !service.snapshot().analyzing() && service.snapshot().preview() != null);
            var requests = fake.commands.stream().filter(command -> command.contains("--dump-single-json")).toList();
            assertEquals(1, requests.size());
            assertTrue(requests.getFirst().contains("--flat-playlist"));
            assertTrue(fake.commands.stream().noneMatch(command -> command.contains("--list-subs") || command.contains("--write-subs") || command.contains("--write-auto-subs")));
        }
    }

    @Test
    void savedAudioDoesNotWaitForOptionalCaptionRequests() throws Exception {
        var fake = new FakeRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws IOException, InterruptedException {
                assertFalse(args.contains("--write-subs") || args.contains("--write-auto-subs"), "Captions must be retrieved after the audio job finishes");
                var result = super.run(args, dir, token, timeout, max, line);
                if (args.contains("--write-info-json")) {
                    var info = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("source.info.json"))).getAsJsonObject();
                    info.add("subtitles", com.google.gson.JsonParser.parseString("{\"ja\":[{}]}"));
                    Files.writeString(dir.resolve("source.info.json"), info.toString());
                }
                return result;
            }
        };
        var notified = new CountDownLatch(1);
        try (var service = new DownloadService(temp.resolve("async-lyrics"), () -> {
        }, fake)) {
            service.configure(tools());
            service.lyrics(true);
            service.savedObserver(file -> notified.countDown());
            service.enqueueSelected(List.of(entry("background-lyrics")), temp.resolve("saved"));
            await(() -> service.snapshot().items().size() == 1 && service.snapshot().items().getFirst().status() == Status.SAVED);
            assertTrue(notified.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void urlsSelectionsAndUnicodeNamesAreSafe() {
        assertEquals("YouTube", source("https://www.youtube.com/watch?v=a&list=PL1"));
        assertEquals("Bilibili", source("https://space.bilibili.com/1/favlist"));
        for (String bad : List.of("file:///tmp/a", "https://user:pass@example.com/a", "--exec x", "https://example.com/\n--exec=x"))
            assertThrows(IllegalArgumentException.class, () -> url(bad));
        assertEquals(List.of(10, 11, 12), range(10, 12));
        assertThrows(IllegalArgumentException.class, () -> range(0, 10));
        assertThrows(IllegalArgumentException.class, () -> range(1, MAX_BATCH + 1));
        String filename = DownloadPipeline.filename("歌曲/音楽:*?", "Youtube:abc");
        assertTrue(filename.startsWith("歌曲_音楽"));
        assertFalse(filename.contains("/"));
        assertNotEquals(filename, DownloadPipeline.filename("歌曲/音楽:*?", "Bilibili:abc"));
    }

    @Test
    void previewsKeepUnavailableEntriesAndOriginalIndices() {
        String data = "{\"playlist_count\":500,\"entries\":[{\"id\":\"a\",\"ie_key\":\"Youtube\",\"title\":\"A\"},null,{\"id\":\"c\",\"url\":\"https://example.com/c\",\"availability\":\"private\"}]}";
        var p = DownloadJson.preview("https://www.youtube.com/playlist?list=PL1", 101, JsonParser.parseString(data).getAsJsonObject());
        assertTrue(p.collection());
        assertTrue(p.hasMore());
        assertEquals(101, p.entries().getFirst().index());
        assertEquals("https://www.youtube.com/watch?v=a", p.entries().getFirst().url());
        assertFalse(p.entries().get(1).available());
        assertFalse(p.entries().get(2).available());
        var mix = DownloadJson.preview("https://www.youtube.com/watch?v=a&list=RDa", 1, JsonParser.parseString(data).getAsJsonObject());
        assertTrue(mix.videoAlso());
        assertEquals(-1, mix.total());
        assertTrue(DownloadJson.friendly("HTTP Error 429").contains("HTTP 429"));
        assertTrue(DownloadJson.friendly("DRM restricted private").contains("not bypassed"));
    }

    @Test
    void pipelinePublishesOnlyAfterConversionAndSkipsDuplicates() throws Exception {
        var fake = new FakeRunner();
        var token = new ProcessRunner.Token();
        var chain = Toolchain.resolve(tools(), temp.resolve("data"), fake, token);
        Path destination = Files.createDirectories(temp.resolve("chosen 音楽"));
        var pipeline = new DownloadPipeline(fake, temp.resolve("data"));
        var saved = pipeline.execute(entry("a"), destination, chain, token, (a, b) -> {
        });
        assertTrue(Files.exists(saved.file()));
        assertEquals(destination, saved.file().getParent());
        assertFalse(saved.skipped());
        assertTrue(pipeline.execute(entry("a"), destination, chain, token, (a, b) -> {
        }).skipped());
        var convert = fake.commands.stream().filter(a -> a.contains("libmp3lame") && !a.contains("-encoders")).findFirst().orElseThrow();
        assertTrue(convert.contains("artist=作曲家"));
        assertTrue(convert.contains("album=专辑"));
        assertTrue(convert.contains("track=7"));
        assertTrue(fake.commands.stream().anyMatch(a -> a.contains("--no-playlist") && a.contains("--") && a.getLast().equals(entry("a").url())));
        try (var files = Files.list(temp.resolve("data/downloads/work"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void thumbnailFailureFallsBackButConversionAndValidationFailuresNeverSucceed() throws Exception {
        var fake = new FakeRunner();
        var token = new ProcessRunner.Token();
        var chain = Toolchain.resolve(tools(), temp.resolve("data"), fake, token);
        var pipeline = new DownloadPipeline(fake, temp.resolve("data"));
        Path destination = temp.resolve("music");
        fake.badCover = true;
        var result = pipeline.execute(entry("a"), destination, chain, token, (a, b) -> {
        });
        assertTrue(result.warning().contains("without artwork"));
        fake.failConversion = true;
        assertThrows(IOException.class, () -> pipeline.execute(entry("b"), destination, chain, token, (a, b) -> {
        }));
        fake.failConversion = false;
        fake.corruptProbe = true;
        assertThrows(IOException.class, () -> pipeline.execute(entry("c"), destination, chain, token, (a, b) -> {
        }));
        try (var files = Files.list(destination)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".mp3")).count());
        }
    }

    @Test
    void queueContinuesAfterUnavailableItemsAndCanCancelActiveAndPendingWork() throws Exception {
        var fake = new FakeRunner();
        fake.hang = true;
        try (var service = new DownloadService(temp.resolve("data"), () -> {
        }, fake)) {
            service.configure(tools());
            service.enqueueSelected(List.of(entry("a"), entry("b")), temp.resolve("music"));
            assertTrue(fake.entered.await(10, TimeUnit.SECONDS));
            var state = service.snapshot();
            assertEquals(2, state.items().size());
            service.cancel(state.items().getFirst().id());
            service.cancel(state.items().get(1).id());
            await(() -> service.snapshot().items().stream().allMatch(i -> i.status() == Status.CANCELLED));
            fake.hang = false;
            service.clearFinished();
            service.enqueueSelected(List.of(new Entry(1, "Private", "", "Youtube", "private", false), entry("c")), temp.resolve("music"));
            await(() -> service.snapshot().items().size() == 2 && service.snapshot().items().stream().allMatch(i -> i.status().finished()));
            assertEquals(Status.FAILED, service.snapshot().items().getFirst().status());
            assertEquals(Status.SAVED, service.snapshot().items().get(1).status());
        }
    }

    @Test
    void libraryIgnoresScratchAudioEvenWhenLibraryContainsConfigDirectory() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Path scratch = Files.createDirectories(data.resolve("downloads/work/item-1"));
        Files.copy(TestMusic.featured(), scratch.resolve("converted.mp3"));
        try (var library = new MusicLibrary(data)) {
            library.setRoot(temp, "test");
            await(() -> !library.snapshot().scanning());
            assertTrue(library.snapshot().tracks().isEmpty());
        }
    }

    @Test
    void unknownPlaylistTotalsDoNotMistakePageCountForCollectionSize() {
        var json = JsonParser.parseString("{\"n_entries\":100,\"requested_entries\":[101,103],\"entries\":[{\"id\":\"a\",\"ie_key\":\"Youtube\"},null]}").getAsJsonObject();
        var preview = DownloadJson.preview("https://www.youtube.com/playlist?list=PL1", 101, json);
        assertEquals(-1, preview.total());
        assertEquals(103, preview.entries().get(1).index());
        var single = DownloadJson.preview("https://example.com/audio", 1, JsonParser.parseString("{\"title\":\"Owned\",\"webpage_url\":\"https://example.com/audio\"}").getAsJsonObject());
        assertEquals(0, single.entries().getFirst().index());
    }

    @Test
    void failedNetworkEntryDoesNotStopQueueAndErrorsRemainUseful() throws Exception {
        var fake = new FakeRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws IOException, InterruptedException {
                if (args.contains("--write-info-json") && args.getLast().endsWith("/bad"))
                    return new Result(1, "HTTP Error 403: Forbidden");
                return super.run(args, dir, token, timeout, max, line);
            }
        };
        try (var service = new DownloadService(temp.resolve("data"), () -> {
        }, fake)) {
            service.configure(tools());
            service.enqueueSelected(List.of(entry("bad"), entry("good")), temp.resolve("music"));
            await(() -> service.snapshot().items().size() == 2 && service.snapshot().items().stream().allMatch(i -> i.status().finished()));
            assertEquals(Status.FAILED, service.snapshot().items().getFirst().status());
            assertTrue(service.snapshot().items().getFirst().message().contains("403"));
            assertEquals(Status.SAVED, service.snapshot().items().get(1).status());
        }
    }

    @Test
    void missingToolsAndCorruptHistoryFailWithoutOverwritingExistingData() throws Exception {
        var fake = new FakeRunner();
        var token = new ProcessRunner.Token();
        var missing = new Tools(temp.resolve("missing.exe").toString(), "", "", "");
        assertThrows(IOException.class, () -> Toolchain.resolve(missing, temp, fake, token));
        var chain = Toolchain.resolve(tools(), temp, fake, token);
        Path destination = Files.createDirectories(temp.resolve("music"));
        Path history = destination.resolve(".darkmusic-downloads.json");
        Files.writeString(history, "broken-original");
        assertThrows(IOException.class, () -> new DownloadPipeline(fake, temp.resolve("data")).execute(entry("a"), destination, chain, token, (a, b) -> {
        }));
        assertEquals("broken-original", Files.readString(history));
    }

    @Test
    void successfulRetryAndCancellationClearTheCurrentDiagnostic() throws Exception {
        var fake = new FakeRunner();
        fake.failConversion = true;
        try (var service = new DownloadService(temp.resolve("diagnostic-service"), () -> {
        }, fake)) {
            service.configure(tools());
            service.enqueueSelected(List.of(entry("retry")), temp.resolve("music"));
            await(() -> service.snapshot().items().size() == 1 && service.snapshot().items().getFirst().status() == Status.FAILED);
            long id = service.snapshot().items().getFirst().id();
            assertEquals(DownloadDiagnostics.Category.PROCESSING_FAILURE, service.diagnostic(id).category());
            assertEquals(DownloadDiagnostics.Stage.PROCESSING, service.diagnostic(id).stage());
            fake.failConversion = false;
            service.retry(id);
            await(() -> service.snapshot().items().getFirst().status() == Status.SAVED);
            assertNull(service.diagnostic(id));
            fake.hang = true;
            service.enqueueSelected(List.of(entry("cancel")), temp.resolve("music"));
            await(() -> service.snapshot().items().size() == 2 && service.snapshot().items().getLast().status() == Status.DOWNLOADING);
            long cancelled = service.snapshot().items().getLast().id();
            service.cancel(cancelled);
            await(() -> service.snapshot().items().getLast().status() == Status.CANCELLED);
            assertNull(service.diagnostic(cancelled));
        }
    }

    static class FakeRunner extends ProcessRunner {
        final Path media = TestMusic.featured();
        final byte[] artwork = new com.darkmusic.client.library.MetadataReader().artwork(media);
        final List<List<String>> commands = new CopyOnWriteArrayList<>();
        boolean failConversion, corruptProbe, noCover, badCover, hang;
        CountDownLatch entered = new CountDownLatch(1);

        FakeRunner() throws Exception {
        }

        @Override
        public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws IOException, InterruptedException {
            commands.add(List.copyOf(args));
            token.check();
            if (args.contains("--version")) return new Result(0, "2026.08.19\n");
            if (args.contains("-version")) return new Result(0, "ffmpeg version 9.0.2\n");
            if (args.contains("--help"))
                return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --no-plugin-dirs");
            if (args.contains("-encoders")) return new Result(0, "libmp3lame mjpeg");
            if (args.contains("--dump-single-json"))
                return new Result(0, "{\"_type\":\"playlist\",\"title\":\"Collection\",\"playlist_count\":3,\"entries\":[{\"id\":\"a\",\"ie_key\":\"Youtube\",\"url\":\"https://example.com/a\",\"title\":\"歌曲 音楽\"},null,{\"id\":\"c\",\"ie_key\":\"Youtube\",\"url\":\"https://example.com/c\",\"title\":\"C\"}]}\n");
            if (args.contains("-show_entries"))
                return new Result(0, corruptProbe ? "{}" : "{\"streams\":[{\"codec_name\":\"mp3\"}]}");
            if (args.contains("-f") && args.contains("null")) return new Result(0, "");
            if (args.contains("--write-info-json")) {
                entered.countDown();
                while (hang) {
                    token.check();
                    Thread.sleep(10);
                }
                Files.copy(media, dir.resolve("source.mp3"));
                Files.writeString(dir.resolve("source.info.json"), "{\"title\":\"歌曲 音楽\",\"artist\":\"作曲家\",\"album\":\"专辑\",\"track_number\":7}");
                if (!noCover) Files.write(dir.resolve("source.jpg"), artwork);
                line.accept("DM_PROGRESS:25.0%");
                return new Result(0, "downloaded");
            }
            if (args.contains("libmp3lame") || args.contains("copy")) {
                if (failConversion || (badCover && args.contains("attached_pic")))
                    return new Result(1, "Conversion failed");
                Files.copy(media, Path.of(args.getLast()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new Result(0, "");
            }
            throw new IOException("Unexpected command " + args);
        }
    }
}
