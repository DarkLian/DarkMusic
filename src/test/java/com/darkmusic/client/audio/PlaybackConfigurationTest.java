package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackConfigurationTest {
    @Test
    void repeatedSavesDoNotReconfigureAudioOrRebuildThePreparedQueue() {
        class Backend implements AudioBackend {
            int configurations, preparations;
            State state = new State(null, Status.STOPPED, 0, 0, "");

            public State state() {
                return state;
            }

            public void play(Track t, Runnable ended, Consumer<String> failed) {
                state = new State(t, Status.PLAYING, 0, 10, "");
            }

            public void pause() {
            }

            public void resume() {
            }

            public void stop() {
            }

            public void seek(double seconds) {
            }

            public void volume(double volume) {
            }

            public void speed(double speed) {
            }

            public void close() {
            }

            public void configure(AudioSettings settings) {
                configurations++;
            }

            public void nextTrack(Track t, Runnable started, Runnable ended, Consumer<String> failed) {
                preparations++;
            }
        }
        var backend = new Backend();
        var controller = new PlaybackController(backend, message -> fail(message));
        var settings = new AudioSettings();
        var track = new Track("test", "reference.mp3", Path.of("reference.mp3"), "", "", "", 10, 0, 0, "");
        controller.configure(settings);
        controller.play(List.of(track), track);
        int prepared = backend.preparations;
        for (int i = 0; i < 200; i++) {
            controller.volume(i / 200.0);
            controller.speed(1 + (i % 10) * .1);
            controller.configure(settings);
        }
        assertEquals(1, backend.configurations);
        assertEquals(prepared, backend.preparations);
        settings.bands[0] = 5;
        controller.configure(settings);
        assertEquals(2, backend.configurations);
        assertEquals(prepared, backend.preparations);
        settings.crossfade = 1;
        controller.configure(settings);
        assertEquals(3, backend.configurations);
        assertTrue(backend.preparations > prepared);
    }

    @Test
    void publishedSettingsAreValidatedAndDetachedFromTheCaller() throws Exception {
        try (var backend = new JavaSoundBackend(Runnable::run, path -> {
            throw new AssertionError();
        }, format -> {
            throw new AssertionError();
        })) {
            var source = new AudioSettings();
            source.crossfade = 100;
            backend.configure(source);
            var snapshot = backend.options();
            assertEquals(10, snapshot.crossfade);
            source.bands[0] = 12;
            assertEquals(0, snapshot.bands[0]);
            source.crossfade = Double.NaN;
            source.bands = null;
            backend.configure(source);
            snapshot = backend.options();
            assertEquals(0, snapshot.crossfade);
            assertEquals(10, snapshot.bands.length);
        }
    }
}
