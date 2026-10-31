package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reset clears the loudness cache once the old services report stopped, so the backend's awaitStopped must also cover
 * the cache writer, or a late write brings the file back after the reset. The case holds the writer busy, closes the
 * backend, and checks that it does not report stopped until the writer finishes. DownloadStopTest covers the
 * download history the same way.
 */
class StoppedMeansWritesDoneTest {
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
    void playbackIsNotStoppedWhileTheLoudnessCacheStillWrites() throws Exception {
        var backend = new JavaSoundBackend(Runnable::run, path -> {
            throw new IllegalStateException("nothing plays in this test");
        }, format -> {
            throw new IllegalStateException("nothing plays in this test");
        });
        var cache = new LoudnessCache();
        backend.loudness(cache);
        // The final write queues behind this task, as behind a slow disk.
        var release = hold(cache.writer());
        backend.close();
        assertFalse(backend.awaitStopped(1), "reported stopped while the final cache write was pending");
        release.countDown();
        assertTrue(backend.awaitStopped(5));
    }
}
