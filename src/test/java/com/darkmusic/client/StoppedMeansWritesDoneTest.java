package com.darkmusic.client;

import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.download.DownloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reset clears the loudness cache and the download history once the old services report stopped. A service's
 * awaitStopped must therefore also cover the writers of those files, or a late write brings them back after the
 * reset. Each case holds the writer busy, closes the service, and checks that it does not report stopped until the
 * writer finishes. The writers are private executors, reached by reflection.
 */
class StoppedMeansWritesDoneTest {
    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

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
        var loudness = JavaSoundBackend.class.getDeclaredField("loudness");
        loudness.setAccessible(true);
        loudness.set(backend, cache);
        // The final write queues behind this task, as behind a slow disk.
        var release = hold((ExecutorService) field(cache, "writer"));
        backend.close();
        assertFalse(backend.awaitStopped(1), "reported stopped while the final cache write was pending");
        release.countDown();
        assertTrue(backend.awaitStopped(5));
    }

    @Test
    void downloadsAreNotStoppedWhileABookkeepingRepairRuns(@TempDir Path data) throws Exception {
        var service = new DownloadService(data, () -> {
        });
        var release = hold((ExecutorService) field(service, "bookkeeping"));
        service.close();
        assertFalse(service.awaitStopped(1), "reported stopped while a history repair was still writing");
        release.countDown();
        assertTrue(service.awaitStopped(5));
    }
}
