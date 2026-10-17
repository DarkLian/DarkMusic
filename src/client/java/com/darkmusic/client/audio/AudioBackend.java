package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;

import java.util.function.Consumer;

public interface AudioBackend extends AutoCloseable {
    State state();

    default SpectrumAnalyzer spectrum() {
        return null;
    }

    void play(Track track, Runnable ended, Consumer<String> failed);

    void pause();

    void resume();

    void stop();

    void seek(double seconds);

    void volume(double volume);

    void speed(double speed);

    @Override
    void close();

    enum Status {STOPPED, LOADING, SEEKING, PLAYING, PAUSED, ERROR}

    record State(Track track, Status status, double position, double duration, String error) {
    }
}
