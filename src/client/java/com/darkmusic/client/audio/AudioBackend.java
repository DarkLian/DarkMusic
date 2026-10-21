package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;

import java.util.function.Consumer;

public interface AudioBackend extends AutoCloseable {
    State state();

    default SpectrumAnalyzer spectrum() {
        return null;
    }

    void play(Track track, Runnable ended, Consumer<String> failed);

    default void playFrom(Track track, double position, boolean paused, Runnable ended, Consumer<String> failed) {
        play(track, ended, failed);
        seek(position);
        if (paused) pause();
    }

    /**
     * Continue the same listening session after an edit that temporarily closes its file.
     */
    default void resumeFrom(Track track, double position, boolean paused, Runnable ended, Consumer<String> failed) {
        playFrom(track, position, paused, ended, failed);
    }

    void pause();

    void resume();

    void stop();

    void seek(double seconds);

    void volume(double volume);

    void speed(double speed);

    default void configure(com.darkmusic.client.config.AudioSettings settings) {
    }

    default void configure(com.darkmusic.client.config.AudioSettings settings, com.darkmusic.client.config.SoundEnvironmentSettings environment) {
        configure(settings);
    }

    default AudioDiagnostics diagnostics() {
        return null;
    }

    default void comparison(AudioComparison comparison) {
    }

    default LoudnessCache.Status normalization() {
        return LoudnessCache.Status.OFF;
    }

    default void nextTrack(Track track, Runnable started, Runnable ended, Consumer<String> failed) {
    }

    default boolean awaitStopped(long seconds) throws InterruptedException {
        return true;
    }

    default boolean awaitIdle(long seconds) throws InterruptedException {
        return true;
    }

    @Override
    void close();

    enum Status {STOPPED, LOADING, SEEKING, PLAYING, PAUSED, ERROR}

    record State(Track track, Status status, double position, double duration, String error) {
    }
}
