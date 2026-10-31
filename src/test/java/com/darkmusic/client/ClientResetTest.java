package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.*;
import com.darkmusic.client.download.*;
import com.darkmusic.client.hud.*;
import com.darkmusic.client.library.*;
import com.darkmusic.client.pip.PipController;
import com.darkmusic.client.testing.FakeAudio;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ClientResetTest {
    @TempDir
    Path temp;
    private final List<String> events = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ClientState state = new ClientState();
    private final SleepTimer sleep = new SleepTimer();
    private final SpeedFeedback speed = new SpeedFeedback();
    private final VolumeFeedback volume = new VolumeFeedback();
    private final Warnings warnings = new Warnings(() -> 1, text -> {
    });
    private final PlaybackController player = new PlaybackController(new FakeAudio(), text -> {
    });
    private final LibraryReconciliation reconciliation = new LibraryReconciliation(null, Runnable::run, Runnable::run, text -> {
    });
    private ConfigStore config;
    private MusicLibrary library;
    private DownloadService downloads;
    private AuditionPlayer audition;
    private ImportHistory imports;
    private Settings settings;
    private ClientReset reset;
    private boolean failCleanup;

    private final class Inputs implements ClientReset.Inputs {
        public Settings settings() {
            return settings;
        }

        public ConfigStore config() {
            return config;
        }

        public ExecutorService io() {
            return io;
        }

        public PlaybackController playback() {
            return player;
        }

        public MusicLibrary library() {
            return library;
        }

        public DownloadService downloads() {
            return downloads;
        }

        public AuditionPlayer takeAudition() {
            events.add("take audition");
            var old = audition;
            audition = null;
            return old;
        }

        public void closeCleanup() {
            events.add("cleanup");
            if (failCleanup) throw new IllegalStateException("fixture cleanup failure");
        }

        public void closeSourceIndex() {
            events.add("index");
        }

        public void forgetCleanupReviews() {
            events.add("reviews");
        }

        public LibraryReconciliation reconciliation() {
            events.add("reconciliation");
            return reconciliation;
        }

        public SleepTimer sleep() {
            events.add("sleep");
            return sleep;
        }

        public SpeedFeedback speedFeedback() {
            return speed;
        }

        public VolumeFeedback volumeFeedback() {
            return volume;
        }

        public AutoCloseable artwork() {
            return () -> events.add("artwork");
        }

        public WaveformCache waveforms() {
            return null;
        }

        public PipController pip() {
            return null;
        }

        public ImportHistory importHistory() {
            events.add("import history");
            return imports;
        }

        public void replaceSettings(Settings value) {
            events.add("settings");
            settings = value;
        }

        public void buildReplaceable(Cleanup built) {
            events.add("build");
            built.first("fixture replacement", () -> events.add("abandon"));
        }

        public Warnings warnings() {
            return warnings;
        }

        public void save() {
            events.add("save");
            config.save(settings);
        }

        public void warn(String text) {
            warnings.warn(text);
        }
    }

    @BeforeEach
    void open() throws Exception {
        Path data = Files.createDirectories(temp.resolve("run/config/darkmusic"));
        config = new ConfigStore(data, io, text -> {
        });
        settings = new Settings();
        settings.volume = .43;
        settings.musicDirectory = temp.resolve("music").toString();
        library = new MusicLibrary(data);
        library.setRoot(Files.createDirectories(temp.resolve("music")), "r");
        downloads = new DownloadService(data, () -> {
        });
        imports = new ImportHistory(data, io, text -> {
        });
        audition = new AuditionPlayer((entry, tools, token) -> {
            throw new AssertionError("not playing");
        }, FakeAudio::new, Runnable::run, () -> {
        });
        var controls = new ClientReset.Controls(() -> events.add("screens"), () -> events.add("folders"),
            () -> Map.of("key_key.darkmusic.open", "key.keyboard.b"), () -> events.add("bindings"),
            path -> events.add("activate"), () -> events.add("picker"));
        reset = new ClientReset(new Inputs(), state, controls, () -> events.add("autoplay"), Runnable::run);
    }

    @AfterEach
    void close() throws Exception {
        if (audition != null) audition.close();
        player.close();
        downloads.close();
        library.close();
        assertTrue(library.awaitStopped(10));
        assertTrue(downloads.awaitStopped(10));
        io.shutdown();
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void admissionRejectsEveryBusyStateBeforeTouchingServices() {
        state.mediaBusy = true;
        assertTrue(reset.resetDefaults().isCompletedExceptionally());
        state.mediaBusy = false;
        state.resetting = true;
        assertTrue(reset.resetDefaults().isCompletedExceptionally());
        state.resetting = false;
        state.closed = true;
        assertTrue(reset.resetDefaults().isCompletedExceptionally());
        assertTrue(events.isEmpty());
    }

    @Test
    void beginTakesAuditionBeforeCleanupAndPreservesThePendingRequestsOrder() {
        var old = audition;
        var work = reset.steps().begin();
        assertNotNull(work);
        assertTrue(state.resetting);
        assertNull(audition);
        assertEquals(List.of("take audition", "cleanup", "index", "reviews", "reconciliation", "screens", "folders", "sleep", "autoplay", "picker", "artwork"), events);
        old.close();
    }

    @Test
    void aCleanupFailureStillRunsTheRemainingStepsAndTheStorageTask() throws Exception {
        failCleanup = true;
        var old = audition;
        var work = reset.steps().begin();
        assertTrue(events.contains("artwork"));
        work.call();
        assertTrue(old.awaitStopped(10));
        assertTrue(downloads.awaitStopped(10));
        assertTrue(library.awaitStopped(10));
        assertTrue(events.contains("import history"));
        assertTrue(Files.readString(temp.resolve("run/options.txt")).contains("key_key.darkmusic.open:key.keyboard.b"));
    }

    @Test
    void publishDefaultsResetsWarningsAndBindingsButFallbackKeepsPriorSettings() {
        Settings prior = settings;
        warnings.warn("prior warning");
        var steps = reset.steps();
        var old = audition;
        steps.begin();
        events.clear();
        steps.publish(false);
        assertSame(prior, settings);
        assertEquals("prior warning", warnings.current());
        assertEquals(List.of("settings", "build", "activate"), events);
        events.clear();
        steps.publish(true);
        assertNotSame(prior, settings);
        assertEquals(Settings.defaults(prior).volume, settings.volume);
        assertEquals("", warnings.current());
        assertEquals(List.of("settings", "build", "bindings", "activate"), events);
        old.close();
    }

    @Test
    void releaseSavesAfterAdmissionReopensAndVerifyChecksTheActualBytes() throws Exception {
        var steps = reset.steps();
        var old = audition;
        steps.begin();
        steps.release();
        assertFalse(state.resetting);
        io.submit(() -> {
        }).get(10, TimeUnit.SECONDS);
        steps.verify();
        Files.writeString(config.directory().resolve("config.json"), "external change");
        assertThrows(IOException.class, steps::verify);
        state.closed = true;
        assertTrue(steps.closed());
        old.close();
    }

    @Test
    void abandoningReplacementClosesOnlyWhatItBuiltOnce() {
        var steps = reset.steps();
        var old = audition;
        steps.begin();
        steps.publish(false);
        steps.abandon();
        steps.abandon();
        assertEquals(1, Collections.frequency(events, "abandon"));
        old.close();
    }

    @Test
    void capturedServicesCloseInAuditionPlaybackDownloadsLibraryOrder() {
        var stopping = ClientReset.stopping(audition, player, downloads, library);
        assertEquals(List.of("audition", "playback", "downloads", "library"), stopping.stream().map(ResetOperation.Stopping::name).toList());
        assertSame(audition, stopping.get(0).service());
        assertSame(player, stopping.get(1).service());
        assertSame(downloads, stopping.get(2).service());
        assertSame(library, stopping.get(3).service());
        assertEquals(List.of("playback", "downloads", "library"), ClientReset.stopping(null, player, downloads, library).stream().map(ResetOperation.Stopping::name).toList());
    }
}
