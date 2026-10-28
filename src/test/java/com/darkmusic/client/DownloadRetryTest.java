package com.darkmusic.client;

import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.hud.DownloadHud;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.darkmusic.client.download.DownloadModels.Entry;
import static com.darkmusic.client.download.DownloadModels.Status;
import static org.junit.jupiter.api.Assertions.*;

class DownloadRetryTest {
    @TempDir
    Path temp;

    @Test
    void privateEntriesDoNotRetryAutomaticallyButManualRetryStillWorks() throws Exception {
        var attempts = new AtomicInteger();
        var runner = new DownloadTest.FakeRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws IOException, InterruptedException {
                if (args.contains("--write-info-json")) {
                    attempts.incrementAndGet();
                    return new Result(1, "Private video");
                }
                return super.run(args, dir, token, timeout, max, line);
            }
        };
        try (var service = new DownloadService(temp, () -> {
        }, runner)) {
            service.configure(new DownloadTest().tools());
            var entry = new Entry(1, "Private", "https://example.com/private", "Test", "private", true);
            service.enqueueSelected(List.of(entry), temp.resolve("music"));
            DownloadTest.await(() -> service.snapshot().items().size() == 1 && service.snapshot().items().getFirst().status() == Status.FAILED);
            assertEquals(1, attempts.get());
            service.retryEntry(entry, temp.resolve("music"));
            DownloadTest.await(() -> attempts.get() == 2 && service.snapshot().items().getFirst().status() == Status.FAILED);
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void retriesWaitForQueueThenStopAfterThreeAndFailedRowsAreRetained() throws Exception {
        var release = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);
        var attempts = new ConcurrentHashMap<String, AtomicInteger>();
        var repaired = new AtomicBoolean();
        var runner = new DownloadTest.FakeRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws IOException, InterruptedException {
                if (args.contains("--write-info-json")) {
                    String id = args.getLast();
                    attempts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                    if (id.endsWith("/slow")) {
                        blocked.countDown();
                        while (!release.await(10, TimeUnit.MILLISECONDS)) token.check();
                    } else if (!repaired.get()) return new Result(1, "HTTP Error 503");
                }
                return super.run(args, dir, token, timeout, max, line);
            }
        };
        try (var service = new DownloadService(temp, () -> {
        }, runner)) {
            service.configure(new DownloadTest().tools());
            var bad = new Entry(1, "Bad", "https://example.com/bad", "Test", "bad", true);
            var slow = new Entry(2, "Slow", "https://example.com/slow", "Test", "slow", true);
            service.enqueueSelected(List.of(bad, slow), temp.resolve("music"));
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            DownloadTest.await(() -> service.snapshot().items().stream().anyMatch(i -> i.status() == Status.FAILED));
            assertEquals(1, attempts.get(bad.url()).get(), "Retry must wait until the other entry finishes");
            release.countDown();
            DownloadTest.await(() -> attempts.get(bad.url()).get() == 4 && service.snapshot().items().stream().allMatch(i -> i.status().finished()));
            assertEquals(4, attempts.get(bad.url()).get());
            assertEquals(1, attempts.get(slow.url()).get());
            service.clearFinished();
            assertEquals(1, service.snapshot().items().size());
            assertEquals(Status.FAILED, service.snapshot().items().getFirst().status());
            assertTrue(service.failedEntry(bad, temp.resolve("music")));
            var hud = new DownloadHud();
            hud.update(service, System.nanoTime());
            var notice = hud.getClass().getDeclaredField("notice");
            notice.setAccessible(true);
            String failedNotice = (String) notice.get(hud);
            repaired.set(true);
            service.retryEntry(bad, temp.resolve("music"));
            DownloadTest.await(() -> service.snapshot().items().getFirst().status() == Status.SAVED);
            assertEquals(5, attempts.get(bad.url()).get());
            // A fast retry may finish between HUD ticks; the new outcome must still notify.
            hud.update(service, System.nanoTime());
            assertNotEquals(failedNotice, notice.get(hud));
            service.clearFinished();
            assertTrue(service.snapshot().items().isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancelAllDisablesAutomaticRetriesAndClearFailedDoesNotRemoveAudio() throws Exception {
        var runner = new DownloadTest.FakeRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws IOException, InterruptedException {
                if (args.contains("--write-info-json")) return new Result(1, "HTTP Error 429");
                return super.run(args, dir, token, timeout, max, line);
            }
        };
        Path media = temp.resolve("original.mp3");
        Files.copy(TestMusic.featured(), media);
        long size = Files.size(media);
        try (var service = new DownloadService(temp.resolve("data"), () -> {
        }, runner)) {
            service.configure(new DownloadTest().tools());
            service.enqueueSelected(List.of(new Entry(1, "Bad", "https://example.com/bad", "Test", "bad", true)), temp);
            DownloadTest.await(() -> service.snapshot().items().size() == 1 && service.snapshot().items().getFirst().status() == Status.FAILED);
            service.cancelAll();
            service.clearFailed();
            assertTrue(service.snapshot().items().isEmpty());
            assertEquals(size, Files.size(media));
        }
    }

    @Test
    void previewAndEditedLinkRemainAvailableUntilExplicitlyReanalyzedOrCleared() throws Exception {
        var runner = new DownloadTest.FakeRunner();
        try (var service = new DownloadService(temp, () -> {
        }, runner)) {
            service.configure(new DownloadTest().tools());
            service.analyze("https://example.com/playlist", 0);
            DownloadTest.await(() -> !service.snapshot().analyzing());
            var preview = service.snapshot().preview();
            assertNotNull(preview);
            service.rememberInput("https://example.com/another");
            assertEquals("https://example.com/another", service.inputUrl());
            assertSame(preview, service.snapshot().preview());
        }
    }
}
