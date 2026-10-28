package com.darkmusic.client.tools;

import com.darkmusic.client.text.Text;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.FileSystemException;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/**
 * Bounded interpretations of failures. Reports contain allowlisted facts, never subprocess lines.
 */
public final class DownloadDiagnostics {
    public enum Category {
        EXTRACTOR_FAILURE, BOT_CHECK, AUTH_REQUIRED, ACCESS_DENIED, RATE_LIMITED, NETWORK_FAILURE, NETWORK_TIMEOUT,
        UNSUPPORTED_URL, TOOL_MISSING, TOOL_INCOMPATIBLE, STORAGE_FAILURE, PROCESSING_FAILURE,
        OUTPUT_VALIDATION_FAILURE, CONTENT_UNAVAILABLE, CANCELLED, UNKNOWN
    }

    public enum Stage {TOOLS, ANALYSIS, COLLECTION, DOWNLOAD, PROCESSING, VALIDATION, OPTIONAL, COMMIT}

    public enum Tool {
        YT_DLP, FFMPEG, FFPROBE, JAVASCRIPT, TOOLS, NONE;

        public String display() {
            return switch (this) {
                case YT_DLP -> "yt-dlp";
                case FFMPEG -> "FFmpeg";
                case FFPROBE -> "FFprobe";
                case JAVASCRIPT -> "Deno / Node";
                case TOOLS -> Text.get("text.darkmusic.download_tools");
                case NONE -> Text.get("download.diag.not_available");
            };
        }
    }

    public enum Source {YOUTUBE, BILIBILI, OTHER, UNKNOWN}

    public record Diagnostic(long operation, int attempt, Category category, Stage stage, Source source, Tool tool,
                             Integer exitCode, String toolVersion, String detail, boolean automaticRetry) {
        public String summary() {
            return Text.get(key("summary", category));
        }

        public String explanation() {
            return Text.get(key("cause", category));
        }

        public String action() {
            return Text.get(key("action", category));
        }

        public boolean toolsRelated() {
            return category == Category.TOOL_MISSING || category == Category.TOOL_INCOMPATIBLE || category == Category.EXTRACTOR_FAILURE || category == Category.BOT_CHECK;
        }

        public boolean canRetry() {
            return category != Category.CANCELLED && category != Category.CONTENT_UNAVAILABLE && category != Category.UNSUPPORTED_URL && category != Category.AUTH_REQUIRED;
        }

        public String report(String modVersion) {
            return "DarkMusic " + version(modVersion) + "\nOperation: " + operation + " / " + attempt
                + "\nSource: " + source + "\nStage: " + stage + "\nCategory: " + category + "\nTool: " + tool
                + (toolVersion.isEmpty() ? "" : " " + toolVersion) + "\nExit code: " + (exitCode == null ? "—" : exitCode)
                + "\nDetail: " + detail + "\n" + action();
        }
    }

    /**
     * Kept only until classification; output is bounded and must never be sent directly to a widget.
     */
    public static final class Failure extends IOException {
        public final Stage stage;
        public final Tool tool;
        public final Category category;
        public final Integer exitCode;
        public final String excerpt;
        private String verifiedVersions = "";

        public Failure versions(String value) {
            verifiedVersions = value;
            return this;
        }

        public Failure(Stage stage, Tool tool, Integer exitCode, String output) {
            this(null, stage, tool, exitCode, output, null);
        }

        public Failure(Category category, Stage stage, Tool tool, Integer exitCode, String output, Throwable cause) {
            super("Download operation failed at " + stage, cause);
            this.category = category;
            this.stage = stage;
            this.tool = tool;
            this.exitCode = exitCode;
            this.excerpt = tail(output);
        }
    }

    @FunctionalInterface
    public interface Checked<T> {
        T get() throws Exception;
    }

    public static <T> T at(Stage stage, Tool tool, Checked<T> operation) throws Exception {
        try {
            return operation.get();
        } catch (Failure | CancellationException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new Failure(null, stage, tool, null, messages(e), e);
        }
    }

    public static Diagnostic classify(Throwable error, Stage fallback, String url, long operation, int attempt, String knownVersions) {
        Failure typed = null;
        boolean cancelled = false, storage = false;
        int depth = 0;
        for (Throwable cause = error; cause != null && depth++ < 16; cause = cause.getCause()) {
            if (typed == null && cause instanceof Failure f) typed = f;
            cancelled |= cause instanceof CancellationException || cause instanceof InterruptedException;
            storage |= cause instanceof FileSystemException;
        }
        Stage stage = typed == null ? fallback : typed.stage;
        Tool tool = typed == null ? Tool.NONE : typed.tool;
        String text = (typed == null ? messages(error) : typed.excerpt + "\n" + messages(typed.getCause())).toLowerCase(Locale.ROOT);
        Category category = cancelled ? Category.CANCELLED : typed == null ? null : typed.category;
        String detail = "";
        var http = Pattern.compile("\\bhttp(?: error| status| status code)?[ :]+([45][0-9]{2})\\b").matcher(text);
        int code = http.find() ? Integer.parseInt(http.group(1)) : 0;
        if (category == null) {
            if (storage) category = Category.STORAGE_FAILURE;
            else if (code == 429) category = Category.RATE_LIMITED;
            else if (code == 403) category = Category.ACCESS_DENIED;
            else if (code == 401) category = Category.AUTH_REQUIRED;
            else if (code == 404 || code == 410) category = Category.CONTENT_UNAVAILABLE;
            else if (code == 408) category = Category.NETWORK_TIMEOUT;
            else if (code >= 500) category = Category.NETWORK_FAILURE;
            else if (contains(text, "not a bot")) category = Category.BOT_CHECK;
            else if (contains(text, "private video", "video is private", "login required", "sign in to", "requires authentication", "age-restricted", "drm"))
                category = Category.AUTH_REQUIRED;
            else if (contains(text, "unsupported url")) category = Category.UNSUPPORTED_URL;
            else if (contains(text, "no space", "disk full", "not enough space", "permission denied", "access denied", "not writable"))
                category = Category.STORAGE_FAILURE;
            else if (contains(text, "javascript runtime", "js runtime", "challenge solving failed", "challenge solver", "unrecognized option", "unknown option", "unsupported option"))
                category = Category.TOOL_INCOMPATIBLE;
            else if ((tool == Tool.YT_DLP || tool == Tool.NONE && (stage == Stage.ANALYSIS || stage == Stage.COLLECTION))
                && contains(text, "unable to extract", "failed to extract", "extractor error"))
                category = Category.EXTRACTOR_FAILURE;
            else if (contains(text, "timed out", "timeout") || error instanceof SocketTimeoutException)
                category = Category.NETWORK_TIMEOUT;
            else if (contains(text, "name resolution", "unable to resolve", "connection refused", "connection reset", "network is unreachable", "remote end closed") || error instanceof UnknownHostException)
                category = Category.NETWORK_FAILURE;
            else if (contains(text, "requested format is not available"))
                category = Category.TOOL_INCOMPATIBLE;
            else if (contains(text, "deleted video", "video unavailable", "video is unavailable", "not available"))
                category = Category.CONTENT_UNAVAILABLE;
            else if (stage == Stage.PROCESSING) category = Category.PROCESSING_FAILURE;
            else if (stage == Stage.VALIDATION) category = Category.OUTPUT_VALIDATION_FAILURE;
            else category = Category.UNKNOWN;
        }
        // Only fixed recognized phrases and numeric HTTP status codes leave this boundary.
        // Unknown text is intentionally omitted, rather than claiming regex redaction makes arbitrary output safe.
        if (code != 0) detail = "HTTP " + code;
        else if (category == Category.EXTRACTOR_FAILURE && text.contains("unable to extract initial state"))
            detail = "Unable to extract initial state";
        else if (category == Category.EXTRACTOR_FAILURE && text.contains("unable to extract"))
            detail = "Unable to extract video information";
        else if (text.contains("permission denied")) detail = "Permission denied";
        else if (contains(text, "no space", "disk full")) detail = "Insufficient disk space";
        else if (contains(text, "timed out", "timeout")) detail = "Request timed out";
        Source source;
        try {
            String identified = SourceUrls.source(url);
            source = identified.equals("YouTube") ? Source.YOUTUBE : identified.equals("Bilibili") ? Source.BILIBILI : Source.OTHER;
        } catch (RuntimeException ignored) {
            source = Source.UNKNOWN;
        }
        if (typed != null && !typed.verifiedVersions.isEmpty()) knownVersions = typed.verifiedVersions;
        if (stage == Stage.TOOLS) knownVersions = "";
        String toolVersion = switch (tool) {
            case YT_DLP -> versionPart(knownVersions, 0);
            case FFMPEG -> versionPart(knownVersions, 1);
            case FFPROBE -> versionPart(knownVersions, 2);
            default -> "";
        };
        return new Diagnostic(operation, attempt, category, stage, source, tool,
            typed == null ? null : typed.exitCode, toolVersion, detail,
            category != Category.CANCELLED && category != Category.EXTRACTOR_FAILURE && category != Category.AUTH_REQUIRED && category != Category.BOT_CHECK && RetryPolicy.automatic(error));
    }

    static String version(String value) {
        if (value == null) return "";
        var match = Pattern.compile("^(?:(?:yt-dlp|ffmpeg version|ffprobe version) )?[vn]?([0-9]{1,4}(?:[.][0-9]{1,4}){1,3}(?:[-+][0-9]+)?)(?:\\s|$)").matcher(value.substring(0, Math.min(256, value.length())));
        return match.find() ? match.group(1) : "";
    }

    private static String versionPart(String versions, int part) {
        if (versions == null) return "";
        String[] values = versions.split(" \\| ");
        return values.length > part ? version(values[part]) : "";
    }

    private static String key(String kind, Category category) {
        return "download.diag." + kind + "." + category.name().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String text, String... phrases) {
        for (String p : phrases) if (text.contains(p)) return true;
        return false;
    }

    private static String tail(String value) {
        if (value == null) return "";
        return value.substring(Math.max(0, value.length() - 8192));
    }

    private static String messages(Throwable value) {
        var text = new StringBuilder();
        for (int depth = 0; value != null && depth < 16; depth++, value = value.getCause()) {
            if (value instanceof Failure f) text.append(f.excerpt);
            else text.append(tail(value.getMessage()));
            text.append('\n');
            if (text.length() > 8192) break;
        }
        return tail(text.toString());
    }

    private DownloadDiagnostics() {
    }
}
