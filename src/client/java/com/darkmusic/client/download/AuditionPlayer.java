package com.darkmusic.client.download;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Serial ownership keeps temporary files alive until the existing audio backend releases them.
 */
public final class AuditionPlayer implements AutoCloseable {
    private final Fetcher fetcher;
    private final Supplier<AudioBackend> factory;
    private final Executor callbacks;
    private final Runnable finished;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-audition").factory());
    private ProcessRunner.Token token = new ProcessRunner.Token();
    private long generation;
    private boolean closed;
    private volatile Snapshot state = new Snapshot(Status.STOPPED, "", "");
    // The worker's release() clears this without the lock, so callers read it once.
    private volatile AudioBackend audio;
    private AuditionClip clip;

    public AuditionPlayer(Fetcher fetcher, Supplier<AudioBackend> factory, Executor callbacks, Runnable finished) {
        this.fetcher = fetcher;
        this.factory = factory;
        this.callbacks = callbacks;
        this.finished = finished;
    }

    public Snapshot snapshot() {
        return state;
    }

    public synchronized void play(DownloadModels.Entry entry, ToolPaths tools, double volume) {
        if (closed) return;
        token.cancel();
        var request = new ProcessRunner.Token();
        token = request;
        long ticket = ++generation;
        state = new Snapshot(Status.LOADING, entry.title(), "");
        var playing = audio;
        if (playing != null) playing.stop();
        worker.execute(() -> {
            release();
            try {
                request.check();
                clip = fetcher.fetch(entry, tools, request);
                synchronized (this) {
                    request.check();
                    if (closed || ticket != generation) {
                        release();
                        return;
                    }
                    audio = factory.get();
                    audio.volume(volume);
                    audio.play(clip.track(), () -> complete(ticket, ""), error -> complete(ticket, error));
                    if (ticket == generation) state = new Snapshot(Status.PLAYING, entry.title(), "");
                }
            } catch (CancellationException e) {
                release();
            } catch (Exception e) {
                LoggerFactory.getLogger("darkmusic-download").warn("Cannot prepare audio preview for {}", entry.title(), e);
                release();
                complete(ticket, String.valueOf(e.getMessage()));
            }
        });
    }

    private synchronized void complete(long ticket, String error) {
        if (closed || ticket != generation) return;
        long completed = ++generation;
        state = new Snapshot(error.isEmpty() ? Status.STOPPED : Status.ERROR, state.title(), error);
        worker.execute(this::release);
        notifyFinished(completed);
    }

    private void notifyFinished(long ticket) {
        callbacks.execute(() -> {
            synchronized (this) {
                if (ticket != generation) return;
            }
            finished.run();
        });
    }

    public synchronized void stop() {
        if (closed) return;
        long completed = ++generation;
        token.cancel();
        state = new Snapshot(Status.STOPPED, "", "");
        var playing = audio;
        if (playing != null) playing.stop();
        worker.execute(this::release);
        notifyFinished(completed);
    }

    public synchronized void pause() {
        var playing = audio;
        if (closed || state.status() != Status.PLAYING || playing == null) return;
        playing.pause();
        state = new Snapshot(Status.PAUSED, state.title(), "");
    }

    public synchronized void resume() {
        var playing = audio;
        if (closed || state.status() != Status.PAUSED || playing == null) return;
        playing.resume();
        state = new Snapshot(Status.PLAYING, state.title(), "");
    }

    private void release() {
        var backend = audio;
        audio = null;
        if (backend != null) backend.close();
        var current = clip;
        clip = null;
        if (current != null) try {
            current.close();
        } catch (IOException e) {
            LoggerFactory.getLogger("darkmusic-download").warn("Cannot remove temporary audition", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        stop();
        closed = true;
        worker.shutdown();
    }

    public boolean awaitStopped(long seconds) throws InterruptedException {
        return worker.awaitTermination(seconds, TimeUnit.SECONDS);
    }

    public enum Status {STOPPED, LOADING, PLAYING, PAUSED, ERROR}

    @FunctionalInterface
    public interface Fetcher {
        AuditionClip fetch(DownloadModels.Entry entry, ToolPaths tools, ProcessRunner.Token token) throws Exception;
    }

    public record Snapshot(Status status, String title, String error) {
    }
}
