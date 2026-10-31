package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reset clears the download history once the old service reports stopped, so awaitStopped must also cover the
 * history repair, or a late write brings the file back after the reset. The case holds the repair worker busy, closes
 * the service, and checks that it does not report stopped until the worker finishes.
 */
class DownloadStopTest {
    private static CountDownLatch hold(ExecutorService writer) throws InterruptedException {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        writer.execute(() -> {
            started.countDown();
            // A write in progress finishes even when close interrupts its thread.
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException ignored) {
                }
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        return release;
    }

    @Test
    void downloadsAreNotStoppedWhileABookkeepingRepairRuns(@TempDir Path data) throws Exception {
        var service = new DownloadService(data, () -> {
        });
        var release = hold(service.bookkeeping());
        service.close();
        assertFalse(service.awaitStopped(1), "reported stopped while a history repair was still writing");
        release.countDown();
        assertTrue(service.awaitStopped(5));
    }
}
