package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;

import javax.sound.sampled.AudioInputStream;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * One pre-opened decoder; synchronized ownership prevents cancellation from closing a transferred stream.
 */
final class NextAudio {
    final long generation;
    final Track track;
    volatile Runnable started, ended;
    volatile Consumer<String> failed;
    volatile Future<?> future;
    private Ready ready;
    private boolean cancelled;

    NextAudio(long generation, Track track, Runnable started, Runnable ended, Consumer<String> failed) {
        this.generation = generation;
        this.track = track;
        this.started = started;
        this.ended = ended;
        this.failed = failed;
    }

    synchronized void install(AudioInputStream stream) {
        if (cancelled) {
            close(stream);
            return;
        }
        try {
            ready = new Ready(stream, new PcmRateConverter(stream));
        } catch (RuntimeException e) {
            close(stream);
            throw e;
        }
    }

    synchronized Ready peek() {
        return ready;
    }

    synchronized Ready claim() {
        var result = ready;
        ready = null;
        cancelled = true;
        return result;
    }

    synchronized void cancel() {
        cancelled = true;
        if (future != null) future.cancel(true);
        if (ready != null) {
            var abandoned = ready.stream;
            ExternalAudio.abort(abandoned);
            Thread.ofVirtual().name("darkmusic-decoder-close").start(() -> close(abandoned));
            ready = null;
        }
    }

    static void close(AudioInputStream stream) {
        try {
            stream.close();
        } catch (java.io.IOException ignored) {
        }
    }

    record Ready(AudioInputStream stream, PcmRateConverter converter) {
    }
}
