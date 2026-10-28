package com.darkmusic.client.tools;


import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.FileSystemException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Automatic retries are for temporary transport failures; manual retry remains available.
 */
public final class RetryPolicy {
    private RetryPolicy() {
    }

    public static boolean automatic(Throwable failure) {
        boolean transientFailure = false;
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof FileSystemException || failure instanceof IllegalArgumentException)
                return false;
            if (failure instanceof InterruptedIOException || failure instanceof ConnectException ||
                failure instanceof UnknownHostException || failure instanceof TimeoutException)
                transientFailure = true;
            if (failure instanceof DownloadDiagnostics.Failure f && f.category != null &&
                f.category != DownloadDiagnostics.Category.NETWORK_FAILURE && f.category != DownloadDiagnostics.Category.NETWORK_TIMEOUT &&
                f.category != DownloadDiagnostics.Category.RATE_LIMITED) return false;
            String text = (failure instanceof DownloadDiagnostics.Failure f ? f.excerpt : String.valueOf(failure.getMessage())).toLowerCase(Locale.ROOT);
            if (text.matches("(?s).*\\bhttp(?: error| status| status code)?[ :]+(?:501|505)\\b.*")) return false;
            boolean temporaryHttp = text.matches("(?s).*\\bhttp(?: error| status| status code)?[ :]+(?:408|429|5[0-9]{2})\\b.*");
            if (text.contains("private video") || text.contains("video is private") || text.contains("deleted") ||
                !temporaryHttp && (text.contains("not available") || text.contains("unavailable")) || text.contains("sign in") ||
                text.contains("authentication") || text.contains("age-restricted") || text.contains("drm") ||
                text.contains("unsupported url") || text.contains("no space") || text.contains("disk full") ||
                text.contains("not enough space") || text.contains("permission denied") || text.contains("access denied") ||
                text.matches("(?s).*\\bhttp(?: error| status| status code)?[ :]+(?:400|401|403|404|410)\\b.*"))
                return false;
            if (temporaryHttp ||
                text.contains("too many requests") || text.contains("timed out") || text.contains("timeout") ||
                text.contains("connection reset") || text.contains("connection refused") || text.contains("network is unreachable") ||
                text.contains("temporary failure in name resolution") || text.contains("remote end closed"))
                transientFailure = true;
        }
        return transientFailure;
    }
}
