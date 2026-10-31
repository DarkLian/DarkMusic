package com.darkmusic.client;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A restore is admitted only while the client accepts exclusive work and both the playlists and the listening history
 * have loaded, and imported shortcuts are checked before the restore begins. The restore itself is RestoreOperation's
 * and BackupRestore's; their tests cover it.
 */
class BackupActionsTest {
    @TempDir
    Path temp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Runnable> client = new ArrayDeque<>();
    private final ClientState state = new ClientState();
    private final SettingsStore store = new SettingsStore(s -> CompletableFuture.completedFuture(null), () -> true, () -> {
    });
    private final KeyMapping key = new KeyMapping("key.darkmusic.backup_test", InputConstants.Type.KEYSYM, 65, KeyMapping.Category.MISC);
    private boolean ready = true;
    private int invalidated;
    private ConfigStore config;
    private MusicLibrary library;
    private PlaylistStore playlists;
    private ListeningHistory history;
    private BackupActions backups;

    @BeforeEach
    void open() throws Exception {
        config = new ConfigStore(Files.createDirectories(temp.resolve("config")), io, text -> {
        });
        library = new MusicLibrary(temp.resolve("data"));
        playlists = new PlaylistStore(temp.resolve("data"), io, text -> {
        });
        playlists.loaded(List.of());
        history = new ListeningHistory(temp.resolve("data"), Runnable::run, text -> {
        });
        history.load();
        store.replace(new Settings());
        backups = new BackupActions(new BackupActions.Inputs() {
            public ConfigStore config() {
                return config;
            }

            public ExecutorService io() {
                return io;
            }

            public MusicLibrary library() {
                return library;
            }

            public PlaybackController playback() {
                return null;
            }

            public PlaylistStore playlists() {
                return playlists;
            }

            public boolean playlistsReady() {
                return ready;
            }

            public ListeningHistory listeningHistory() {
                return history;
            }

            public void apply() {
            }
        }, state, store, () -> invalidated++, () -> new KeyMapping[]{key}, client::add);
    }

    @AfterEach
    void close() {
        library.close();
        io.shutdownNow();
    }

    private static BackupArchive.Data data(JsonObject settings) {
        return new BackupArchive.Data(1, "root", settings, List.of(), Map.of());
    }

    private static Throwable failure(CompletableFuture<?> future) {
        assertTrue(future.isCompletedExceptionally());
        return assertThrows(ExecutionException.class, future::get).getCause();
    }

    @Test
    void aRestoreWaitsForAcceptingWorkLoadedPlaylistsAndLoadedHistory() {
        var everything = new BackupArchive.Selection(true, true, true, true, true, false);
        for (int blocked = 0; blocked < 4; blocked++) {
            state.mediaBusy = blocked == 0;
            state.outputChanging = blocked == 1;
            ready = blocked != 2;
            if (blocked == 3) history = new ListeningHistory(temp.resolve("unloaded"), Runnable::run, text -> {
            });
            var refused = failure(backups.restoreBackup(data(new JsonObject()), everything));
            assertEquals(Text.get("library.darkmusic.busy"), refused.getMessage());
        }
        assertFalse(state.restoring);
        assertTrue(client.isEmpty());
        assertEquals(0, invalidated);
    }

    @Test
    void anInvalidShortcutFailsBeforeTheRestoreBegins() throws Exception {
        var settings = new JsonObject();
        var shortcuts = new JsonObject();
        shortcuts.addProperty(key.getName(), "key.keyboard.nonsense");
        settings.add("shortcuts", shortcuts);
        var result = backups.restoreBackup(data(settings), new BackupArchive.Selection(false, false, true, false, false, false));
        var refused = failure(result);
        assertInstanceOf(IllegalArgumentException.class, refused);
        assertEquals(Text.get("error.shortcut_invalid"), refused.getMessage());
        assertFalse(state.restoring);
        assertTrue(client.isEmpty());
        assertEquals("key.keyboard.a", key.saveString(), "the binding is unchanged");
    }

    @Test
    void unreadableSettingsFailAsABackupError() {
        var settings = new JsonObject();
        settings.addProperty("volume", "loud");
        var refused = failure(backups.restoreBackup(data(settings), new BackupArchive.Selection(false, false, true, false, false, false)));
        assertInstanceOf(IOException.class, refused);
        assertEquals(Text.get("backup.failed"), refused.getMessage());
    }

    @Test
    void anExportCarriesTheBindingsAndRunsOnTheStorageExecutor() throws Exception {
        library.setRoot(Files.createDirectories(temp.resolve("music")), "root");
        config.load();
        Path file = temp.resolve("backup.zip");
        backups.exportBackup(file).get();
        var read = backups.readBackup(file).get();
        assertEquals("root", read.root());
        assertTrue(Files.size(file) > 0);
    }
}
