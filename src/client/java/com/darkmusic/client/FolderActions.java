package com.darkmusic.client;

import com.darkmusic.client.library.MusicFolders;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Music folder choices from menus. Checks run on the browse pool; settings change on the client thread only.
 */
public final class FolderActions {
    private final DarkMusicClient app;
    // Reset bumps this so a folder check that finishes afterwards cannot apply a stale choice.
    private long generation;

    FolderActions(DarkMusicClient app) {
        this.app = app;
    }

    void invalidate() {
        generation++;
    }

    void activate(Path path) {
        var settings = app.settings();
        var roots = MusicFolders.resolve(settings, path);
        var destination = settings.musicDirectory.isBlank() ? app.config.defaultMusic() : Path.of(settings.musicDirectory);
        app.library.setRoots(roots, destination);
        app.save();
    }

    public void choose(Path directory, Runnable success) {
        if (app.mediaBusy()) {
            app.warn(Text.get("library.darkmusic.busy"));
            return;
        }
        long ticket = generation;
        app.browse(() -> {
            try {
                if (!Files.isDirectory(directory) || !Files.isReadable(directory))
                    throw new IOException("Directory unavailable or unreadable");
                Path canonical = directory.toRealPath();
                Minecraft.getInstance().execute(() -> {
                    if (app.idle() && ticket == generation) {
                        var library = app.library;
                        if (!library.roots().containsKey(canonical) && MusicFolders.overlaps(library.roots().keySet(), canonical)) {
                            app.warn(Text.get("folders.overlap"));
                            return;
                        }
                        var settings = app.settings();
                        var folders = new ArrayList<>(settings.musicFolders);
                        if (!folders.contains(canonical.toString())) folders.add(canonical.toString());
                        settings.musicFolders = List.copyOf(folders);
                        settings.musicDirectory = canonical.toString();
                        activate(canonical);
                        success.run();
                    }
                });
            } catch (Exception e) {
                app.warn(Text.failure(e, "error.folder"));
            }
        });
    }

    public void reset() {
        app.browse(() -> {
            try {
                Files.createDirectories(app.config.defaultMusic());
                Minecraft.getInstance().execute(() -> {
                    if (!app.alive()) return;
                    choose(app.config.defaultMusic(), () -> {
                        app.settings().musicDirectory = "";
                        app.save();
                    });
                });
            } catch (Exception e) {
                app.warn(Text.failure(e, "error.folder"));
            }
        });
    }

    public void add(Path directory) {
        long ticket = generation;
        app.browse(() -> {
            try {
                var canonical = directory.toRealPath();
                if (!Files.isDirectory(canonical) || !Files.isReadable(canonical))
                    throw new IOException("Unreadable music folder");
                Minecraft.getInstance().execute(() -> {
                    if (!app.idle() || ticket != generation) return;
                    if (MusicFolders.overlaps(app.library.roots().keySet(), canonical)) {
                        app.warn(Text.get("folders.overlap"));
                        return;
                    }
                    var settings = app.settings();
                    var folders = new ArrayList<>(settings.musicFolders);
                    folders.add(canonical.toString());
                    settings.musicFolders = List.copyOf(folders);
                    activate(app.library.root());
                });
            } catch (Exception e) {
                app.warn(Text.failure(e, "error.folder"));
            }
        });
    }

    public boolean remove(Path directory) {
        if (!app.idle()) {
            app.warn(Text.get("library.darkmusic.busy"));
            return false;
        }
        if (directory.equals(app.library.root())) return false;
        app.settings().musicFolders = app.settings().musicFolders.stream().filter(p -> !Path.of(p).equals(directory)).toList();
        activate(app.library.root());
        return true;
    }

    public void open() {
        open(app.library.root());
    }

    public void open(Path directory) {
        app.browse(() -> {
            try {
                if (!Files.isDirectory(directory))
                    throw new IOException("The configured music directory is unavailable");
                Util.getPlatform().openPath(directory);
            } catch (Exception e) {
                app.warn(Text.failure(e, "error.folder"));
            }
        });
    }
}
