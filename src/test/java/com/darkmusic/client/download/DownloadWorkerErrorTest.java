package com.darkmusic.client.download;

import com.darkmusic.client.testing.Await;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.darkmusic.client.download.DownloadModels.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadWorkerErrorTest {
    @TempDir
    Path temp;

    @Test
    void codecErrorsReleaseKeysAndAllThreeWorkersContinue() throws Exception {
        var attempts = new AtomicInteger();
        var runner = new ProcessRunner() {
            @Override
            public Result run(List<String> args, Path cwd, Token token, Duration timeout, int max, java.util.function.Consumer<String> output) {
                if (args.contains("--version") || args.contains("-version")) return new Result(0, "2026.08.19");
                if (args.contains("--help"))
                    return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --no-plugin-dirs");
                if (args.contains("-encoders")) return new Result(0, "libmp3lame");
                attempts.incrementAndGet();
                throw new NoClassDefFoundError("injected codec linkage failure");
            }
        };
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        try (var service = new DownloadService(temp, () -> {
        }, runner)) {
            service.configure(new ToolPaths(executable, executable, executable, ""));
            var entries = List.of(new Entry(1, "A", "https://youtu.be/a", "Youtube", "a", true), new Entry(2, "B", "https://youtu.be/b", "Youtube", "b", true), new Entry(3, "C", "https://youtu.be/c", "Youtube", "c", true));
            for (int batch = 0; batch < 2; batch++) {
                service.enqueueSelected(entries, temp.resolve("music"));
                Await.until(Duration.ofSeconds(10), () -> service.snapshot().items().size() == 3 && service.snapshot().items().stream().allMatch(i -> i.status() == Status.FAILED), "All workers report failure");
                service.clearFailed();
                assertTrue(service.snapshot().items().isEmpty());
            }
            assertEquals(6, attempts.get());
        }
    }
}
