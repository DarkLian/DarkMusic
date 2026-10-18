package com.darkmusic.client;

import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.DownloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ToolOperationTest {
    @TempDir
    Path temporary;

    private DownloadModels.Tools tools() {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new DownloadModels.Tools(java, java, java, "");
    }

    @Test
    void toolCancellationStopsTheCheckAndResetsItsBusyState() throws Exception {
        var runner = new Blocking();
        try (var service = new DownloadService(temporary, () -> {
        }, runner)) {
            service.configure(tools());
            service.checkTools();
            assertTrue(runner.entered.await(3, TimeUnit.SECONDS));
            assertTrue(service.toolsBusy());
            service.cancelTools();
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertFalse(service.toolsBusy());
            assertEquals(com.darkmusic.client.text.Text.get("download.darkmusic.tools_cancelled"), service.snapshot().message());
        }
    }

    @Test
    void cancelToolsCannotCancelAnUnrelatedUrlAnalysis() throws Exception {
        var runner = new Blocking();
        runner.url = true;
        try (var service = new DownloadService(temporary, () -> {
        }, runner)) {
            service.configure(tools());
            service.analyze("https://example.com/video", 0);
            assertTrue(runner.entered.await(3, TimeUnit.SECONDS));
            assertFalse(service.toolsBusy());
            service.cancelTools();
            assertTrue(service.snapshot().analyzing());
            runner.release = true;
            DownloadTest.await(() -> !service.snapshot().analyzing());
            assertNotNull(service.snapshot().preview());
        }
    }

    private static class Blocking extends AuditRegressionTest.ToolsRunner {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean release, url;

        @Override
        public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) {
            if (url == args.contains("--dump-single-json")) {
                entered.countDown();
                while (!release) {
                    token.check();
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                }
            }
            if (args.contains("--dump-single-json"))
                return new Result(0, "{\"id\":\"one\",\"title\":\"Accessible entry\",\"webpage_url\":\"https://example.com/video\"}");
            return super.run(args, dir, token, timeout, max, line);
        }
    }
}
