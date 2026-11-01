package com.darkmusic.client.library;

import com.darkmusic.client.testing.TestMusic;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ArtworkCacheTransferTest {
    private static Track track(int identity) throws Exception {
        var file = TestMusic.featured();
        return new Track("artwork-" + identity, file.getFileName().toString(), file, "", "", "", 1,
            Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
    }

    private static void closed(NativeImage image) {
        assertThrows(IllegalStateException.class, () -> image.getPixel(0, 0));
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final LinkedBlockingQueue<Runnable> render = new LinkedBlockingQueue<>();
        final List<NativeImage> images = new CopyOnWriteArrayList<>();
        final Map<Identifier, NativeImage> registered = new HashMap<>();
        final AtomicBoolean failRegistration = new AtomicBoolean();
        Runnable beforeReturn = () -> {
        };
        Runnable beforeTransfer = () -> {
        };
        final ArtworkCache cache = new ArtworkCache(worker, task -> {
            beforeTransfer.run();
            render.add(task);
        }, pixels -> {
            assertNotNull(pixels.values(), "use real embedded artwork");
            var image = ArtworkCache.prepareImage(pixels);
            images.add(image);
            beforeReturn.run();
            return image;
        }, (id, image) -> {
            if (failRegistration.compareAndSet(true, false)) throw new IllegalStateException("GPU registration denied");
            assertNull(registered.put(id, image));
        }, id -> {
            var image = registered.remove(id);
            assertNotNull(image);
            image.close();
        });

        void awaitWorker() throws Exception {
            worker.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        }

        void drain() {
            Runnable task;
            while ((task = render.poll()) != null) task.run();
        }

        public void close() throws Exception {
            cache.close();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            drain();
            assertTrue(registered.isEmpty());
            for (var image : images) closed(image);
        }
    }

    @Test
    void registrationFailureClosesTheRealNativeImageAndTheNextCoverStillWorks() throws Exception {
        try (var fixture = new Fixture()) {
            var failed = track(1);
            fixture.failRegistration.set(true);
            assertNull(fixture.cache.thumbnail(failed));
            fixture.awaitWorker();
            fixture.drain();
            assertEquals(1, fixture.images.size());
            closed(fixture.images.getFirst());
            assertEquals(0, fixture.cache.pendingCount());
            assertNull(fixture.cache.thumbnail(failed));
            var next = track(2);
            assertNull(fixture.cache.thumbnail(next));
            fixture.awaitWorker();
            fixture.drain();
            assertNotNull(fixture.cache.thumbnail(next));
            assertEquals(1, fixture.registered.size());
            assertEquals(0, fixture.cache.pendingCount());
        }
    }

    private static void hold(CountDownLatch entered, CountDownLatch release, AtomicBoolean first) {
        if (!first.compareAndSet(true, false)) return;
        entered.countDown();
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException expectedOnClose) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test
    void closeOnEitherSideOfNativeImageTransferNeverLeaksOrRegistersTheClosedCover() throws Exception {
        for (boolean afterClosedCheck : List.of(false, true)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var first = new AtomicBoolean(true);
            try (var fixture = new Fixture()) {
                Runnable pause = () -> hold(entered, release, first);
                if (afterClosedCheck) fixture.beforeTransfer = pause;
                else fixture.beforeReturn = pause;
                try {
                    fixture.cache.thumbnail(track(3));
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    assertEquals(1, fixture.images.size());
                    assertDoesNotThrow(() -> fixture.images.getFirst().getPixel(0, 0));
                    fixture.cache.close();
                    assertEquals(0, fixture.cache.pendingCount());
                } finally {
                    release.countDown();
                }
                assertTrue(fixture.worker.awaitTermination(10, TimeUnit.SECONDS));
                fixture.drain();
                closed(fixture.images.getFirst());
                assertTrue(fixture.registered.isEmpty());
                assertNull(fixture.cache.thumbnail(track(3)));
            }
        }
    }

    @Test
    void pendingAdmissionStaysAtEightUntilTheRealArtworkTransfersToRender() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        try (var fixture = new Fixture()) {
            fixture.beforeReturn = () -> hold(entered, release, first);
            var accepted = new ArrayList<Track>();
            try {
                for (int i = 0; i < 8; i++) {
                    var track = track(i);
                    accepted.add(track);
                    assertNull(fixture.cache.thumbnail(track));
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                for (int i = 8; i < 40; i++) assertNull(fixture.cache.thumbnail(track(i)));
                assertEquals(8, fixture.cache.pendingCount());
            } finally {
                release.countDown();
            }
            fixture.awaitWorker();
            assertEquals(8, fixture.images.size());
            fixture.drain();
            assertEquals(0, fixture.cache.pendingCount());
            assertEquals(8, fixture.registered.size());
            for (var track : accepted) assertNotNull(fixture.cache.thumbnail(track));
        }
    }
}
