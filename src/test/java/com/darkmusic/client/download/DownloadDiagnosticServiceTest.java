package com.darkmusic.client.download;

import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.darkmusic.client.download.DownloadModels.*;
import static com.darkmusic.client.download.DownloadDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadDiagnosticServiceTest {
    @TempDir
    Path directory;

    static final class Runner extends ProcessRunner {
        final AtomicReference<String> analysis = new AtomicReference<>("Unable to extract initial state");
        final List<List<String>> commands = new CopyOnWriteArrayList<>();

        @Override
        public Result run(List<String> args, Path cwd, Token token, Duration timeout, int max, Consumer<String> line) {
            token.check();
            commands.add(List.copyOf(args));
            if (args.contains("--version") || args.contains("-version")) return new Result(0, "2026.08.19");
            if (args.contains("--help"))
                return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --no-plugin-dirs");
            if (args.contains("-encoders")) return new Result(0, "libmp3lame");
            if (args.contains("--dump-single-json")) return new Result(1, analysis.get());
            String url = args.getLast();
            return new Result(1, url.endsWith("/extractor") ? "Unable to extract initial state" : url.endsWith("/private") ? "Private video" : "Permission denied");
        }
    }

    private Tools tools() {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new Tools(java, java, java, "");
    }

    private void idle(DownloadService service) throws Exception {
        Await.until(Duration.ofSeconds(10), () -> !service.snapshot().analyzing(), "Analysis completes");
    }

    @Test
    void failedAnalysisRetainsItsContextAndNewAttemptsReplaceItWithoutNetworkOrInstallation() throws Exception {
        var runner = new Runner();
        try (var service = new DownloadService(directory, () -> {
        }, runner)) {
            service.configure(tools());
            service.analyze("https://www.bilibili.com/video/id?token=secret", 0);
            idle(service);
            var first = service.analysisDiagnostic();
            assertNotNull(first);
            assertEquals(Category.EXTRACTOR_FAILURE, first.category());
            assertEquals(Stage.ANALYSIS, first.stage());
            assertEquals(Source.BILIBILI, first.source());
            assertTrue(service.lastAnalyzedUrl().contains("token=secret"));
            assertFalse(first.report("2.5.1").contains("secret"));
            service.configure(new Tools(directory.resolve("custom-missing-executable").toString(), "", "", ""));
            service.checkTools();
            idle(service);
            assertSame(first, service.analysisDiagnostic(), "Checking tools must retain the prior analysis diagnostic");
            assertEquals(Category.TOOL_MISSING, service.toolDiagnostic().category());
            assertEquals(Tool.YT_DLP, service.toolDiagnostic().tool(), "Role is known even for a custom executable filename");
            service.configure(tools());
            service.checkTools();
            idle(service);
            assertNull(service.toolDiagnostic());
            assertSame(first, service.analysisDiagnostic());
            runner.analysis.set("HTTP Error 403: Forbidden");
            service.analyze(service.lastAnalyzedUrl(), 1);
            idle(service);
            assertEquals(Category.ACCESS_DENIED, service.analysisDiagnostic().category());
            assertEquals(Stage.COLLECTION, service.analysisDiagnostic().stage());
            assertNotEquals(first.operation(), service.analysisDiagnostic().operation());
            service.rememberInput("");
            assertNull(service.analysisDiagnostic(), "Intentionally resetting the URL clears its diagnostic");
            service.clearPreview();
            assertNull(service.analysisDiagnostic());
            assertTrue(runner.commands.stream().noneMatch(command -> command.contains("--update") || command.contains("-U")));
        }
    }

    @Test
    void parallelFailuresStayWithTheirJobsRetriesReplaceAttemptsAndClearingRemovesDiagnostics() throws Exception {
        var runner = new Runner();
        try (var service = new DownloadService(directory, () -> {
        }, runner)) {
            service.configure(tools());
            service.enqueueSelected(List.of(
                new Entry(1, "Extractor", "https://www.bilibili.com/video/extractor", "Bilibili", "a", true),
                new Entry(2, "Private", "https://youtu.be/private", "Youtube", "b", true),
                new Entry(3, "Storage", "https://example.com/storage", "Other", "c", true)), directory.resolve("music"));
            Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().size() == 3 && service.snapshot().items().stream().allMatch(i -> i.status() == Status.FAILED), "Three failures");
            var items = service.snapshot().items();
            assertEquals(Category.EXTRACTOR_FAILURE, service.diagnostic(items.get(0).id()).category());
            assertEquals(Category.AUTH_REQUIRED, service.diagnostic(items.get(1).id()).category());
            assertEquals(Category.STORAGE_FAILURE, service.diagnostic(items.get(2).id()).category());
            for (var item : items) {
                assertEquals(item.id(), service.diagnostic(item.id()).operation());
                assertEquals(1, service.diagnostic(item.id()).attempt());
                assertEquals(Stage.DOWNLOAD, service.diagnostic(item.id()).stage());
            }
            service.clearFinished();
            assertEquals(3, service.snapshot().items().size());
            service.retry(items.get(0).id());
            Await.until(Duration.ofSeconds(10), () -> service.diagnostic(items.get(0).id()) != null, "Manual retry result");
            assertEquals(1, service.diagnostic(items.get(0).id()).attempt(), "Manual retry starts a new attempt budget");
            service.clearFailed();
            assertTrue(service.snapshot().items().isEmpty());
            for (var item : items) assertNull(service.diagnostic(item.id()));
        }
    }
}
