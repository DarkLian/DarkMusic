package com.darkmusic.client;

import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.hud.DownloadProgress;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.darkmusic.client.download.DownloadModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadParallelTest {
    @TempDir
    Path temp;

    @Test
    void threeWorkersRunTogetherCancelIndependentlyAndMergeHistory() throws Exception {
        var threeStarted = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var running = new AtomicInteger();
        var maximum = new AtomicInteger();
        var runner = new DownloadTest.FakeRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws java.io.IOException, InterruptedException {
                if (!args.contains("--write-info-json")) return super.run(args, dir, token, timeout, max, line);
                int count = running.incrementAndGet();
                maximum.accumulateAndGet(count, Math::max);
                threeStarted.countDown();
                try {
                    while (!release.await(10, TimeUnit.MILLISECONDS)) token.check();
                    token.check();
                    return super.run(args, dir, token, timeout, max, line);
                } finally {
                    running.decrementAndGet();
                }
            }
        };
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path music = temp.resolve("music");
        try (var service = new DownloadService(temp.resolve("data"), () -> {
        }, runner)) {
            service.configure(new Tools(executable, executable, executable, ""));
            var entries = java.util.stream.IntStream.range(0, 8).mapToObj(i -> new Entry(i, "Track " + i, "https://example.com/" + i, "Test", "id" + i, true)).toList();
            service.enqueueSelected(entries, music);
            assertTrue(threeStarted.await(10, TimeUnit.SECONDS), "Three independent downloads must reach the runner together");
            assertEquals(3, maximum.get());
            var state = service.snapshot();
            service.cancel(state.items().getFirst().id());
            DownloadTest.await(() -> service.snapshot().items().getFirst().status() == Status.CANCELLED);
            assertTrue(service.snapshot().items().stream().anyMatch(i -> i.status() == Status.DOWNLOADING));
            release.countDown();
            DownloadTest.await(() -> service.snapshot().items().size() == 8 && service.snapshot().items().stream().allMatch(i -> i.status().finished()));
            assertEquals(7, service.snapshot().items().stream().filter(i -> i.status() == Status.SAVED).count());
            assertEquals(3, maximum.get(), "Concurrency must stay bounded");
            var history = JsonParser.parseString(Files.readString(music.resolve(".darkmusic-downloads.json"))).getAsJsonObject();
            assertEquals(7, history.size(), "Workers must not lose each other's history entries");
            try (var files = Files.list(music)) {
                assertEquals(7, files.filter(p -> p.toString().endsWith(".mp3")).count());
            }
            try (var files = Files.list(temp.resolve("data/downloads/work"))) {
                assertEquals(0, files.count());
            }
        } finally {
            release.countDown();
        }
        assertEquals(0, running.get());
    }

    @Test
    void overallProgressCountsFailuresAsProcessedButNeverAsSaved() {
        var items = List.of(new Item(1, "saved", temp, Status.SAVED, 1, ""), new Item(2, "failed", temp, Status.FAILED, -1, "403"), new Item(3, "active", temp, Status.DOWNLOADING, .5, ""), new Item(4, "waiting", temp, Status.QUEUED, -1, ""));
        var progress = DownloadProgress.of(items);
        assertEquals(4, progress.total());
        assertEquals(2, progress.finished());
        assertEquals(1, progress.failed());
        assertEquals(2, progress.active());
        assertEquals(.625, progress.fraction());
    }
}
