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
    private final ClientServices services;
    // Reset and restore bump this so a folder check that finishes afterwards cannot apply a stale choice.
    private long generation;

    FolderActions(ClientServices services) {
        this.services = services;
    }

    void invalidate() {
        generation++;
    }

    void activate(Path path) {
        var settings = services.settings();
        var roots = MusicFolders.resolve(settings, path);
        var destination = settings.musicDirectory.isBlank() ? services.config().defaultMusic() : Path.of(settings.musicDirectory);
        services.library().setRoots(roots, destination);
        services.save();
    }

    public void choose(Path directory, Runnable success) {
        if (services.mediaBusy()) {
            services.warn(Text.get("library.darkmusic.busy"));
            return;
        }
        long ticket = generation;
        services.browse(() -> {
            try {
                if (!Files.isDirectory(directory) || !Files.isReadable(directory))
                    throw new IOException("Directory unavailable or unreadable");
                Path canonical = directory.toRealPath();
                Minecraft.getInstance().execute(() -> {
                    if (services.state.idle() && ticket == generation) {
                        var library = services.library();
                        if (!library.roots().containsKey(canonical) && MusicFolders.overlaps(library.roots().keySet(), canonical)) {
                            services.warn(Text.get("folders.overlap"));
                            return;
                        }
                        var settings = services.settings();
                        var folders = new ArrayList<>(settings.musicFolders);
                        if (!folders.contains(canonical.toString())) folders.add(canonical.toString());
                        settings.musicFolders = List.copyOf(folders);
                        settings.musicDirectory = canonical.toString();
                        activate(canonical);
                        success.run();
                    }
                });
            } catch (Exception e) {
                services.warn(Text.failure(e, "error.folder"));
            }
        });
    }

    public void reset() {
        long ticket = generation;
        services.browse(() -> {
            try {
                Files.createDirectories(services.config().defaultMusic());
                Minecraft.getInstance().execute(() -> {
                    if (!services.state.alive() || ticket != generation) return;
                    choose(services.config().defaultMusic(), () -> {
                        services.update(s -> s.musicDirectory = "");
                    });
                });
            } catch (Exception e) {
                services.warn(Text.failure(e, "error.folder"));
            }
        });
    }

    public void add(Path directory) {
        long ticket = generation;
        services.browse(() -> {
            try {
                var canonical = directory.toRealPath();
                if (!Files.isDirectory(canonical) || !Files.isReadable(canonical))
                    throw new IOException("Unreadable music folder");
                Minecraft.getInstance().execute(() -> {
                    if (!services.state.idle() || ticket != generation) return;
                    if (MusicFolders.overlaps(services.library().roots().keySet(), canonical)) {
                        services.warn(Text.get("folders.overlap"));
                        return;
                    }
                    var settings = services.settings();
                    var folders = new ArrayList<>(settings.musicFolders);
                    folders.add(canonical.toString());
                    settings.musicFolders = List.copyOf(folders);
                    activate(services.library().root());
                });
            } catch (Exception e) {
                services.warn(Text.failure(e, "error.folder"));
            }
        });
    }

    public boolean remove(Path directory) {
        if (!services.state.idle()) {
            services.warn(Text.get("library.darkmusic.busy"));
            return false;
        }
        if (directory.equals(services.library().root())) return false;
        services.settings().musicFolders = services.settings().musicFolders.stream().filter(p -> !Path.of(p).equals(directory)).toList();
        activate(services.library().root());
        return true;
    }

    public void open() {
        open(services.library().root());
    }

    public void open(Path directory) {
        services.browse(() -> {
            try {
                if (!Files.isDirectory(directory))
                    throw new IOException("The configured music directory is unavailable");
                Util.getPlatform().openPath(directory);
            } catch (Exception e) {
                services.warn(Text.failure(e, "error.folder"));
            }
        });
    }
}
