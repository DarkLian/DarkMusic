package com.darkmusic.client.lyrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LyricsWriteBarrierTest {
    @TempDir
    Path temp;

    @Test
    void fenceWaitsForEarlierWritesWithoutWaitingForLaterWork() throws Exception {
        var laterEntered = new CountDownLatch(1);
        var releaseLater = new CountDownLatch(1);
        try (var lookup = new LyricsLookup(temp); var gate = new LyricWriteGate(lookup)) {
            try {
                var events = new CopyOnWriteArrayList<String>();
                lookup.storage().execute(() -> events.add("earlier write"));
                var fence = lookup.writesSettled().thenRun(() -> events.add("fence"));
                lookup.storage().execute(() -> {
                    laterEntered.countDown();
                    try {
                        releaseLater.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertFalse(fence.isDone());
                gate.release();
                fence.get(5, TimeUnit.SECONDS);
                assertTrue(laterEntered.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("earlier write", "fence"), events);
            } finally {
                releaseLater.countDown();
            }
        }
    }

    @Test
    void boundedQueueRefusalReturnsAFailedFuture() throws Exception {
        try (var lookup = new LyricsLookup(temp); var gate = new LyricWriteGate(lookup)) {
            gate.saturate();
            var fence = lookup.writesSettled();
            assertTrue(fence.isCompletedExceptionally());
            assertInstanceOf(RejectedExecutionException.class,
                assertThrows(ExecutionException.class, () -> fence.get(2, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void storageShutdownReturnsAFailedFuture() throws Exception {
        try (var lookup = new LyricsLookup(temp)) {
            lookup.storage().shutdown();
            var fence = lookup.writesSettled();
            assertTrue(fence.isCompletedExceptionally());
            assertInstanceOf(RejectedExecutionException.class,
                assertThrows(ExecutionException.class, () -> fence.get(2, TimeUnit.SECONDS)).getCause());
        }
    }
}
