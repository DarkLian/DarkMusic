package com.darkmusic.client;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackTest {
    List<Track> tracks() {
        return java.util.stream.IntStream.range(0, 5).mapToObj(i -> StorageTest.track("root", i + ".wav", Path.of(i + ".wav"))).toList();
    }

    @Test
    void nextPreviousPauseRepeatAndStaleCompletions() {
        var audio = new Fake();
        var controller = new PlaybackController(audio, message -> {
        });
        var tracks = tracks();
        controller.play(tracks, tracks.getFirst());
        Runnable stale = audio.ended;
        controller.next();
        assertEquals(tracks.get(1), audio.state.track());
        stale.run();
        assertEquals(tracks.get(1), audio.state.track());
        controller.previous();
        assertEquals(tracks.getFirst(), audio.state.track());
        controller.toggle();
        assertEquals(AudioBackend.Status.PAUSED, audio.state.status());
        controller.toggle();
        assertEquals(AudioBackend.Status.PLAYING, audio.state.status());
        controller.repeat(PlaybackController.Repeat.ONE);
        audio.ended.run();
        assertEquals(tracks.getFirst(), audio.state.track());
        controller.next();
        assertEquals(tracks.get(1), audio.state.track());
        controller.repeat(PlaybackController.Repeat.OFF);
        controller.play(tracks, tracks.getLast());
        audio.ended.run();
        assertEquals(AudioBackend.Status.STOPPED, audio.state.status());
        controller.repeat(PlaybackController.Repeat.ALL);
        controller.play(tracks, tracks.getLast());
        audio.ended.run();
        assertEquals(tracks.getFirst(), audio.state.track());
    }

    @Test
    void shuffledQueueHasNoDuplicatesAndHistorySurvives() {
        var audio = new Fake();
        var controller = new PlaybackController(audio, s -> {
        });
        var tracks = tracks();
        controller.shuffle(true);
        controller.play(tracks, tracks.getFirst());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tracks.size(); i++) {
            assertTrue(seen.add(audio.state.track().id()));
            if (i < tracks.size() - 1) controller.next();
        }
        assertEquals(tracks.size(), seen.size());
        Track last = audio.state.track();
        controller.previous();
        assertNotEquals(last, audio.state.track());
    }

    @Test
    void entirelyUnplayableRepeatQueueTerminates() {
        var audio = new Fake();
        List<String> warnings = new ArrayList<>();
        var controller = new PlaybackController(audio, warnings::add);
        var tracks = tracks();
        controller.repeat(PlaybackController.Repeat.ALL);
        controller.play(tracks, tracks.getFirst());
        for (int i = 0; i < tracks.size(); i++) audio.failed.accept("Corrupt file");
        assertEquals(AudioBackend.Status.STOPPED, audio.state.status());
        assertEquals(tracks.size(), warnings.size());
    }

    @Test
    void softwareGainHandlesRealMusicPcm() throws Exception {
        byte[] original = TestMusic.clip().bytes();
        byte[] pcm = original.clone();
        JavaSoundBackend.scale(pcm, pcm.length, .5);
        for (int at = 0; at + 1 < pcm.length; at += 2) {
            short input = (short) ((original[at] & 255) | original[at + 1] << 8);
            short result = (short) ((pcm[at] & 255) | pcm[at + 1] << 8);
            assertEquals((short) Math.round(input * .5), result);
        }
        JavaSoundBackend.scale(pcm, pcm.length, 0);
        assertArrayEquals(new byte[pcm.length], pcm);
    }

    static final class Fake implements AudioBackend {
        State state = new State(null, Status.STOPPED, 0, 0, "");
        Runnable ended;
        Consumer<String> failed;

        public State state() {
            return state;
        }

        public void play(Track track, Runnable ended, Consumer<String> failed) {
            state = new State(track, Status.PLAYING, 0, 1, "");
            this.ended = ended;
            this.failed = failed;
        }

        public void pause() {
            state = new State(state.track(), Status.PAUSED, 0, 1, "");
        }

        public void resume() {
            state = new State(state.track(), Status.PLAYING, 0, 1, "");
        }

        public void stop() {
            state = new State(state.track(), Status.STOPPED, 0, 1, "");
        }

        public void seek(double s) {
        }

        public void volume(double v) {
        }

        public void speed(double v) {
        }

        public void close() {
        }
    }
}
