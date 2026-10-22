package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TransitionOrderTest {
    @Test
    void anAlreadyDispatchedTransitionDoesNotUndoANewShuffleOrder() throws Exception {
        var audio = new Backend();
        var controller = new PlaybackController(audio, message -> fail(message));
        var tracks = java.util.stream.IntStream.range(0, 12).mapToObj(i -> new Track("root", "song" + i, Path.of("song" + i), "", "", "", 120, 0, 0, "")).toList();
        controller.play(tracks, tracks.getFirst());
        Runnable dispatched = audio.started;
        audio.state = new AudioBackend.State(tracks.get(1), AudioBackend.Status.PLAYING, 0, 120, "");
        controller.shuffle(true);
        var field = PlaybackController.class.getDeclaredField("order");
        field.setAccessible(true);
        var expected = List.copyOf((List<?>) field.get(controller));
        dispatched.run();
        assertEquals(expected, field.get(controller), "A dispatched callback restored an obsolete queue order");
        var cursor = PlaybackController.class.getDeclaredField("cursor");
        cursor.setAccessible(true);
        assertEquals(expected.indexOf(1), cursor.getInt(controller), "The queue must still follow the audible track");
    }

    static final class Backend implements AudioBackend {
        State state = new State(null, Status.STOPPED, 0, 0, "");
        Runnable started;

        public State state() {
            return state;
        }

        public void play(Track t, Runnable end, Consumer<String> fail) {
            state = new State(t, Status.PLAYING, 0, 120, "");
        }

        public void pause() {
        }

        public void resume() {
        }

        public void stop() {
        }

        public void seek(double p) {
        }

        public void volume(double v) {
        }

        public void speed(double v) {
        }

        public void close() {
        }

        public void nextTrack(Track t, Runnable start, Runnable end, Consumer<String> fail) {
            started = start;
        }
    }
}
