package com.darkmusic.client;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.SleepTimer;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.hud.VolumeFeedback;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.systemmedia.MediaControls;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.FakeAudio;
import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.testing.Tracks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what each player command allows today, over a real controller on a fake backend, a real settings store and
 * real playlists. The library cases scan temporary copies of songs from the private collection.
 */
class PlaybackCommandsTest {
    private static final PlaybackController.Source ALL = new PlaybackController.Source("All", "", "", "");
    @TempDir
    Path temp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final FakeAudio audio = new FakeAudio();
    private final PlaybackController playback = new PlaybackController(audio, text -> {
    });
    private final ClientState state = new ClientState();
    private final List<Settings> writes = new ArrayList<>();
    private final SettingsStore store = new SettingsStore(s -> {
        writes.add(s);
        return CompletableFuture.completedFuture(null);
    }, () -> true, () -> {
    });
    private final VolumeFeedback feedback = new VolumeFeedback();
    private final SleepTimer sleep = new SleepTimer();
    private MusicLibrary library;
    private PlaylistStore playlists;
    private ListeningHistory history;
    private PlaybackCommands commands;

    @AfterEach
    void close() {
        if (library != null) library.close();
        playback.close();
        io.shutdownNow();
    }

    private PlaybackCommands commands() {
        store.replace(new Settings());
        library = new MusicLibrary(temp.resolve("data"));
        playlists = new PlaylistStore(temp.resolve("data"), io, text -> {
        });
        playlists.loaded(List.of());
        history = new ListeningHistory(temp.resolve("data"), Runnable::run, text -> {
        });
        history.load();
        return commands = new PlaybackCommands(new PlaybackCommands.Inputs() {
            public PlaybackController playback() {
                return playback;
            }

            public MusicLibrary library() {
                return library;
            }

            public PlaylistStore playlists() {
                return playlists;
            }

            public ListeningHistory listeningHistory() {
                return history;
            }

            public VolumeFeedback volumeFeedback() {
                return feedback;
            }

            public SleepTimer sleep() {
                return sleep;
            }
        }, state, store);
    }

    /**
     * Three temporary copies of real songs in one library root.
     */
    private List<Track> scanned() throws Exception {
        var files = TestMusic.files();
        Path root = Files.createDirectories(temp.resolve("music"));
        for (int i = 0; i < 3; i++) {
            Path source = files.get(i % files.size());
            String name = source.getFileName().toString();
            Files.copy(source, root.resolve(i + "-" + name));
        }
        library.setRoot(root, "r");
        Await.of(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 3);
        return library.snapshot().tracks();
    }

    private static List<Track> tracks() {
        return List.of(Tracks.of("r", "a.opus", Path.of("a.opus")), Tracks.of("r", "b.opus", Path.of("b.opus")));
    }

    private boolean[] transport(PlaybackCommands c, List<Track> tracks) {
        return new boolean[]{c.play(tracks, tracks.getFirst(), ALL), c.toggle(), c.next(),
            c.previous(), c.seek(1), c.stop(), c.pause(), c.resume()};
    }

    @Test
    void steeringWaitsForMediaWorkWhileStopPauseAndResumeOnlyNeedTheClientAlive() {
        var c = commands();
        var tracks = tracks();
        state.mediaBusy = true;
        assertArrayEquals(new boolean[]{false, false, false, false, false, true, true, true}, transport(c, tracks));
        assertNull(audio.state().track(), "a refused play must not reach the player");
        state.mediaBusy = false;
        state.outputChanging = true;
        assertArrayEquals(new boolean[]{true, true, true, true, true, true, true, true}, transport(c, tracks),
            "switching the output device does not hold the transport");
        state.outputChanging = false;
        state.resetting = true;
        assertArrayEquals(new boolean[8], transport(c, tracks));
        state.resetting = false;
        state.closed = true;
        assertArrayEquals(new boolean[8], transport(c, tracks));
    }

    @Test
    void seekRelativeFollowsTheSteeringRule() {
        var c = commands();
        var tracks = tracks();
        c.play(tracks, tracks.getFirst(), ALL);
        audio.lastSeek = -1;
        state.mediaBusy = true;
        c.seekRelative(5);
        assertEquals(-1, audio.lastSeek);
        state.mediaBusy = false;
        c.seekRelative(5);
        assertNotEquals(-1, audio.lastSeek);
    }

    @Test
    void theQueueIsEditableOnlyWhileAcceptingWork() {
        var c = commands();
        var tracks = tracks();
        c.play(tracks, tracks.getFirst(), ALL);
        for (int blocked = 0; blocked < 3; blocked++) {
            state.mediaBusy = blocked == 0;
            state.outputChanging = blocked == 1;
            state.resetting = blocked == 2;
            assertFalse(c.canEditQueue());
            assertFalse(c.playNext(tracks.get(1)));
            assertFalse(c.addToQueue(tracks.get(1)));
            assertFalse(c.insert(tracks.get(1), 0));
            assertFalse(c.reorder(List.of(), List.of()));
            assertFalse(c.remove(List.of()));
            assertFalse(c.clear());
            assertTrue(playback.upNextSnapshot().manual().isEmpty());
        }
        state.mediaBusy = state.outputChanging = state.resetting = false;
        assertTrue(c.addToQueue(tracks.get(1)));
        assertTrue(c.playNext(tracks.getFirst()));
        assertEquals(2, playback.upNextSnapshot().manual().size());
        assertTrue(c.remove(List.of(playback.upNextSnapshot().manual().getFirst().id())));
        assertEquals(1, playback.upNextSnapshot().manual().size());
        assertTrue(c.clear());
        assertTrue(playback.upNextSnapshot().manual().isEmpty());
    }

    @Test
    void volumeSpeedShuffleAndRepeatChangeTheSettingsAndThePlayerAndSave() {
        var c = commands();
        c.volume(.4);
        assertEquals(.4, store.settings().volume);
        assertEquals(.4, store.settings().lastAudibleVolume);
        assertEquals(.4, audio.volume, 1e-9);
        c.volume(0);
        assertEquals(0, store.settings().volume);
        assertEquals(.4, store.settings().lastAudibleVolume, "silence keeps the last audible volume");
        c.speed(100);
        assertEquals(store.settings().speed, audio.speed, 1e-9);
        assertTrue(store.settings().speed < 100, "speed is clamped to the converter's range");
        c.shuffle(true);
        assertTrue(store.settings().shuffle);
        assertTrue(playback.shuffle());
        c.repeat(Repeat.ONE);
        assertEquals(Repeat.ONE, store.settings().repeat);
        assertEquals(Repeat.ONE, playback.repeat());
        assertEquals(5, writes.size(), "each change saves once");
    }

    @Test
    void muteRestoresTheLastAudibleVolumeAndShowsTheFeedback() {
        var c = commands();
        c.volume(.6);
        c.toggleMute();
        assertEquals(0, store.settings().volume);
        assertTrue(feedback.opacity(System.nanoTime()) > 0);
        c.toggleMute();
        assertEquals(.6, store.settings().volume);
        assertEquals(.6, audio.volume, 1e-9);
    }

    @Test
    void resumePlaysInTheRememberedCollectionOrFallsBackToTheLibrary() throws Exception {
        var c = commands();
        var tracks = scanned();
        var song = tracks.get(1);
        String mix = playlists.create("Mix");
        playlists.addAll(mix, List.of(song));
        var settings = store.settings();
        assertFalse(c.resumeLast(false), "nothing to resume while the setting is off");
        settings.resumeLast = true;
        settings.resume = new Settings.Resume("Playlists", mix, song.id(), 2);
        assertTrue(c.resumes(song));
        assertFalse(c.resumes(tracks.getFirst()));
        state.mediaBusy = true;
        assertFalse(c.resumeLast(false));
        state.mediaBusy = false;
        assertTrue(c.resumeLast(true));
        assertEquals(song, playback.state().track());
        assertEquals(new PlaybackController.Source("Playlists", mix, "", ""), playback.playingSource());
        assertEquals(2, audio.lastSeek, 1e-9);
        assertEquals(AudioBackend.Status.PAUSED, playback.state().status());
        // The playlist lost the song: play it in the whole library instead.
        settings.resume = new Settings.Resume("Playlists", "missing", song.id(), 2);
        assertTrue(c.resumeLast(false));
        assertEquals("All", playback.playingSource().mode());
        assertEquals(song, playback.state().track());
        // Within the last five seconds the song starts over.
        audio.lastSeek = -1;
        settings.resume = new Settings.Resume("All", "", song.id(), song.duration() - 1);
        assertTrue(c.resumeLast(false));
        assertTrue(audio.lastSeek <= 0, "started over instead of seeking near the end");
        settings.resume = new Settings.Resume("All", "", "gone", 0);
        assertFalse(c.resumeLast(false));
    }

    @Test
    void startPlaybackResumesOrPlaysTheFirstSong() throws Exception {
        var c = commands();
        var tracks = scanned();
        assertEquals(tracks.getFirst(), c.startPlayback());
        store.settings().resumeLast = true;
        store.settings().resume = new Settings.Resume("All", "", tracks.get(2).id(), 0);
        assertEquals(tracks.get(2), c.startPlayback());
    }

    @Test
    void historyCollectionsComeFromTheListeningHistory() throws Exception {
        var c = commands();
        var tracks = scanned();
        assertEquals(history.collection(tracks, true), c.collection("History", "most"));
        assertEquals(history.collection(tracks, false), c.collection("History", "recent"));
        assertEquals(ListeningHistory.recentlyAdded(tracks, PlaybackCommands.RECENTLY_ADDED), c.collection("History", "added"));
        assertEquals(history.neverPlayed(tracks, null), c.collection("History", "never"));
        assertEquals(tracks, c.collection("All", ""));
        assertEquals(List.of(), c.collection("Artists", "nobody"));
        String mix = playlists.create("Mix");
        playlists.addAll(mix, List.of(tracks.get(2), tracks.getFirst()));
        assertEquals(List.of(tracks.get(2), tracks.getFirst()), c.collection("Playlists", mix));
    }

    @Test
    void mediaKeysMapToThePlayerOnlyWhenEnabledAndIdle() throws Exception {
        var c = commands();
        var tracks = scanned();
        c.mediaAction(new MediaControls.Action("play", 0));
        assertNull(playback.state().track(), "media keys are off by default");
        store.settings().mediaControls = true;
        state.mediaBusy = true;
        c.mediaAction(new MediaControls.Action("play", 0));
        assertNull(playback.state().track());
        state.mediaBusy = false;
        c.mediaAction(new MediaControls.Action("play", 0));
        assertEquals(tracks.getFirst(), playback.state().track(), "play with nothing playing starts playback");
        c.mediaAction(new MediaControls.Action("pause", 0));
        assertEquals(AudioBackend.Status.PAUSED, playback.state().status());
        c.mediaAction(new MediaControls.Action("play", 0));
        assertEquals(AudioBackend.Status.PLAYING, playback.state().status());
        c.mediaAction(new MediaControls.Action("seek", 1e9));
        assertEquals(audio.state().duration(), audio.lastSeek, 1e-9, "seek is clamped to the song");
        c.mediaAction(new MediaControls.Action("next", 0));
        assertEquals(tracks.get(1), playback.state().track());
        c.mediaAction(new MediaControls.Action("stop", 0));
        assertEquals(AudioBackend.Status.STOPPED, playback.state().status());
    }
}
