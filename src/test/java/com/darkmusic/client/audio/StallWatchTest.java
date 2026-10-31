package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stall and starvation decisions on a fake clock. The converters only stand for "which decoder"; nothing is read.
 */
class StallWatchTest {
    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private final AtomicLong now = new AtomicLong(1_000);
    private final StallWatch watch = new StallWatch(now::get, Duration.ofSeconds(5), Duration.ofSeconds(2));

    private static PcmRateConverter converter() {
        return new PcmRateConverter(new AudioInputStream(InputStream.nullInputStream(), new AudioFormat(48000, 16, 2, true, false), 0));
    }

    @Test
    void aDeviceThatStopsMovingWithAudioQueuedStallsAfterTheTimeout() {
        watch.opened(100);
        now.addAndGet(5 * SECOND);
        assertFalse(watch.deviceStalled(100, () -> false), "exactly the timeout is not yet a stall");
        now.incrementAndGet();
        assertTrue(watch.deviceStalled(100, () -> false));
    }

    @Test
    void aMovingPositionOrAnEmptyBufferRestartsTheTime() {
        watch.opened(100);
        now.addAndGet(4 * SECOND);
        assertFalse(watch.deviceStalled(200, () -> false));
        now.addAndGet(4 * SECOND);
        assertFalse(watch.deviceStalled(200, () -> true), "a device waiting for data is not stalled");
        now.addAndGet(4 * SECOND);
        assertFalse(watch.deviceStalled(200, () -> false));
        now.addAndGet(2 * SECOND);
        assertTrue(watch.deviceStalled(200, () -> false));
    }

    @Test
    void theBufferIsAskedOnlyWhenThePositionDidNotMove() {
        watch.opened(0);
        assertFalse(watch.deviceStalled(1, () -> {
            throw new AssertionError("asked although the position moved");
        }));
    }

    @Test
    void pausingAndDecodingGiveTheDeviceAFreshBudget() {
        watch.opened(0);
        now.addAndGet(6 * SECOND);
        watch.progressed();
        assertFalse(watch.writeStalled());
        now.addAndGet(5 * SECOND + 1);
        assertTrue(watch.writeStalled());
    }

    @Test
    void aDecoderThatYieldsNothingStarvesAfterItsLimit() {
        var decoder = converter();
        assertFalse(watch.decoderStarved(decoder, true), "the first empty read starts the count");
        now.addAndGet(2 * SECOND);
        assertFalse(watch.decoderStarved(decoder, true));
        now.incrementAndGet();
        assertTrue(watch.decoderStarved(decoder, true));
    }

    @Test
    void dataAnotherDecoderOrAPauseRestartsTheCount() {
        var decoder = converter();
        watch.decoderStarved(decoder, true);
        now.addAndGet(3 * SECOND);
        assertFalse(watch.decoderStarved(decoder, false));
        assertFalse(watch.decoderStarved(decoder, true));
        now.addAndGet(3 * SECOND);
        assertFalse(watch.decoderStarved(converter(), true), "a new decoder starts its own count");
        now.addAndGet(3 * SECOND);
        watch.paused();
        assertFalse(watch.decoderStarved(decoder, true));
        now.addAndGet(SECOND);
        assertFalse(watch.decoderStarved(decoder, true));
    }
}
