package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static com.darkmusic.client.download.DownloadDiagnostics.*;

class DownloadDiagnosticsTest {
    private Diagnostic diagnostic(String text, Stage stage) {
        return classify(new Failure(stage, stage == Stage.PROCESSING ? Tool.FFMPEG : Tool.YT_DLP, 1, text), stage,
            "https://www.bilibili.com/video/private-id?token=secret", 7, 2, "2026.08.19 | ffmpeg version 9.0.2 | ffprobe version 9.0.2");
    }

    @Test
    void recognizedFailuresKeepStageToolUncertaintyAndRetrySemantics() {
        var cases = Map.ofEntries(
            Map.entry("ERROR: [BiliBili] xyz Unable to extract initial state", Category.EXTRACTOR_FAILURE),
            Map.entry("Unsupported URL: https://private.example/x", Category.UNSUPPORTED_URL),
            Map.entry("HTTP Error 403: Forbidden", Category.ACCESS_DENIED),
            Map.entry("HTTP Error 429: Too Many Requests", Category.RATE_LIMITED),
            Map.entry("HTTP Error 503: Service Unavailable", Category.NETWORK_FAILURE),
            Map.entry("Login required", Category.AUTH_REQUIRED), Map.entry("Private video", Category.AUTH_REQUIRED),
            Map.entry("Incompatible JavaScript runtime", Category.TOOL_INCOMPATIBLE),
            Map.entry("Temporary failure in name resolution", Category.NETWORK_FAILURE),
            Map.entry("Connection timed out", Category.NETWORK_TIMEOUT), Map.entry("No space left on device", Category.STORAGE_FAILURE),
            Map.entry("Permission denied", Category.STORAGE_FAILURE), Map.entry("Unrecognized tool error", Category.UNKNOWN));
        cases.forEach((text, expected) -> {
            var result = diagnostic(text, Stage.ANALYSIS);
            assertEquals(expected, result.category(), text);
            assertEquals(Stage.ANALYSIS, result.stage());
            assertEquals(Source.BILIBILI, result.source());
            assertEquals(Tool.YT_DLP, result.tool());
            assertEquals(1, result.exitCode());
            assertEquals("2026.08.19", result.toolVersion());
        });
        assertFalse(diagnostic("Unable to extract initial state", Stage.ANALYSIS).automaticRetry());
        assertTrue(diagnostic("HTTP Error 503: Service Unavailable", Stage.DOWNLOAD).automaticRetry());
        assertEquals(Category.PROCESSING_FAILURE, diagnostic("Conversion failed", Stage.PROCESSING).category());
        assertEquals(Tool.FFMPEG, diagnostic("Conversion failed", Stage.PROCESSING).tool());
        assertEquals(Category.PROCESSING_FAILURE, diagnostic("Unable to extract an audio stream", Stage.PROCESSING).category(), "A conversion failure is not a website extractor failure");
        assertEquals(Category.OUTPUT_VALIDATION_FAILURE, diagnostic("Invalid audio", Stage.VALIDATION).category());
        assertEquals(Category.CANCELLED, classify(new CancellationException(), Stage.DOWNLOAD, "", 0, 1, "").category());
        for (Tool tool : new Tool[]{Tool.YT_DLP, Tool.FFMPEG}) {
            var missing = new Failure(Category.TOOL_MISSING, Stage.TOOLS, tool, null, "private-path", null);
            assertEquals(Category.TOOL_MISSING, classify(missing, Stage.TOOLS, "", 1, 1, "").category());
        }
    }

    @Test
    void reportsNeverCopyArbitraryOutputOrPrivateIdentifiers() {
        String raw = "C:\\Users\\PrivatePerson\\Music\\secret-file.mp3 /home/private-user/music/audio.opus\n"
            + "https://user:password@private.host/video-id?token=secret-token&session=secret-session\n"
            + "Authorization: Bearer secret-auth\nCookie: session=secret-cookie\n\u001b[31munknown failure\u001b[0m\n\ufffd"
            + "\n" + "sensitive".repeat(10000);
        for (String text : new String[]{raw, raw + "\nUnable to extract initial state", raw + "\nHTTP Error 403: Forbidden"}) {
            var result = diagnostic(text, Stage.DOWNLOAD);
            var report = result.report("2.5.1");
            for (String forbidden : new String[]{"PrivatePerson", "secret", "private-user", "private.host", "video-id", "sensitive", "password", "Authorization", "Cookie", "\u001b", "\ufffd"})
                assertFalse(report.contains(forbidden), forbidden);
            assertTrue(report.length() < 2000);
            assertTrue(result.detail().length() < 80);
        }
        assertEquals("", version("https://private.host/123.456?token=secret"));
        assertEquals("", version("Cookie: 123.456.789"));
        assertEquals("", version(raw));
    }

    @Test
    void typedStagesSurviveWrappingAndSpecificHttpPrecedesBroadUnavailableWords() throws Exception {
        var failure = assertThrows(Failure.class, () -> at(Stage.PROCESSING, Tool.FFMPEG, () -> {
            throw new IOException("Conversion rejected");
        }));
        var result = classify(new IOException("Friendly message", failure), Stage.DOWNLOAD, "https://youtu.be/id", 4, 3, "");
        assertEquals(Stage.PROCESSING, result.stage());
        assertEquals(Tool.FFMPEG, result.tool());
        assertEquals(Category.PROCESSING_FAILURE, result.category());
        var transientFailure = new Failure(Stage.DOWNLOAD, Tool.YT_DLP, 1, "HTTP Error 503: Service Unavailable");
        assertTrue(RetryPolicy.automatic(transientFailure));
        assertFalse(RetryPolicy.automatic(new Failure(Category.TOOL_MISSING, Stage.TOOLS, Tool.FFMPEG, null, "timeout", null)));
    }

    @Test
    void failedProbeKeepsItsExitCodeAndDoesNotBecomeAWebsiteFailure() {
        var runner = new ProcessRunner() {
            @Override
            public Result run(java.util.List<String> args, java.nio.file.Path directory, Token token,
                              java.time.Duration timeout, int limit, java.util.function.Consumer<String> output) {
                return new Result(7, "Unable to extract an audio stream https://private.host/id?token=secret");
            }
        };
        var tools = new Toolchain(java.nio.file.Path.of("yt-dlp"), java.nio.file.Path.of("ffmpeg"), java.nio.file.Path.of("ffprobe"), "", "");
        var failure = assertThrows(Failure.class, () -> DownloadEncoding.codec(runner, tools, java.nio.file.Path.of("audio"), java.nio.file.Path.of("."), new ProcessRunner.Token()));
        var result = classify(failure, Stage.DOWNLOAD, "https://www.bilibili.com/video/id", 1, 1, "");
        assertEquals(Category.OUTPUT_VALIDATION_FAILURE, result.category());
        assertEquals(Stage.VALIDATION, result.stage());
        assertEquals(Tool.FFPROBE, result.tool());
        assertEquals(7, result.exitCode());
        assertFalse(result.report("2.5.1").contains("secret"));
    }
}
