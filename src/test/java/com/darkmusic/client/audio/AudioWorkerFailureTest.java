package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.*;

import com.darkmusic.client.library.Track;

import static org.junit.jupiter.api.Assertions.*;

class AudioWorkerFailureTest {
    @Test
    void codecErrorDoesNotKillThePlaybackWorker() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (var backend = new JavaSoundBackend(Runnable::run, p -> {
            if (count.getAndIncrement() == 0) throw new NoClassDefFoundError("injected");
            throw new java.io.IOException("second request");
        }, f -> {
            throw new AssertionError("No output for failed decoder");
        })) {
            var track = new Track("r", "unopened", Path.of("unopened"), "", "", "", 1, 0, 0, "");
            for (int i = 0; i < 2; i++) {
                var failed = new CompletableFuture<String>();
                backend.play(track, () -> fail("Unexpected finish"), failed::complete);
                assertNotNull(failed.get(5, TimeUnit.SECONDS));
                assertEquals(AudioBackend.Status.ERROR, backend.state().status());
            }
            assertEquals(2, count.get());
        }
    }

    @Test
    void crossfadePreservesUncorrelatedPowerAndEndpoints() {
        for (int i = 0; i <= 100; i++) {
            double t = i / 100.0, a = JavaSoundBackend.crossfadeGain(t, false), b = JavaSoundBackend.crossfadeGain(t, true);
            assertEquals(1, a * a + b * b, 1e-12);
        }
        assertEquals(1, JavaSoundBackend.crossfadeGain(0, false));
        assertEquals(0, JavaSoundBackend.crossfadeGain(0, true));
        assertEquals(1, JavaSoundBackend.crossfadeGain(1, true));
    }
}