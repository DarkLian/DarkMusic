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
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Backup export, reading and restore preview on the storage executor; {@link RestoreOperation} owns a restore's
 * phases. Started on the client thread.
 */
final class BackupActions {
    private final Inputs services;
    private final ClientState state;
    private final SettingsStore store;
    private final Runnable invalidateFolders;
    private final Supplier<KeyMapping[]> bindings;
    private final Executor clientThread;

    interface Inputs {
        ConfigStore config();

        ExecutorService io();

        MusicLibrary library();

        PlaybackController playback();

        PlaylistStore playlists();

        boolean playlistsReady();

        ListeningHistory listeningHistory();

        void apply();
    }

    /**
     * @param invalidateFolders voids a folder check still running, once the settings object is replaced
     * @param bindings          the mod's key mappings, saved and restored with the backup
     */
    BackupActions(Inputs services, ClientState state, SettingsStore store, Runnable invalidateFolders,
                  Supplier<KeyMapping[]> bindings, Executor clientThread) {
        this.services = services;
        this.state = state;
        this.store = store;
        this.invalidateFolders = invalidateFolders;
        this.bindings = bindings;
        this.clientThread = clientThread;
    }

    CompletableFuture<BackupArchive.Restore> previewRestore(BackupArchive.Data data, BackupArchive.Selection selection) {
        var library = services.library();
        var current = BackupRestore.Current.capture(store.settings(), library.rootId(), library.snapshot().tracks(),
            services.playlists(), services.listeningHistory());
        return CompletableFuture.supplyAsync(() -> current.prepare(data, selection), services.io());
    }

    CompletableFuture<BackupArchive.Data> readBackup(Path source) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return BackupArchive.read(source);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, services.io());
    }

    CompletableFuture<Void> exportBackup(Path destination) {
        var bindings = new LinkedHashMap<String, String>();
        for (var key : this.bindings.get()) bindings.put(key.getName(), key.saveString());
        var job = new BackupArchive.Export(services.config().snapshotJson(), services.library().rootId(),
            services.playlists().all(), bindings, services.listeningHistory().snapshotSource());
        return CompletableFuture.runAsync(() -> {
            try {
                job.write(destination);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, services.io());
    }

    CompletableFuture<Void> restoreBackup(BackupArchive.Data data, BackupArchive.Selection selection) {
        if (!state.accepting() || !services.playlistsReady() || !services.listeningHistory().ready())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("library.darkmusic.busy")));
        boolean bindings = selection.shortcuts();
        Map<String, String> imported = Map.of();
        if (bindings) try {
            var values = ConfigStore.JSON.fromJson(data.settings(), Settings.class);
            values.validate();
            imported = values.shortcuts;
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(new IOException(Text.get("backup.failed"), invalid));
        }
        var keys = new LinkedHashMap<String, String>();
        if (bindings) for (var key : this.bindings.get()) {
            String value = imported.getOrDefault(key.getName(), key.saveString());
            try {
                InputConstants.getKey(value);
            } catch (RuntimeException invalid) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(Text.get("error.shortcut_invalid")));
            }
            keys.put("key_" + key.getName(), value);
        }
        var library = services.library();
        return RestoreOperation.run(new BackupRestore(state, services.config(), store,
            services.playlists(), services.listeningHistory(), library.rootId(), library.snapshot().tracks(), data,
            selection, bindings ? keys : null, () -> {
            // The settings object was just replaced: a folder check still running must not apply to it.
            if (selection.settings() || bindings) invalidateFolders.run();
            if (bindings) {
                for (var key : this.bindings.get())
                    key.setKey(InputConstants.getKey(keys.get("key_" + key.getName())));
                KeyMapping.resetMapping();
            }
            var playback = services.playback();
            playback.volume(store.settings().volume);
            playback.speed(store.settings().speed);
            playback.shuffle(store.settings().shuffle);
            playback.repeat(store.settings().repeat);
            // The journal already wrote the files; the next save writes anything applying normalized.
            services.apply();
        }), clientThread, services.io());
    }
}
