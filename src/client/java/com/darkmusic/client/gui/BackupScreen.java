package com.darkmusic.client.gui;

import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.text.Text;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Backup preview and category selection; no audio files are ever imported or exported.
 */
public final class BackupScreen extends AudioPage<BackupScreen.Sources> {
    /**
     * What a backup exports and what a restore replaces.
     */
    public interface Sources extends AudioPage.Sources {
        boolean mediaBusy();

        boolean playlistsReady();

        ListeningHistory listeningHistory();

        MusicLibrary library();

        PlaylistStore playlists();

        ConfigStore config();

        ExecutorService io();

        KeyMapping[] shortcutBindings();

        void announce(String text);

        CompletableFuture<Void> restoreBackup(BackupArchive.Restore restored, boolean bindings);
    }

    private BackupArchive.Data data;
    private boolean settings = true, playlists = true, shortcuts = true, history = true, replace, remap = true, busy;
    private String status = "";
    private UiStatus.Tone statusTone = UiStatus.Tone.INFO;
    private boolean available;
    private BackupArchive.Restore prepared;
    private long previewVersion;
    private boolean preparing, previewFailed;

    @Override
    protected void changed() {
        prepared = null;
        previewVersion++;
        preparing = false;
        previewFailed = false;
        rebuildWidgets();
    }

    private boolean available() {
        return !app.mediaBusy() && app.playlistsReady() && app.listeningHistory().ready() && !app.library().snapshot().scanning();
    }

    @Override
    public void tick() {
        super.tick();
        if (available != available()) {
            available = available();
            rebuildWidgets();
        }
    }

    public BackupScreen(Sources app, Screen parent) {
        super(app, parent, "backup.title");
    }

    @Override
    protected void populate() {
        section("backup.title");
        info("backup.scope");
        action("backup.export", () -> choose(true)).runs();
        action("backup.open", () -> choose(false)).runs();
        if (!status.isEmpty()) status(() -> status, () -> statusTone);
        if (data != null) {
            section("backup.preview");
            info(() -> Text.get("backup.count", data.playlists().size(), data.history().size()));
            toggle("backup.settings", () -> settings, v -> settings = v);
            toggle("backup.playlists", () -> playlists, v -> playlists = v);
            toggle("backup.shortcuts", () -> shortcuts, v -> shortcuts = v);
            toggle("backup.history", () -> history, v -> history = v);
            toggle("backup.replace", () -> replace, v -> replace = v);
            toggle("backup.remap", () -> remap, v -> remap = v);
            status(() -> prepared == null ? Text.get("backup.preparing") : Text.get("backup.unresolved", prepared.unresolved()), () -> prepared != null && prepared.unresolved() > 0 ? UiStatus.Tone.WARNING : UiStatus.Tone.INFO);
            action("backup.apply", this::confirmRestore).destructive();
            if (prepared == null && !preparing && !previewFailed) {
                preparing = true;
                long version = previewVersion;
                prepare().whenComplete((preview, error) -> minecraft.execute(() -> {
                    if (version != previewVersion) return;
                    preparing = false;
                    if (error == null) prepared = preview;
                    else {
                        previewFailed = true;
                        status = Text.get("backup.failed");
                        statusTone = UiStatus.Tone.ERROR;
                        LoggerFactory.getLogger("darkmusic").warn("Backup preview failed", error);
                    }
                    if (isCurrent()) rebuildWidgets();
                }));
            }
        }
        available = available();
        enableSince(0, !busy && available);
    }

    private CompletableFuture<BackupArchive.Restore> prepare() {
        var input = data;
        var selected = new BackupArchive.Selection(settings, playlists, shortcuts, history, replace, remap);
        var current = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(app.settings()), Settings.class);
        current.nativeShortcutNames = app.settings().nativeShortcutNames;
        var root = app.library().rootId();
        var tracks = app.library().snapshot().tracks();
        var existing = app.playlists().all();
        var retained = app.playlists().retainedImports();
        return CompletableFuture.supplyAsync(() -> BackupArchive.prepare(input, selected, current, root, tracks, existing,
            app.listeningHistory().snapshot(), retained), app.io());
    }

    private void confirmRestore() {
        if (busy || !available()) return;
        busy = true;
        rebuildWidgets();
        prepare().whenComplete((latest, error) -> minecraft.execute(() -> {
            busy = false;
            if (!isCurrent()) return;
            if (error != null) {
                finish(error);
                return;
            }
            show(new ConfirmationScreen(this, Text.component("backup.apply"), Text.component("backup.confirm", latest.unresolved()),
                Text.component("backup.apply"), () -> app.restoreBackup(latest, latest.selection().shortcuts()), this));
        }));
    }

    private void choose(boolean export) {
        if (busy || app.mediaBusy()) return;
        busy = true;
        rebuildWidgets();
        boolean fullscreen = minecraft.getWindow().isFullscreen();
        if (fullscreen) minecraft.getWindow().toggleFullScreen();
        DesktopFolderPicker.choose(app.config().directory(), Text.get(export ? "backup.export" : "backup.open"), export ? "save" : "open")
            .whenComplete((path, failure) -> minecraft.execute(() -> {
                if (fullscreen && !minecraft.getWindow().isFullscreen()) minecraft.getWindow().toggleFullScreen();
                if (!isCurrent()) {
                    busy = false;
                    return;
                }
                if (failure != null) finish(failure);
                else if (path == null) {
                    busy = false;
                    rebuildWidgets();
                } else transfer(path, export);
            }));
    }

    private void transfer(Path path, boolean export) {
        var bindings = new LinkedHashMap<String, String>();
        if (export) for (var key : app.shortcutBindings()) bindings.put(key.getName(), key.saveString());
        var job = export ? new BackupArchive.Export(app.config().snapshotJson(), app.library().rootId(), app.playlists().all(),
            bindings, app.listeningHistory().snapshotSource()) : null;
        CompletableFuture.supplyAsync(() -> {
            try {
                if (export) {
                    job.write(path);
                    return null;
                }
                return BackupArchive.read(path);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, app.io()).whenComplete((loaded, error) -> minecraft.execute(() -> {
            if (error == null) {
                if (!export) {
                    data = loaded;
                    prepared = null;
                    previewVersion++;
                    preparing = false;
                    previewFailed = false;
                }
                status = Text.get(export ? "backup.exported" : "backup.loaded");
                statusTone = UiStatus.Tone.SUCCESS;
            }
            if (error == null) app.announce(status);
            finish(error);
        }));
    }

    private void finish(Throwable failure) {
        busy = false;
        if (failure != null) {
            LoggerFactory.getLogger("darkmusic").warn("Backup operation failed", failure);
            status = Text.get("backup.failed");
            statusTone = UiStatus.Tone.ERROR;
        }
        if (isCurrent()) rebuildWidgets();
    }
}
