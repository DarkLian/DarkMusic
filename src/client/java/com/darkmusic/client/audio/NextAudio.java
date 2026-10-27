package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;

import javax.sound.sampled.AudioInputStream;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * One pre-opened decoder; synchronized ownership prevents cancellation from closing a transferred stream.
 */
final class NextAudio {
    volatile long generation;
    final Track track;
    final AudioBackend.Transition transition;
    volatile Runnable started, ended;
    volatile Consumer<String> failed;
    volatile Future<?> future;
    private Ready ready;
    private boolean cancelled;

    NextAudio(long generation, Track track, Runnable started, Runnable ended, Consumer<String> failed) {
        this(generation, track, started, ended, failed, new AudioBackend.Transition());
    }

    NextAudio(long generation, Track track, Runnable started, Runnable ended, Consumer<String> failed, AudioBackend.Transition transition) {
        this.generation = generation;
        this.track = track;
        this.started = started;
        this.ended = ended;
        this.failed = failed;
        this.transition = transition;
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

    synchronized boolean untouched() {
        return ready == null || ready.converter.positionFrames() == 0;
    }

    synchronized Ready claim() {
        if (cancelled || ready == null || !transition.claim()) return null;
        var result = ready;
        ready = null;
        cancelled = true;
        return result;
    }

    synchronized java.util.concurrent.CompletableFuture<Void> cancel() {
        cancelled = true;
        if (future != null) future.cancel(true);
        if (ready != null) {
            var abandoned = ready.stream;
            ExternalAudio.abort(abandoned);
            var released = new java.util.concurrent.CompletableFuture<Void>();
            Thread.ofVirtual().name("darkmusic-decoder-close").start(() -> {
                try {
                    close(abandoned);
                } finally {
                    released.complete(null);
                }
            });
            ready = null;
            return released;
        }
        return java.util.concurrent.CompletableFuture.completedFuture(null);
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
