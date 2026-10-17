package com.darkmusic.client;

import com.darkmusic.client.download.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    @TempDir
    Path temp;

    List<String> command(String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), "-cp", Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), ProcessFixture.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    @Test
    void passesUnicodeAndShellMetacharactersAsOneLiteralArgument() throws Exception {
        // The Java launcher uses the Windows native code page for argv; real-tool tests also cover Simplified Chinese.
        String value = "音樂 日本語 & $(touch SHOULD_NOT_EXIST) ; --exec=bad";
        var r = new ProcessRunner().run(command("echo", value), temp, new ProcessRunner.Token(), Duration.ofSeconds(10), 32000, s -> {
        });
        assertEquals(0, r.exit());
        assertEquals(value, r.output().strip());
        assertFalse(Files.exists(temp.resolve("SHOULD_NOT_EXIST")));
    }

    @Test
    void boundedOutputKeepsFinalErrorsAfterLongProgressLogs() throws Exception {
        var result = new ProcessRunner().run(command("output"), temp, new ProcessRunner.Token(), Duration.ofSeconds(10), 1000, line -> {
        });
        assertEquals(1000, result.output().length());
        assertTrue(result.output().contains("HTTP 429 final diagnostic"));
    }

    @Test
    void timeoutKillsTheProcess() throws Exception {
        assertThrows(java.io.IOException.class, () -> new ProcessRunner().run(command("wait"), temp, new ProcessRunner.Token(), Duration.ofMillis(300), 32000, s -> {
        }));
    }

    @Test
    void cancellationTerminatesChildProcesses() throws Exception {
        var token = new ProcessRunner.Token();
        var executor = Executors.newSingleThreadExecutor();
        Path pid = temp.resolve("child.pid");
        try {
            Future<?> running = executor.submit(() -> {
                try {
                    new ProcessRunner().run(command("tree", pid.toString()), temp, token, Duration.ofSeconds(30), 32000, s -> {
                    });
                } catch (CancellationException expected) {
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            DownloadTest.await(() -> Files.exists(pid));
            long child = Long.parseLong(Files.readString(pid));
            Thread.sleep(150);
            token.cancel();
            running.get(10, TimeUnit.SECONDS);
            DownloadTest.await(() -> ProcessHandle.of(child).map(h -> !h.isAlive()).orElse(true));
        } finally {
            token.cancel();
            executor.shutdownNow();
        }
    }
}
