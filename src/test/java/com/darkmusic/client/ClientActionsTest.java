package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.*;
import com.darkmusic.client.hud.*;
import com.darkmusic.client.library.*;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.testing.*;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.update.UpdateService;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ClientActionsTest {
    @TempDir
    Path temp;
    private final ClientState state = new ClientState();
    private final FakeAudio audio = new FakeAudio();
    private final PlaybackController playback = new PlaybackController(audio, text -> {
    });
    private final SettingsStore store = new SettingsStore(s -> CompletableFuture.completedFuture(null), () -> true, () -> {
    });
    private final SleepTimer sleep = new SleepTimer();
    private final VolumeFeedback volume = new VolumeFeedback();
    private final SpeedFeedback speed = new SpeedFeedback();
    private MusicLibrary library;
    private ClientActions actions;

    private final class Inputs implements ClientActions.Inputs {
        public Settings settings() {
            return store.settings();
        }

        public PlaybackController playback() {
            return playback;
        }

        public MusicLibrary library() {
            return library;
        }

        public PlaylistStore playlists() {
            return null;
        }

        public ListeningHistory listeningHistory() {
            return null;
        }

        public SleepTimer sleep() {
            return sleep;
        }

        public VolumeFeedback volumeFeedback() {
            return volume;
        }

        public SpeedFeedback speedFeedback() {
            return speed;
        }

        public UpdateService updates() {
            return null;
        }
    }

    @BeforeEach
    void open() {
        store.replace(new Settings());
        library = new MusicLibrary(temp.resolve("data"));
        var inputs = new Inputs();
        var commands = new PlaybackCommands(inputs, state, store);
        actions = new ClientActions(inputs, state, commands, null, new Shortcuts(null, null, () -> {
        }), "test-version");
    }

    @AfterEach
    void close() throws Exception {
        playback.close();
        if (library != null) {
            library.close();
            assertTrue(library.awaitStopped(10));
        }
    }

    private List<Supplier<Component>> commands() {
        return List.of(() -> actions.play(null), actions::pause, actions::stop, actions::next, actions::previous,
            actions::now, () -> actions.queue("song"), () -> actions.volume(20), actions::mute,
            () -> actions.speed(1.5), () -> actions.seek("10"), () -> actions.shuffle(true),
            () -> actions.repeat(Repeat.ALL), () -> actions.sleep(10), actions::stopAfter);
    }

    @Test
    void allChatPlaybackActionsKeepTheirExistingIdleAdmission() {
        for (int busy = 0; busy < 3; busy++) {
            state.mediaBusy = busy == 0;
            state.resetting = busy == 1;
            state.closed = busy == 2;
            for (var command : commands())
                assertEquals(Text.get("command.darkmusic.unavailable"), command.get().getString());
        }
        assertEquals(-1, audio.volume);
        assertEquals(-1, audio.speed);
        assertEquals(-1, audio.lastSeek);
    }

    @Test
    void missingSettingsPlayerOrLibraryDoNotAdmitActions() {
        store.replace(null);
        for (var command : commands())
            assertEquals(Text.get("command.darkmusic.unavailable"), command.get().getString());
        store.replace(new Settings());
        library.close();
        library = null;
        for (var command : commands())
            assertEquals(Text.get("command.darkmusic.unavailable"), command.get().getString());
        library = new MusicLibrary(temp.resolve("replacement"));
    }

    @Test
    void idleFeedbackExplainsNothingToSkipSeekOrStop() {
        for (var command : List.<Supplier<Component>>of(actions::pause, actions::stop, actions::next, actions::previous,
            () -> actions.seek("10"), actions::stopAfter))
            assertEquals(Text.get("upnext.idle"), command.get().getString());
        assertEquals(Text.get("command.darkmusic.no_songs"), actions.play(null).getString());
        assertEquals(Text.get("command.darkmusic.no_songs", "missing"), actions.play("missing").getString());
        assertEquals(Text.get("command.darkmusic.no_songs", "missing"), actions.queue("missing").getString());
        assertTrue(actions.now().getString().contains(Text.get("upnext.idle")));
    }

    private Track playing() {
        var track = new Track("r", "song", temp.resolve("song"), "Song title", "Artist", "Album", 100, 1, 1, "");
        playback.play(List.of(track), track);
        return track;
    }

    @Test
    void playPauseAndStopReportTheirActualState() {
        Track track = playing();
        assertEquals(Text.get("command.darkmusic.already_playing"), actions.play(null).getString());
        assertEquals(Text.get("command.darkmusic.paused"), actions.pause().getString());
        assertEquals(AudioBackend.Status.PAUSED, audio.state.status());
        assertEquals(Text.get("command.darkmusic.already_paused"), actions.pause().getString());
        assertEquals(Text.get("command.darkmusic.playing", track.title()), actions.play(null).getString());
        assertEquals(AudioBackend.Status.PLAYING, audio.state.status());
        assertEquals(Text.get("command.darkmusic.stopped"), actions.stop().getString());
        assertEquals(AudioBackend.Status.STOPPED, audio.state.status());
    }

    @Test
    void seekFeedbackDistinguishesInvalidAndPastTheEndFromAValidPosition() {
        playing();
        assertEquals(Text.get("command.darkmusic.seek_invalid", "invalid"), actions.seek("invalid").getString());
        assertEquals(Text.get("command.darkmusic.seek_past_end", "1:40"), actions.seek("120").getString());
        assertEquals(Text.get("command.darkmusic.position", "0:10 / 1:40"), actions.seek("10").getString());
        assertEquals(10, audio.lastSeek);
    }

    @Test
    void settingsCommandsUseTheSettersAndShowTheirResult() {
        assertEquals(Text.get("command.darkmusic.volume_now", "35%"), actions.volume(35).getString());
        assertEquals(.35, store.settings().volume);
        assertEquals(Text.get("command.darkmusic.muted"), actions.mute().getString());
        assertEquals(Text.get("command.darkmusic.volume_now", "35%"), actions.mute().getString());
        assertEquals(Text.get("command.darkmusic.speed_now", "1.5\u00d7"), actions.speed(1.5).getString());
        assertEquals(1.5, audio.speed);
        assertEquals(Text.get("text.darkmusic.shuffle_on"), actions.shuffle(null).getString());
        assertTrue(playback.shuffle());
        assertNotNull(actions.repeat(Repeat.ALL));
        assertEquals(Repeat.ALL, playback.repeat());
        assertEquals(Text.get("command.darkmusic.sleep_off"), actions.sleep(0).getString());
    }

    @Test
    void nowAndStopAfterDescribeTheRetainedSong() {
        playing();
        String now = actions.now().getString();
        assertTrue(now.contains("Song title"));
        assertTrue(now.contains("Artist"));
        assertEquals(Text.get("command.darkmusic.stopafter_on"), actions.stopAfter().getString());
        assertTrue(playback.stopAfterCurrent());
        assertEquals(Text.get("command.darkmusic.stopafter_off"), actions.stopAfter().getString());
    }

    @Test
    void versionAndMissingUpdateServiceRemainSafeBeforeRegistration() {
        assertEquals("test-version", actions.version());
        assertTrue(actions.shortcuts().isEmpty());
        actions.checkUpdate();
        actions.installUpdate();
        assertFalse(actions.installCode("code"));
        assertTrue(actions.songs().isEmpty());
    }

    @Test
    void songMatchingAndQueueFeedbackUseTheScannedLibrary() throws Exception {
        Path source = TestMusic.featured();
        Path root = Files.createDirectories(temp.resolve("music"));
        Files.copy(source, root.resolve(source.getFileName()));
        library.setRoot(root, "r");
        Await.of(() -> !library.snapshot().scanning() && !library.snapshot().tracks().isEmpty());
        Track track = library.snapshot().tracks().getFirst();
        assertTrue(actions.songs().contains(track.title()));
        assertEquals(Text.get("command.darkmusic.playing", track.title()), actions.play(track.title()).getString());
        assertEquals(Text.get("command.darkmusic.queued", track.title()), actions.queue(track.title()).getString());
        state.outputChanging = true;
        assertEquals(Text.get("command.darkmusic.unavailable"), actions.queue(track.title()).getString());
        state.outputChanging = false;
        assertNull(actions.next());
        assertNull(actions.previous());
    }
}
