package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    @Test
    void temporaryNetworkFailuresRetryButPermanentFailuresRequireManualRetry() {
        for (String message : List.of("HTTP Error 408", "HTTP Error 429", "HTTP Error 503", "connection reset", "timed out"))
            assertTrue(RetryPolicy.automatic(new IOException("localized message", new IOException(message))), message);
        for (String message : List.of("HTTP Error 403", "HTTP Error 404", "Private video", "Video deleted", "DRM protected", "Unsupported URL", "No space left on device", "ffmpeg not found", "conversion failed"))
            assertFalse(RetryPolicy.automatic(new IOException(message)), message);
        assertFalse(RetryPolicy.automatic(new java.nio.file.AccessDeniedException("destination")));
        assertFalse(RetryPolicy.automatic(new IOException("connection reset", new IOException("video is private"))));
    }
}
