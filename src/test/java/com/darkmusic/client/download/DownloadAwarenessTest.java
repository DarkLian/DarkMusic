package com.darkmusic.client.download;

import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.config.DownloadFormat;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static com.darkmusic.client.download.DownloadModels.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadAwarenessTest {
    @TempDir
    Path temp;

    @Test
    void concurrentRequestsKeepFormatIdentityAndCoalesceRepeatedMixEntries() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ProcessRunner runner = new ProcessRunner() {
            private final Runner tools = new Runner();

            @Override
            public Result run(List<String> args, Path cwd, Token token, Duration timeout, int max, Consumer<String> line) throws InterruptedException {
                if (args.contains("--version") || args.contains("-version") || args.contains("--help") || args.contains("-encoders"))
                    return tools.run(args, cwd, token, timeout, max, line);
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                token.check();
                return new Result(1, "Private video");
            }
        };
        try (var service = new DownloadService(temp.resolve("service"), () -> {
        }, runner)) {
            service.configure(tools());
            service.format(DownloadFormat.ORIGINAL);
            service.enqueueSelected(List.of(entry(), entry()), temp.resolve("downloads"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(1, service.snapshot().items().size(), "Repeated Mix entries share one active same-format job");
            service.format(DownloadFormat.MP3);
            service.enqueueSelected(List.of(entry()), temp.resolve("downloads"));
            Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().size() == 2, "A different requested format is not suppressed");
            release.countDown();
            Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().stream().allMatch(i -> i.status() == Status.FAILED), "Both fake jobs finish independently");
        } finally {
            release.countDown();
        }
    }

    static final class Runner extends ProcessRunner {
        final List<List<String>> commands = new CopyOnWriteArrayList<>();

        @Override
        public Result run(List<String> args, Path cwd, Token token, Duration timeout, int max, Consumer<String> line) {
            token.check();
            commands.add(args);
            if (args.contains("--version") || args.contains("-version")) return new Result(0, "2026.08.19");
            if (args.contains("--help"))
                return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --extract-audio --embed-metadata --embed-thumbnail --print --js-runtimes --remote-components --no-plugin-dirs");
            if (args.contains("-encoders")) return new Result(0, "libmp3lame");
            return new Result(1, "Private video");
        }
    }

    ToolPaths tools() {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new ToolPaths(java, java, java, "");
    }

    Entry entry() {
        return new Entry(1, "Different name", "https://youtu.be/abcDEFG_123", "Youtube", "abcDEFG_123", true);
    }

    Track known() throws Exception {
        Path root = Files.createDirectories(temp.resolve("registered"));
        Path p = Files.writeString(root.resolve("renamed.txt"), "Disposable index fixture, not an audio asset");
        Files.writeString(root.resolve(".darkmusic-downloads.json"), "{\"Youtube:abcDEFG_123:original\":\"renamed.txt\"}");
        SourceLinks.record(p, "https://youtu.be/abcDEFG_123", "Youtube:abcDEFG_123:original");
        return new Track("root", "renamed.txt", p, "Existing title", "", "", 180, Files.size(p), Files.getLastModifiedTime(p).toMillis(), "");
    }

    @Test
    void confirmedOtherFolderRequiresChoiceAndExplicitSkipCreatesSkippedStatus() throws Exception {
        var t = known();
        var runner = new Runner();
        try (var index = new LibrarySourceIndex(temp.resolve("index")); var service = new DownloadService(temp.resolve("service"), () -> {
        }, runner)) {
            index.update(List.of(t), Map.of(t.file().getParent(), t.rootId()), true);
            index.settled().get(10, TimeUnit.SECONDS);
            service.awareness(index);
            service.configure(tools());
            service.format(DownloadFormat.ORIGINAL);
            service.enqueueSelected(List.of(entry(), entry()), temp.resolve("another-folder"));
            Await.until(Duration.ofSeconds(10), () -> !service.snapshot().analyzing(), "Preparation");
            var choice = service.duplicateChoice();
            assertNotNull(choice);
            assertTrue(service.snapshot().items().isEmpty());
            assertTrue(runner.commands.stream().noneMatch(c -> c.contains("--extract-audio")));
            service.resolveDuplicateChoice(choice.id(), true);
            Await.until(Duration.ofSeconds(10), () -> !service.snapshot().analyzing() && !service.snapshot().items().isEmpty(), "Skip choice");
            assertTrue(service.snapshot().items().stream().allMatch(i -> i.status() == Status.SKIPPED));
            assertNull(service.duplicateChoice());
            assertTrue(Files.exists(t.file()));
        }
    }

    @Test
    void differentFormatsOrDisappearingFilesDoNotGetSilentlySkipped() throws Exception {
        var t = known();
        var runner = new Runner();
        try (var index = new LibrarySourceIndex(temp.resolve("index")); var service = new DownloadService(temp.resolve("service"), () -> {
        }, runner)) {
            index.update(List.of(t), Map.of(t.file().getParent(), t.rootId()), true);
            index.settled().get(10, TimeUnit.SECONDS);
            service.awareness(index);
            service.configure(tools());
            service.format(DownloadFormat.MP3);
            service.enqueueSelected(List.of(entry()), temp.resolve("new-folder"));
            Await.until(Duration.ofSeconds(10), () -> !service.snapshot().analyzing(), "Preparing format warning");
            assertNotNull(service.duplicateChoice());
            service.resolveDuplicateChoice(service.duplicateChoice().id(), true);
            Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().stream().anyMatch(i -> i.status() == Status.FAILED), "Different format reaches downloader");
            assertTrue(service.snapshot().items().stream().noneMatch(i -> i.status() == Status.SKIPPED));
            service.clearFailed();
            service.format(DownloadFormat.ORIGINAL);
            service.enqueueSelected(List.of(entry()), temp.resolve("new-folder"));
            Await.until(Duration.ofSeconds(10), () -> !service.snapshot().analyzing(), "Preparing second warning");
            var choice = service.duplicateChoice();
            assertNotNull(choice);
            Files.delete(t.file());
            service.resolveDuplicateChoice(choice.id(), true);
            Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().stream().anyMatch(i -> i.status() == Status.FAILED), "Deleted indexed file does not skip valid request");
            assertTrue(service.snapshot().items().stream().noneMatch(i -> i.status() == Status.SKIPPED));
        }
    }

    @Test
    void noIndexAndDownloadAnywayKeepExistingPipelineAndCancelPreservesFile() throws Exception {
        var t = known();
        var runner = new Runner();
        try (var index = new LibrarySourceIndex(temp.resolve("index")); var service = new DownloadService(temp.resolve("service"), () -> {
        }, runner)) {
            index.update(List.of(t), Map.of(t.file().getParent(), t.rootId()), true);
            index.settled().get(10, TimeUnit.SECONDS);
            service.awareness(index);
            service.configure(tools());
            service.enqueueSelected(List.of(entry()), temp.resolve("new-folder"));
            Await.until(Duration.ofSeconds(10), () -> !service.snapshot().analyzing(), "Pending review");
            service.cancelDuplicateChoice(service.duplicateChoice().id());
            assertTrue(service.snapshot().items().isEmpty());
            assertTrue(Files.exists(t.file()));
            service.enqueueSelected(List.of(entry()), temp.resolve("new-folder"));
            Await.until(Duration.ofSeconds(10), () -> !service.snapshot().analyzing(), "Review again");
            service.resolveDuplicateChoice(service.duplicateChoice().id(), false);
            Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().stream().anyMatch(i -> i.status() == Status.FAILED), "Explicit download anyway uses pipeline");
            service.clearFailed();
            service.awareness(null);
            service.enqueueSelected(List.of(entry()), temp.resolve("new-folder"));
            Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().stream().anyMatch(i -> i.status() == Status.FAILED), "Unavailable awareness preserves existing behavior");
            assertNull(service.duplicateChoice());
        }
    }
}
