package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.BackupArchive;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Backup preview and category selection; no audio files are ever imported or exported.
 */
public final class BackupScreen extends AudioPage {
    private BackupArchive.Data data;
    private boolean settings = true, playlists = true, shortcuts = true, history = true, replace, remap = true, busy;
    private String status = "";
    private boolean available;

    private boolean available() {
        return !app.mediaBusy && app.playlistsReady && app.listeningHistory.ready() && !app.library.snapshot().scanning();
    }

    @Override
    public void tick() {
        super.tick();
        if (available != available()) {
            available = available();
            rebuildWidgets();
        }
    }

    public BackupScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "backup.title");
    }

    @Override
    protected void populate() {
        section("backup.title");
        info("backup.scope");
        action("backup.export", () -> choose(true));
        action("backup.open", () -> choose(false));
        if (!status.isEmpty()) info(() -> status);
        if (data != null) {
            section("backup.preview");
            info(() -> Text.get("backup.count", data.playlists().size(), data.history().size()));
            toggle("backup.settings", () -> settings, v -> settings = v);
            toggle("backup.playlists", () -> playlists, v -> playlists = v);
            toggle("backup.shortcuts", () -> shortcuts, v -> shortcuts = v);
            toggle("backup.history", () -> history, v -> history = v);
            toggle("backup.replace", () -> replace, v -> replace = v);
            toggle("backup.remap", () -> remap, v -> remap = v);
            var prepared = prepare();
            info(() -> Text.get("backup.unresolved", prepared.unresolved()));
            action("backup.apply", () -> {
                var latest = prepare();
                show(new ConfirmationScreen(this, Text.component("backup.apply"),
                    Text.component("backup.confirm", latest.unresolved()), Text.component("backup.apply"),
                    () -> app.restoreBackup(prepare(), shortcuts), this));
            });
        }
        available = available();
        enableSince(0, !busy && available);
    }

    private BackupArchive.Restore prepare() {
        return BackupArchive.prepare(data, new BackupArchive.Selection(settings, playlists, shortcuts, history, replace, remap),
            app.settings, app.library.rootId(), app.library.snapshot().tracks(), app.playlists.all(), app.listeningHistory.snapshot(), app.playlists.retainedImports());
    }

    private void choose(boolean export) {
        if (busy || app.mediaBusy) return;
        busy = true;
        rebuildWidgets();
        boolean fullscreen = minecraft.getWindow().isFullscreen();
        if (fullscreen) minecraft.getWindow().toggleFullScreen();
        DesktopFolderPicker.choose(app.config.directory(), Text.get(export ? "backup.export" : "backup.open"), export ? "save" : "open")
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
        var copy = com.darkmusic.client.config.ConfigStore.JSON.fromJson(com.darkmusic.client.config.ConfigStore.JSON.toJson(app.settings), com.darkmusic.client.config.Settings.class);
        copy.shortcuts.clear();
        for (var key : app.bindings()) copy.shortcuts.put(key.getName(), key.saveString());
        var snapshot = BackupArchive.snapshot(copy, app.library.rootId(), app.playlists.all(), app.listeningHistory.snapshot());
        CompletableFuture.supplyAsync(() -> {
            try {
                if (export) {
                    BackupArchive.write(path, snapshot);
                    return null;
                }
                return BackupArchive.read(path);
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, app.io).whenComplete((loaded, error) -> minecraft.execute(() -> {
            if (error == null) {
                if (!export) data = loaded;
                status = Text.get(export ? "backup.exported" : "backup.loaded");
            }
            if (error == null) app.announce(status);
            finish(error);
        }));
    }

    private void finish(Throwable failure) {
        busy = false;
        if (failure != null) {
            org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Backup operation failed", failure);
            status = Text.get("backup.failed");
        }
        if (isCurrent()) rebuildWidgets();
    }
}
