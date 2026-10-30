package com.darkmusic.client;

import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.RestoreJournal;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The client's side of {@link RestoreOperation}: what a backup restore merges with, which files it writes and which
 * stores it replaces. Key binds and playback are Minecraft's, so the client passes them in as one step that runs
 * after the stores.
 * <p>
 * The backup is merged with the state current when the restore starts, not when it was previewed: the music keeps
 * playing while the confirmation is open, and that listening must not be lost.
 */
final class BackupRestore implements RestoreOperation.Steps {
    /**
     * Writes the selected files under the journal; tests replace it to end an install part way.
     */
    @FunctionalInterface
    interface Installer {
        void install(Path directory, Map<String, String> contents) throws IOException;
    }

    /**
     * What a backup is merged with, captured on the client thread. The history is copied later, on the storage
     * executor; a restore captures it after suspending history, so nothing changes it in between.
     */
    record Current(Settings settings, String root, List<Track> tracks, List<PlaylistStore.Playlist> playlists,
                   Set<PlaylistStore.Entry> retained, Supplier<Map<String, ListeningHistory.Entry>> history) {
        static Current capture(Settings live, String root, List<Track> tracks, PlaylistStore playlists,
                               ListeningHistory history) {
            var settings = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(live), Settings.class);
            settings.nativeShortcutNames = live.nativeShortcutNames;
            return new Current(settings, root, tracks, playlists.all(), playlists.retainedImports(),
                history.snapshotSource());
        }

        /**
         * Storage executor.
         */
        BackupArchive.Restore prepare(BackupArchive.Data data, BackupArchive.Selection selection) {
            return BackupArchive.prepare(data, selection, settings, root, tracks, playlists, history.get(), retained);
        }
    }

    private final ClientState state;
    private final ConfigStore config;
    private final SettingsStore settingsStore;
    private final PlaylistStore playlists;
    private final ListeningHistory history;
    private final String root;
    private final List<Track> tracks;
    private final BackupArchive.Data data;
    private final BackupArchive.Selection selection;
    private final Map<String, String> keys;
    private final Runnable applyClient;
    private final Installer installer;
    private Current current;
    private BackupArchive.Restore restored;

    /**
     * @param root        the library's root id, with tracks its current snapshot
     * @param keys        the key binds to write to options.txt, or null to leave them alone
     * @param applyClient applies the key binds and the playback settings, then the settings to the services
     */
    BackupRestore(ClientState state, ConfigStore config, SettingsStore settingsStore, PlaylistStore playlists,
                  ListeningHistory history, String root, List<Track> tracks, BackupArchive.Data data,
                  BackupArchive.Selection selection, Map<String, String> keys, Runnable applyClient) {
        this(state, config, settingsStore, playlists, history, root, tracks, data, selection, keys, applyClient,
            RestoreJournal::install);
    }

    BackupRestore(ClientState state, ConfigStore config, SettingsStore settingsStore, PlaylistStore playlists,
                  ListeningHistory history, String root, List<Track> tracks, BackupArchive.Data data,
                  BackupArchive.Selection selection, Map<String, String> keys, Runnable applyClient,
                  Installer installer) {
        this.state = state;
        this.config = config;
        this.settingsStore = settingsStore;
        this.playlists = playlists;
        this.history = history;
        this.root = root;
        this.tracks = tracks;
        this.data = data;
        this.selection = selection;
        this.keys = keys;
        this.applyClient = applyClient;
        this.installer = installer;
    }

    @Override
    public void begin() {
        state.mediaBusy = state.restoring = true;
        if (selection.history()) history.suspended(true);
        current = Current.capture(settingsStore.settings(), root, tracks, playlists, history);
    }

    @Override
    public void install() throws IOException {
        restored = current.prepare(data, selection);
        var contents = new LinkedHashMap<String, String>();
        if (selection.settings() || keys != null)
            contents.put("config.json", ConfigStore.JSON.toJson(restored.settings()));
        if (selection.playlists()) {
            contents.put("playlists.json", ConfigStore.JSON.toJson(restored.playlists()));
            contents.put("playlist-retained.json", ConfigStore.JSON.toJson(restored.retained()));
        }
        if (selection.history())
            contents.put("listening-history.json", ListeningHistory.json(restored.history()));

        if (keys != null) contents.put("options.txt", config.shortcutsContents(keys));
        installer.install(config.directory(), contents);
        if (contents.containsKey("config.json")) config.committedSnapshot(contents.get("config.json"));
    }

    @Override
    public void apply() {
        if (selection.settings() || keys != null) settingsStore.replace(restored.settings());
        if (selection.playlists()) playlists.restoredFromDisk(restored.playlists(), restored.retained());
        if (selection.history()) history.replace(restored.history());
        applyClient.run();
    }

    @Override
    public boolean recoveryPending() {
        return RestoreJournal.recoveryPending(config.directory());
    }

    @Override
    public void end(boolean hold) {
        state.mediaBusy = state.restoring = hold;
        // Only a restore that wrote the history file owns it; otherwise listening keeps counting and saving.
        if (selection.history()) history.suspended(hold);
    }

    @Override
    public boolean closed() {
        return state.closed;
    }
}
