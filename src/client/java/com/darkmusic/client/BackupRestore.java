package com.darkmusic.client;

import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.RestoreJournal;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.playlist.PlaylistStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The client's side of {@link RestoreOperation}: which files a backup restore writes and which stores it replaces.
 * Key binds and playback are Minecraft's, so the client passes them in as one step that runs after the stores.
 */
final class BackupRestore implements RestoreOperation.Steps {
    /**
     * Writes the selected files under the journal; tests replace it to end an install part way.
     */
    @FunctionalInterface
    interface Installer {
        void install(Path directory, Map<String, String> contents) throws IOException;
    }

    private final ClientState state;
    private final ConfigStore config;
    private final SettingsStore settingsStore;
    private final PlaylistStore playlists;
    private final ListeningHistory history;
    private final BackupArchive.Restore restored;
    private final Map<String, String> keys;
    private final Runnable applyClient;
    private final Installer installer;

    /**
     * @param keys        the key binds to write to options.txt, or null to leave them alone
     * @param applyClient applies the key binds and the playback settings, then the settings to the services
     */
    BackupRestore(ClientState state, ConfigStore config, SettingsStore settingsStore, PlaylistStore playlists,
                  ListeningHistory history, BackupArchive.Restore restored, Map<String, String> keys,
                  Runnable applyClient) {
        this(state, config, settingsStore, playlists, history, restored, keys, applyClient, RestoreJournal::install);
    }

    BackupRestore(ClientState state, ConfigStore config, SettingsStore settingsStore, PlaylistStore playlists,
                  ListeningHistory history, BackupArchive.Restore restored, Map<String, String> keys,
                  Runnable applyClient, Installer installer) {
        this.state = state;
        this.config = config;
        this.settingsStore = settingsStore;
        this.playlists = playlists;
        this.history = history;
        this.restored = restored;
        this.keys = keys;
        this.applyClient = applyClient;
        this.installer = installer;
    }

    @Override
    public void begin() {
        state.mediaBusy = state.restoring = true;
        if (restored.selection().history()) history.suspended(true);
    }

    @Override
    public void install() throws IOException {
        var contents = new LinkedHashMap<String, String>();
        if (restored.selection().settings() || keys != null)
            contents.put("config.json", ConfigStore.JSON.toJson(restored.settings()));
        if (restored.selection().playlists()) {
            contents.put("playlists.json", ConfigStore.JSON.toJson(restored.playlists()));
            contents.put("playlist-retained.json", ConfigStore.JSON.toJson(restored.retained()));
        }
        if (restored.selection().history())
            contents.put("listening-history.json", ListeningHistory.json(restored.history()));

        if (keys != null) contents.put("options.txt", config.shortcutsContents(keys));
        installer.install(config.directory(), contents);
        if (contents.containsKey("config.json")) config.committedSnapshot(contents.get("config.json"));
    }

    @Override
    public void apply() {
        if (restored.selection().settings() || keys != null) settingsStore.replace(restored.settings());
        if (restored.selection().playlists())
            playlists.restoredFromDisk(restored.playlists(), restored.retained());
        if (restored.selection().history()) history.replace(restored.history());
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
        if (restored.selection().history()) history.suspended(hold);
    }

    @Override
    public boolean closed() {
        return state.closed;
    }
}
