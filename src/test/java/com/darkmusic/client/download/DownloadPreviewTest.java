package com.darkmusic.client.download;

import com.darkmusic.client.testing.JavaTools;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.darkmusic.client.download.DownloadModels.Item;
import static org.junit.jupiter.api.Assertions.*;

class DownloadPreviewTest {
    private static final String URL = "https://example.com/playlist";
    @TempDir
    Path temporary;

    @Test
    void explicitlyClearingResultsKeepsTheUrlAndRejectsLateAnalysis() throws Exception {
        var runner = new Metadata();
        runner.block = true;
        try (var service = new DownloadService(temporary, () -> {
        }, runner)) {
            service.configure(JavaTools.paths());
            service.analyze(URL, 0);
            assertTrue(runner.entered.await(3, TimeUnit.SECONDS));
            assertEquals(URL, service.lastAnalyzedUrl());
            service.clearPreview();
            runner.release.countDown();
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertNull(service.snapshot().preview());
            assertEquals(URL, service.lastAnalyzedUrl());
            runner.block = false;
            service.analyze(service.lastAnalyzedUrl(), 0);
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertNotNull(service.snapshot().preview());
            service.clearPreview();
            assertNull(service.snapshot().preview());
        }
    }

    @Test
    void rangeUsesOriginalIndicesAndClearingTheUiDoesNotCancelDownloadPreparation() throws Exception {
        var runner = new Metadata();
        try (var service = new DownloadService(temporary, () -> {
        }, runner)) {
            service.configure(JavaTools.paths());
            service.analyze(URL, 0);
            DownloadTest.await(() -> !service.snapshot().analyzing());
            var preview = service.snapshot().preview();
            assertNotNull(preview);
            runner.block = true;
            service.enqueueRange(preview, 3, 5, temporary.resolve("music"));
            service.clearPreview();
            runner.release.countDown();
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertEquals(List.of("Song 3", "Song 4", "Song 5"), service.snapshot().items().stream().map(Item::title).toList());
            assertEquals("3:5", runner.ranges.getLast());
            assertNull(service.snapshot().preview());
        }
    }

    private static class Metadata extends ProcessRunner {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<String> ranges = new CopyOnWriteArrayList<>();
        volatile boolean block;

        @Override
        public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> lines) throws InterruptedException {
            if (args.contains("--help"))
                return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --no-plugin-dirs");
            if (args.contains("-encoders")) return new Result(0, "libmp3lame");
            if (!args.contains("--dump-single-json")) return new Result(0, "2026.08.19");
            entered.countDown();
            if (block) release.await(5, TimeUnit.SECONDS);
            String range = args.get(args.indexOf("--playlist-items") + 1);
            ranges.add(range);
            int first = Integer.parseInt(range.split(":")[0]), last = Math.min(5, range.contains(":") ? Integer.parseInt(range.split(":")[1]) : first);
            String entries = IntStream.rangeClosed(first, last).mapToObj(i -> "{\"id\":\"" + i + "\",\"playlist_index\":" + i + ",\"title\":\"Song " + i + "\",\"url\":\"https://example.com/" + i + "\",\"availability\":\"private\"}").collect(Collectors.joining(","));
            return new Result(0, "{\"playlist_count\":5,\"entries\":[" + entries + "]}");
        }
    }
}
