package com.darkmusic.client.audio;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Decides when playback has stopped making progress: the output device's position has not moved for the stall time
 * while it still had room to play, or the decoder has produced nothing for its own limit. A starved decoder is not
 * a stalled device, and time spent paused or decoding does not count against the device. Audio worker only.
 */
final class StallWatch {
    private final LongSupplier clock;
    private final long stallNanos, decoderStallNanos;
    private long progressedAt, lastPlayed;
    private PcmRateConverter emptyConverter;
    private long decoderEmptySince;

    /**
     * @param clock nanoseconds, as {@link System#nanoTime()}
     */
    StallWatch(LongSupplier clock, Duration stall, Duration decoder) {
        this.clock = clock;
        stallNanos = stall.toNanos();
        decoderStallNanos = Math.max(1, decoder.toNanos());
    }

    /**
     * A line was opened at this device position: the stall time starts again from here.
     */
    void opened(long position) {
        progressedAt = clock.getAsLong();
        lastPlayed = position;
    }

    /**
     * Pausing, decoding or a resume gave the device a fresh stall budget.
     */
    void progressed() {
        progressedAt = clock.getAsLong();
    }

    /**
     * Paused: an empty decoder read before the pause no longer counts toward starvation.
     */
    void paused() {
        decoderEmptySince = 0;
    }

    /**
     * Before a block is decoded: true when the device position has not moved for the stall time although the line
     * still holds unplayed audio. An empty buffer (asked only when the position did not move) means the device is
     * waiting for data, not stalled.
     */
    boolean deviceStalled(long played, BooleanSupplier bufferEmpty) {
        if (played != lastPlayed) {
            lastPlayed = played;
            progressedAt = clock.getAsLong();
        } else if (bufferEmpty.getAsBoolean()) progressedAt = clock.getAsLong();
        else return clock.getAsLong() - progressedAt > stallNanos;
        return false;
    }

    /**
     * A write found no room: true when nothing has progressed for the stall time.
     */
    boolean writeStalled() {
        return clock.getAsLong() - progressedAt > stallNanos;
    }

    /**
     * After a read from converter: true when it has yielded nothing for longer than the decoder limit. Switching to
     * another converter starts the count again.
     */
    boolean decoderStarved(PcmRateConverter converter, boolean empty) {
        if (!empty) {
            decoderEmptySince = 0;
            return false;
        }
        long now = clock.getAsLong();
        if (emptyConverter != converter || decoderEmptySince == 0) {
            emptyConverter = converter;
            decoderEmptySince = now;
            return false;
        }
        return now - decoderEmptySince > decoderStallNanos;
    }
}
