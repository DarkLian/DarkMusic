package com.darkmusic.client.audio;

import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.testing.Tracks;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackTest {
    @Test
    void manualNavigationWrapsTheCollectionRegardlessOfRepeatOrExhaustedHistory() {
        for (var repeat : Repeat.values()) {
            var audio = new Fake();
            var controller = new PlaybackController(audio, message -> fail(message));
            var tracks = tracks();
            controller.repeat(repeat);
            controller.play(tracks, tracks.getFirst());
            controller.previous();
            assertEquals(tracks.getLast(), audio.state.track());
            controller.next();
            assertEquals(tracks.getFirst(), audio.state.track());
            for (int i = 0; i < tracks.size() * 3; i++) controller.next();
            for (int i = 1; i <= tracks.size() * 5; i++) {
                controller.previous();
                assertEquals(tracks.get(Math.floorMod(-i, tracks.size())), audio.state.track());
            }
            controller.play(tracks, tracks.get(2));
            controller.previous();
            assertEquals(tracks.get(1), audio.state.track());
        }
    }

    @Test
    void relativeSeekingClampsToTheCurrentTrackWithoutChangingPauseOrQueue() {
        var audio = new Fake();
        var controller = new PlaybackController(audio, message -> fail(message));
        var track = tracks().getFirst();
        for (var status : List.of(AudioBackend.Status.PLAYING, AudioBackend.Status.PAUSED)) {
            audio.state = new AudioBackend.State(track, status, 2, 20, "");
            controller.seekRelative(-5);
            assertEquals(0, audio.lastSeek);
            assertEquals(status, audio.state.status());
            audio.state = new AudioBackend.State(track, status, 10, 20, "");
            controller.seekRelative(5);
            assertEquals(15, audio.lastSeek);
            audio.state = new AudioBackend.State(track, status, 18, 20, "");
            controller.seekRelative(5);
            assertEquals(20, audio.lastSeek);
        }
        audio.lastSeek = -1;
        controller.seekRelative(Double.NaN);
        assertEquals(-1, audio.lastSeek);
        for (var status : List.of(AudioBackend.Status.STOPPED, AudioBackend.Status.ERROR)) {
            audio.state = new AudioBackend.State(track, status, 10, 20, "");
            controller.seekRelative(5);
            assertEquals(-1, audio.lastSeek);
        }
        audio.state = new AudioBackend.State(track, AudioBackend.Status.PLAYING, 0, 0, "");
        controller.seekRelative(5);
        assertEquals(-1, audio.lastSeek);
    }

    @Test
    void metadataEditPreservesQueueOriginPauseAndSelectionAndDoesNotUndoUserStop() {
        var audio = new Fake();
        var controller = new PlaybackController(audio, message -> fail(message));
        var tracks = tracks();
        var source = new PlaybackController.Source("Playlists", "evening", "", "root");
        controller.play(tracks, tracks.getFirst(), source);
        controller.toggle();
        var ticket = controller.suspendForEdit();
        Track renamed = Tracks.of("root", "renamed.wav", Path.of("renamed.wav"));
        controller.finishEdit(ticket, tracks.getFirst(), renamed);
        assertEquals(renamed, controller.state().track());
        assertEquals(AudioBackend.Status.PAUSED, controller.state().status());
        assertEquals(source, controller.source());
        controller.next();
        assertEquals(tracks.get(1), controller.state().track());
        ticket = controller.suspendForEdit();
        controller.stop();
        controller.finishEdit(ticket, renamed, renamed);
        assertEquals(AudioBackend.Status.STOPPED, controller.state().status());
    }

    List<Track> tracks() {
        return IntStream.range(0, 5).mapToObj(i -> Tracks.of("root", i + ".wav", Path.of(i + ".wav"))).toList();
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
        controller.repeat(Repeat.ONE);
        audio.ended.run();
        assertEquals(tracks.getFirst(), audio.state.track());
        controller.next();
        assertEquals(tracks.get(1), audio.state.track());
        controller.repeat(Repeat.OFF);
        controller.play(tracks, tracks.getLast());
        audio.ended.run();
        assertEquals(AudioBackend.Status.STOPPED, audio.state.status());
        controller.repeat(Repeat.ALL);
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
        controller.repeat(Repeat.ALL);
        controller.play(tracks, tracks.getFirst());
        for (int i = 0; i < tracks.size(); i++) audio.failed.accept("Corrupt file");
        assertEquals(AudioBackend.Status.STOPPED, audio.state.status());
        assertEquals(tracks.size(), warnings.size());
    }

    @Test
    void queueOriginSurvivesRepeatShuffleAndTrackChangesUntilNewPlaybackStarts() {
        var audio = new Fake();
        var controller = new PlaybackController(audio, message -> {
        });
        var tracks = tracks();
        for (var source : List.of(new PlaybackController.Source("All", "E1/Nested", "", "root"), new PlaybackController.Source("All", "", "", "root"), new PlaybackController.Source("Playlists", "casual-id", "", "root"), new PlaybackController.Source("Artists", "Artist", "search", "root"), new PlaybackController.Source("Albums", "Album", "", "root"))) {
            controller.play(tracks, tracks.getFirst(), source);
            controller.repeat(Repeat.ALL);
            controller.next();
            controller.previous();
            controller.shuffle(true);
            audio.ended.run();
            assertEquals(source, controller.source());
        }
        controller.play(tracks, tracks.getFirst());
        assertEquals(new PlaybackController.Source("All", "", "", "root"), controller.source());
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
        double lastSeek = -1;
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
            lastSeek = s;
        }

        public void volume(double v) {
        }

        public void speed(double v) {
        }

        public void close() {
        }
    }
}
