package com.darkmusic.client.lyrics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the actual single-thread storage executor; test callers release it in finally.
 */
public final class LyricWriteGate implements AutoCloseable {
    private final ThreadPoolExecutor storage;
    private final CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);

    public LyricWriteGate(LyricsLookup lookup) throws InterruptedException {
        storage = lookup.storage();
        var entered = new CountDownLatch(1);
        storage.execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
            release.countDown();
            throw new AssertionError("Lyric storage did not enter the gate");
        }
    }

    public int queued() {
        return storage.getQueue().size();
    }

    public void saturate() {
        while (storage.getQueue().remainingCapacity() > 0) storage.execute(() -> {
        });
    }

    public void rejectNewWrites() {
        storage.shutdown();
    }

    public void release() {
        release.countDown();
    }

    public void close() throws InterruptedException {
        release();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
    }
}
