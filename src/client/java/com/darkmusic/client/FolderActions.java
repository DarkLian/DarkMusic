package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.MusicFolders;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * Music folder choices from menus. Checks run on the browse pool; settings change on the client thread only.
 */
public final class FolderActions {
    private final Inputs services;
    private final ClientState state;
    private final SettingsStore store;
    private final Executor clientThread;
    // Reset and restore bump this so a folder check that finishes afterwards cannot apply a stale choice.
    private long generation;

    interface Inputs {
        ConfigStore config();

        MusicLibrary library();

        Future<?> browse(Runnable task);

        void warn(String text);
    }

    FolderActions(Inputs services, ClientState state, SettingsStore store, Executor clientThread) {
        this.services = services;
        this.state = state;
        this.store = store;
        this.clientThread = clientThread;
    }

    void invalidate() {
        generation++;
    }

    void activate(Path path) {
        var settings = store.settings();
        var roots = MusicFolders.resolve(settings, path);
        var destination = settings.musicDirectory.isBlank() ? services.config().defaultMusic() : Path.of(settings.musicDirectory);
        services.library().setRoots(roots, destination);
        store.save();
    }

    public void choose(Path directory, Runnable success) {
        if (state.mediaBusy) {
            services.warn(Text.get("library.darkmusic.busy"));
            return;
        }
        long ticket = generation;
        services.browse(() -> {
            try {
                if (!Files.isDirectory(directory) || !Files.isReadable(directory))
                    throw new IOException("Directory unavailable or unreadable");
                Path canonical = directory.toRealPath();
                clientThread.execute(() -> {
                    if (state.idle() && ticket == generation) {
                        var library = services.library();
                        if (!library.roots().containsKey(canonical) && MusicFolders.overlaps(library.roots().keySet(), canonical)) {
                            services.warn(Text.get("folders.overlap"));
                            return;
                        }
                        var settings = store.settings();
                        var folders = new ArrayList<>(MusicFolders.folders(settings, services.config().defaultMusic()));
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
                clientThread.execute(() -> {
                    if (!state.alive() || ticket != generation) return;
                    choose(services.config().defaultMusic(), () -> {
                        store.update(s -> s.musicDirectory = "");
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
                clientThread.execute(() -> {
                    if (!state.idle() || ticket != generation) return;
                    if (MusicFolders.overlaps(services.library().roots().keySet(), canonical)) {
                        services.warn(Text.get("folders.overlap"));
                        return;
                    }
                    var settings = store.settings();
                    var folders = new ArrayList<>(MusicFolders.folders(settings, services.config().defaultMusic()));
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
        if (!state.idle()) {
            services.warn(Text.get("library.darkmusic.busy"));
            return false;
        }
        if (directory.equals(services.library().root())) return false;
        var settings = store.settings();
        settings.musicFolders = MusicFolders.folders(settings, services.config().defaultMusic()).stream().filter(p -> !Path.of(p).equals(directory)).toList();
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
