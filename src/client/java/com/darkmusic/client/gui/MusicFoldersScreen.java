package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Native folder picking with explicit, non-destructive scan registration and write routing.
 */
public final class MusicFoldersScreen extends AudioPage {
    private java.util.Map<Path, String> shown;
    private Path destination;

    public MusicFoldersScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "folders.title");
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.FOLDER;
    }

    @Override
    protected void populate() {
        shown = app.library.roots();
        destination = app.library.root();
        action("folders.add", this::choose);
        action("settings.darkmusic.default_folder", app::resetMusicFolder);
        action("delete_audio.manage", () -> show(new LibraryManagerScreen(app, this)));
        info("folders.scope");
        for (var entry : shown.entrySet()) {
            Path path = entry.getKey();
            String name = com.darkmusic.client.library.MusicFolders.label(path, shown.keySet());
            var row = new SettingRow(formX, 0, formWidth, name + (path.equals(destination) ? " • " + Text.get("folders.default") : ""), () -> path.equals(app.library.root()), () -> app.chooseDirectory(path, () -> {
            })).accentLabel();
            place(row);
            Help.tip(row, path.toString());
            row.active = !path.equals(destination);
            labeledAction(Text.get("folders.remove_action"), Text.get("folders.remove_help"), () -> show(new ConfirmationScreen(this,
                Text.component("folders.remove", name), Text.component("folders.remove_help"), Text.component("folders.remove_action"), () -> {
                if (!app.removeMusicFolder(path))
                    return CompletableFuture.failedFuture(new IllegalStateException(Text.get(app.mediaBusy ? "library.darkmusic.busy" : "folders.remove_blocked")));
                return CompletableFuture.completedFuture(null);
            }))).active = !path.equals(destination);
        }
    }

    private void choose() {
        boolean fullscreen = minecraft.getWindow().isFullscreen();
        if (fullscreen) minecraft.getWindow().toggleFullScreen();
        DesktopFolderPicker.choose(destination, Text.get("folders.add")).whenComplete((path, failure) -> minecraft.execute(() -> {
            if (fullscreen && !minecraft.getWindow().isFullscreen()) minecraft.getWindow().toggleFullScreen();
            if (!isCurrent()) return;
            if (failure != null) app.warn(Text.failure(failure, "error.folder"));
            else if (path != null) app.addMusicFolder(path);
        }));
    }

    @Override
    public void tick() {
        if (!shown.equals(app.library.roots()) || !destination.equals(app.library.root())) rebuildWidgets();
    }
}
