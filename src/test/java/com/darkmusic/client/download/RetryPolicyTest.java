package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    @Test
    void temporaryNetworkFailuresRetryButPermanentFailuresRequireManualRetry() {
        assertFalse(RetryPolicy.automatic(new IOException("HTTP Error 501: Not Implemented")));
        assertFalse(RetryPolicy.automatic(new IOException("HTTP Error 505: HTTP Version Not Supported")));
        for (String message : List.of("HTTP Error 408", "HTTP Error 429", "HTTP Error 503", "HTTP Error 503: Service Unavailable",
            "HTTP status code 502: Bad Gateway", "HTTP Error 504: Gateway Timeout", "connection reset", "timed out"))
            assertTrue(RetryPolicy.automatic(new IOException("localized message", new IOException(message))), message);
        for (String message : List.of("HTTP Error 403", "HTTP Error 404", "Private video", "Video deleted", "DRM protected", "Unsupported URL", "No space left on device", "ffmpeg not found", "conversion failed"))
            assertFalse(RetryPolicy.automatic(new IOException(message)), message);
        assertFalse(RetryPolicy.automatic(new java.nio.file.AccessDeniedException("destination")));
        assertFalse(RetryPolicy.automatic(new IOException("connection reset", new IOException("video is private"))));
        assertFalse(RetryPolicy.automatic(new IOException("HTTP Error 503: Service Unavailable", new java.nio.file.AccessDeniedException("destination"))));
        assertFalse(RetryPolicy.automatic(new IOException("HTTP Error 503: Service Unavailable", new IOException("video is private"))));
        assertFalse(RetryPolicy.automatic(new IOException("Video unavailable")));
    }
}
