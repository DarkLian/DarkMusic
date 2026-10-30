package com.darkmusic.client;

import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.JournalFixture;
import com.darkmusic.client.config.RestoreJournal;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A backup restore against the real stores and journal in a disposable directory, with the client thread and the
 * storage executor run by hand so that shutdown can land between any two steps. Each check reopens the files the way
 * the next start does.
 */
class BackupRestoreTest {
    /**
     * Serial, like the client's storage executor; runs only when told to.
     */
    private static final class Manual extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void execute(Runnable task) {
            if (shutdown) throw new RejectedExecutionException("stopped");
            tasks.add(task);
        }

        void drain() {
            for (Runnable task; (task = tasks.poll()) != null; ) task.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            var left = List.copyOf(tasks);
            tasks.clear();
            return left;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            drain();
            return true;
        }
    }

    @TempDir
    Path directory;

    private final Manual io = new Manual(), client = new Manual();
    private final ClientState state = new ClientState();
    private Track song;
    private ListeningHistory history;
    private ConfigStore config;
    private SettingsStore settingsStore;
    private PlaylistStore playlists;
    private final Map<String, ListeningHistory.Entry> old = Map.of("root:old.opus", new ListeningHistory.Entry(4, 1000, 400));
    private final Map<String, ListeningHistory.Entry> backup = Map.of("root:backup.opus", new ListeningHistory.Entry(9, 2000, 900));

    @BeforeEach
    void loadOldHistoryAndListen() throws Exception {
        Files.writeString(directory.resolve("listening-history.json"), ListeningHistory.json(old));
        history = new ListeningHistory(directory, io, message -> fail(message));
        history.load();
        config = new ConfigStore(directory, io, message -> fail(message));
        // The client's rule: no settings writes while closed, resetting or restoring.
        settingsStore = new SettingsStore(config::save, () -> state.alive() && !state.restoring, () -> {
        });
        settingsStore.replace(new Settings());
        playlists = new PlaylistStore(directory, io, message -> fail(message));
        song = new Track("root", "old.opus", directory.resolve("old.opus"), "Old", "", "", 120, 1, 1, "");
        // Progress the periodic flush has not written yet.
        history.heard(song, 1, 31);
    }

    private BackupArchive.Restore restore(boolean playlists, boolean history) {
        var settings = new Settings();
        settings.volume = 0.25;
        return new BackupArchive.Restore(settings, List.of(), Set.of(), backup, 0,
            new BackupArchive.Selection(false, playlists, false, history, true, false));
    }

    private CompletableFuture<Void> start(BackupArchive.Restore restored, Runnable applyClient,
                                          BackupRestore.Installer installer) {
        return RestoreOperation.run(new BackupRestore(state, config, settingsStore, playlists, history, restored,
            null, applyClient, installer), client, io);
    }

    private CompletableFuture<Void> start(BackupArchive.Restore restored) {
        return start(restored, () -> {
        }, RestoreJournal::install);
    }

    /**
     * Minecraft closing, in the order {@link Shutdown} runs it; no services are running here.
     */
    private void shutdown() {
        Shutdown.run(new Shutdown.Steps() {
            @Override
            public void saveFinal() {
                settingsStore.save();
            }

            @Override
            public void close() {
                state.closed = true;
            }

            @Override
            public void cancelWork() {
            }

            @Override
            public void flushHistory() {
                history.flush(true);
            }

            @Override
            public void drainStorage() {
                io.shutdown();
                io.drain();
            }

            @Override
            public void confirmSaved() {
            }
        }, new Shutdown.Services(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null));
    }

    private Map<String, ListeningHistory.Entry> nextStart() {
        var reopened = new ListeningHistory(directory, Runnable::run, message -> fail(message));
        reopened.load();
        return reopened.snapshot();
    }

    private Map<String, ListeningHistory.Entry> oldWithProgress() {
        var entry = old.get(song.id());
        return Map.of(song.id(), new ListeningHistory.Entry(entry.plays() + 1, entry.lastPlayed(), entry.seconds() + 31));
    }

    private static void assertSameExceptTime(Map<String, ListeningHistory.Entry> expected,
                                             Map<String, ListeningHistory.Entry> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((key, entry) -> {
            assertEquals(entry.plays(), actual.get(key).plays(), key);
            assertEquals(entry.seconds(), actual.get(key).seconds(), 1e-9, key);
        });
    }

    @Test
    void shutdownBeforeTheInstallRunsKeepsTheRestoredHistory() {
        var restored = start(restore(false, true));
        shutdown();
        client.drain();
        assertTrue(restored.isDone() && !restored.isCompletedExceptionally());
        assertEquals(backup, nextStart());
    }

    @Test
    void shutdownAfterTheInstallButBeforePublicationKeepsTheRestoredHistory() {
        start(restore(false, true));
        io.drain();
        assertEquals(backup, nextStart(), "installed");
        shutdown();
        // Minecraft stopped running tasks, so publication never happens.
        assertEquals(backup, nextStart());
    }

    @Test
    void theFinalSettingsSaveDoesNotReplaceRestoredSettings() throws Exception {
        var restored = restore(false, true);
        var settings = new BackupArchive.Restore(restored.settings(), List.of(), Set.of(), backup, 0,
            new BackupArchive.Selection(true, false, false, true, true, false));
        start(settings);
        io.drain();
        shutdown();
        var saved = ConfigStore.JSON.fromJson(Files.readString(directory.resolve("config.json")), Settings.class);
        assertEquals(0.25, saved.volume);
        assertEquals(backup, nextStart());
    }

    @Test
    void anApplyFailureBeforeTheHistoryIsReplacedKeepsTheRestoredHistory() {
        // A playlist store that cannot take the restored list fails before the history is replaced.
        var restored = new BackupArchive.Restore(new Settings(), Arrays.asList((PlaylistStore.Playlist) null),
            Set.of(), backup, 0, new BackupArchive.Selection(false, true, false, true, true, false));
        var result = start(restored, () -> {
        }, RestoreJournal::install);
        io.drain();
        client.drain();
        var failure = assertThrows(ExecutionException.class, () -> result.get(0, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException, String.valueOf(failure.getCause()));
        assertEquals(backup, nextStart(), "installed");
        // A client tick, then Minecraft closing.
        history.heard(song, 2, 31);
        if (!state.mediaBusy) history.flush(false);
        io.drain();
        boolean held = state.restoring;
        shutdown();
        assertEquals(backup, nextStart());
        assertTrue(held, "the stale in-memory data must not be saved before the restart");
    }

    @Test
    void aPendingJournalSurvivesShutdownSoTheNextStartCanRollBack() throws Exception {
        var result = start(restore(false, true), () -> {
        }, JournalFixture::dieAfterFirstWrite);
        io.drain();
        client.drain();
        assertTrue(result.isCompletedExceptionally());
        assertTrue(RestoreJournal.recoveryPending(directory));
        shutdown();
        // The next start recovers before loading anything.
        assertTrue(RestoreJournal.recover(directory));
        assertEquals(old, nextStart());
    }

    @Test
    void aFailedInstallThatRolledBackResumesWritingTheProgress() throws Exception {
        Files.createDirectories(directory.resolve("playlists.json"));
        var result = start(restore(true, true));
        io.drain();
        client.drain();
        assertTrue(result.isCompletedExceptionally());
        assertFalse(state.restoring);
        shutdown();
        assertSameExceptTime(oldWithProgress(), nextStart());
    }

    @Test
    void restoringOnlyPlaylistsKeepsTheListeningProgress() {
        start(restore(true, false));
        io.drain();
        client.drain();
        shutdown();
        assertSameExceptTime(oldWithProgress(), nextStart());
    }

    @Test
    void aPendingJournalForPlaylistsKeepsTheListeningProgress() {
        var result = start(restore(true, false), () -> {
        }, JournalFixture::dieAfterFirstWrite);
        io.drain();
        client.drain();
        assertTrue(result.isCompletedExceptionally());
        assertTrue(RestoreJournal.recoveryPending(directory));
        history.heard(song, 1, 1);
        shutdown();
        var progress = oldWithProgress();
        assertSameExceptTime(Map.of(song.id(), new ListeningHistory.Entry(progress.get(song.id()).plays(), 0,
            progress.get(song.id()).seconds() + 1)), nextStart());
    }
}
