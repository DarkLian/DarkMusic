package com.darkmusic.client.testing;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.library.Track;

import java.util.function.Consumer;

/**
 * A player that plays nothing: it reports what it was told, so tests can drive a real controller without a device.
 */
public final class FakeAudio implements AudioBackend {
    public volatile State state = new State(null, Status.STOPPED, 0, 0, "");
    public double lastSeek = -1, volume = -1, speed = -1;
    public Runnable ended;
    public Consumer<String> failed;

    @Override
    public State state() {
        return state;
    }

    @Override
    public void play(Track track, Runnable ended, Consumer<String> failed) {
        state = new State(track, Status.PLAYING, 0, Math.max(1, track.duration()), "");
        this.ended = ended;
        this.failed = failed;
    }

    @Override
    public void pause() {
        state = new State(state.track(), Status.PAUSED, state.position(), state.duration(), "");
    }

    @Override
    public void resume() {
        state = new State(state.track(), Status.PLAYING, state.position(), state.duration(), "");
    }

    @Override
    public void stop() {
        state = new State(state.track(), Status.STOPPED, 0, state.duration(), "");
    }

    @Override
    public void seek(double seconds) {
        lastSeek = seconds;
    }

    @Override
    public void volume(double volume) {
        this.volume = volume;
    }

    @Override
    public void speed(double speed) {
        this.speed = speed;
    }

    @Override
    public void close() {
    }
}
