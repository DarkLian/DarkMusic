package com.darkmusic.client;

import com.darkmusic.client.download.DownloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The download menus add no admission of their own: the service decides. The actions read the current service on
 * every call, because Reset to Defaults replaces it.
 */
class DownloadActionsTest {
    @TempDir
    Path temp;
    private DownloadService current;

    @Test
    void eachCallReachesTheCurrentService() {
        try (var first = new DownloadService(temp.resolve("first"), () -> {
        }); var second = new DownloadService(temp.resolve("second"), () -> {
        })) {
            var actions = new DownloadActions(() -> current);
            current = first;
            actions.rememberInput("https://example.com/first");
            current = second;
            actions.rememberInput("https://example.com/second");
            assertEquals("https://example.com/first", first.inputUrl());
            assertEquals("https://example.com/second", second.inputUrl());
            actions.cancelAll();
            actions.clearFinished();
            actions.clearFailed();
            assertEquals(0, second.snapshot().items().size());
        }
    }
}
