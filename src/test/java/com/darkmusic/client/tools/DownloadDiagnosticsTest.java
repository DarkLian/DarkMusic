package com.darkmusic.client.tools;

import com.darkmusic.client.download.DownloadEncoding;
import com.darkmusic.client.download.DownloadJson;
import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static com.darkmusic.client.tools.DownloadDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

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
            Map.entry("Requested format is not available. Use --list-formats for a list of available formats", Category.TOOL_INCOMPATIBLE),
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
            public Result run(List<String> args, Path directory, Token token,
                              Duration timeout, int limit, Consumer<String> output) {
                return new Result(7, "Unable to extract an audio stream https://private.host/id?token=secret");
            }
        };
        var tools = new Toolchain(Path.of("yt-dlp"), Path.of("ffmpeg"), Path.of("ffprobe"), "", "");
        var failure = assertThrows(Failure.class, () -> DownloadEncoding.codec(runner, tools, Path.of("audio"), Path.of("."), new ProcessRunner.Token()));
        var result = classify(failure, Stage.DOWNLOAD, "https://www.bilibili.com/video/id", 1, 1, "");
        assertEquals(Category.OUTPUT_VALIDATION_FAILURE, result.category());
        assertEquals(Stage.VALIDATION, result.stage());
        assertEquals(Tool.FFPROBE, result.tool());
        assertEquals(7, result.exitCode());
        assertFalse(result.report("2.5.1").contains("secret"));
    }

    @Test
    void botVerificationIsNotPrivateContentAndKeepsManualRetryAvailable() {
        String text = "ERROR: Sign in to confirm you're not a bot";
        assertEquals(Text.get("download.error.bot_check"), DownloadJson.friendly(text));
        var result = diagnostic(text, Stage.ANALYSIS);
        assertEquals(Category.BOT_CHECK, result.category());
        assertTrue(result.canRetry());
        assertFalse(result.automaticRetry());
        assertTrue(result.toolsRelated());
        assertEquals(Category.AUTH_REQUIRED, diagnostic("This video is private", Stage.ANALYSIS).category());
    }

    @Test
    void causeShapesKeepCancellationStorageTypedPrecedenceAndTheDepthLimit() {
        record Case(Throwable error, Category category, Stage stage, Tool tool) {
        }
        var cases = List.of(
            new Case(new IOException("outer", new CancellationException()), Category.CANCELLED, Stage.COMMIT, Tool.NONE),
            new Case(new IOException("outer", new InterruptedException()), Category.CANCELLED, Stage.COMMIT, Tool.NONE),
            new Case(new IOException("HTTP 429", new FileSystemException("private")), Category.STORAGE_FAILURE, Stage.COMMIT, Tool.NONE),
            new Case(new Failure(Category.TOOL_MISSING, Stage.TOOLS, Tool.FFMPEG, null, "HTTP 429", new FileSystemException("private")), Category.TOOL_MISSING, Stage.TOOLS, Tool.FFMPEG),
            new Case(new Failure(Category.TOOL_MISSING, Stage.TOOLS, Tool.FFMPEG, null, "", new CancellationException()), Category.CANCELLED, Stage.TOOLS, Tool.FFMPEG),
            new Case(new IOException("outer", new Failure(Stage.PROCESSING, Tool.FFMPEG, 9, "unrecognized")), Category.PROCESSING_FAILURE, Stage.PROCESSING, Tool.FFMPEG),
            new Case(new SocketTimeoutException(), Category.NETWORK_TIMEOUT, Stage.COMMIT, Tool.NONE),
            new Case(new UnknownHostException(), Category.NETWORK_FAILURE, Stage.COMMIT, Tool.NONE));
        for (var entry : cases) {
            var result = classify(entry.error(), Stage.COMMIT, "invalid", 19, 3, "2026.1 | ffmpeg version 8.0 | ffprobe version 8.0");
            assertEquals(entry.category(), result.category());
            assertEquals(entry.stage(), result.stage());
            assertEquals(entry.tool(), result.tool());
            assertEquals(19, result.operation());
            assertEquals(3, result.attempt());
        }
        Throwable included = new CancellationException();
        for (int i = 0; i < 15; i++) included = new IOException("", included);
        assertEquals(Category.CANCELLED, classify(included, Stage.COMMIT, "", 0, 1, "").category());
        Throwable excluded = new IOException("", included);
        assertEquals(Category.UNKNOWN, classify(excluded, Stage.COMMIT, "", 0, 1, "").category());
    }

    @Test
    void everyHttpBranchAndStageFallbackKeepTheirFixedSafeDetail() {
        var categories = Map.of(401, Category.AUTH_REQUIRED, 403, Category.ACCESS_DENIED, 404, Category.CONTENT_UNAVAILABLE,
            408, Category.NETWORK_TIMEOUT, 410, Category.CONTENT_UNAVAILABLE, 429, Category.RATE_LIMITED, 500, Category.NETWORK_FAILURE);
        categories.forEach((code, expected) -> {
            var result = diagnostic("HTTP status code: " + code + " private video timeout", Stage.DOWNLOAD);
            assertEquals(expected, result.category());
            assertEquals("HTTP " + code, result.detail());
        });
        for (Stage stage : Stage.values()) {
            var expected = stage == Stage.PROCESSING ? Category.PROCESSING_FAILURE : stage == Stage.VALIDATION ? Category.OUTPUT_VALIDATION_FAILURE : Category.UNKNOWN;
            assertEquals(expected, classify(new IOException(), stage, "", 0, 1, "").category());
        }
        var first = new Failure(Stage.DOWNLOAD, Tool.YT_DLP, 7, "timeout").versions("2026.9 | ffmpeg version 8.1 | ffprobe version 8.1");
        var outer = new Failure(Category.ACCESS_DENIED, Stage.ANALYSIS, Tool.FFPROBE, 11, "HTTP 403", first);
        var result = classify(outer, Stage.COMMIT, "https://youtu.be/abcdefghijk", 1, 1, "2025.1 | ffmpeg version 7.0 | ffprobe version 7.0");
        assertEquals(Category.ACCESS_DENIED, result.category());
        assertEquals(11, result.exitCode());
        assertEquals("7.0", result.toolVersion(), "the first typed failure owns the context and verified versions");
        assertEquals(Source.YOUTUBE, result.source());
        assertEquals("2026.9", classify(first, Stage.COMMIT, "", 1, 1, "").toolVersion());
    }

}
