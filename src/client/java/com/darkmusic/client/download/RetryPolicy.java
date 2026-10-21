package com.darkmusic.client.download;

import java.util.Locale;

/**
 * Automatic retries are for temporary transport failures; manual retry remains available.
 */
public final class RetryPolicy {
    private RetryPolicy() {
    }

    public static boolean automatic(Throwable failure) {
        boolean transientFailure = false;
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof java.nio.file.FileSystemException || failure instanceof IllegalArgumentException)
                return false;
            if (failure instanceof java.io.InterruptedIOException || failure instanceof java.net.ConnectException ||
                failure instanceof java.net.UnknownHostException || failure instanceof java.util.concurrent.TimeoutException)
                transientFailure = true;
            String text = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
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
