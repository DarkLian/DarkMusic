package com.darkmusic.client.cleanup;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.library.Track;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * A separate backend has no history listener or source/queue ownership. Main playback is only paused.
 */
public final class ComparisonAudition implements AutoCloseable {
    private final PlaybackController main;
    private final Supplier<AudioBackend> factory;
    private AudioBackend preview;
    private Track suspended;
    private double position;
    private boolean resume, closed;
    private volatile String failure = "";

    public ComparisonAudition(PlaybackController main, Supplier<AudioBackend> factory) {
        this.main = main;
        this.factory = factory;
    }

    public void play(Track track, double volume) {
        if (closed) return;
        try {
            if (preview == null) {
                var state = main.state();
                resume = state.status() == AudioBackend.Status.PLAYING || state.status() == AudioBackend.Status.LOADING || state.status() == AudioBackend.Status.SEEKING;
                suspended = state.track();
                position = state.position();
                if (resume) main.pause();
                preview = factory.get();
            }
            failure = "";
            preview.volume(volume);
            var state = preview.state();
            if (track.equals(state.track())) {
                if (state.status() == AudioBackend.Status.PAUSED) {
                    preview.resume();
                    return;
                }
                if (active(state.status())) {
                    preview.pause();
                    return;
                }
            }
            preview.play(track, () -> {
            }, error -> failure = error);
        } catch (RuntimeException error) {
            failure = "Preview unavailable";
            LoggerFactory.getLogger("darkmusic-cleanup").warn("Could not start comparison preview", error);
            restore();
        }
    }

    public Track playing() {
        return preview == null ? null : preview.state().track();
    }

    public boolean active(Track track) {
        return preview != null && track.equals(preview.state().track()) && active(preview.state().status());
    }

    private static boolean active(AudioBackend.Status status) {
        return status == AudioBackend.Status.PLAYING || status == AudioBackend.Status.LOADING || status == AudioBackend.Status.SEEKING;
    }

    public String error() {
        return failure;
    }

    public void stop() {
        if (preview != null) preview.stop();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (preview != null) preview.close();
        } finally {
            restore();
        }
    }

    private void restore() {
        var state = main.state();
        if (resume && suspended != null && suspended.equals(state.track()) && state.status() == AudioBackend.Status.PAUSED && Math.abs(position - state.position()) < .5)
            main.resume();
        resume = false;
    }
}
