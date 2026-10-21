package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.MusicScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DarkMusicClient implements ClientModInitializer {
    public static DarkMusicClient INSTANCE;
    public final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-storage").factory());
    public final ExecutorService browsing = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("darkmusic-browse").factory(), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    public final ConfigStore config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("darkmusic"), io, this::warn);
    public final com.darkmusic.client.hud.SpeedFeedback speedFeedback = new com.darkmusic.client.hud.SpeedFeedback();
    public final com.darkmusic.client.hud.VolumeFeedback volumeFeedback = new com.darkmusic.client.hud.VolumeFeedback();
    public volatile Settings settings;
    public volatile String warning = "";
    public KeyMapping open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute, seekBack, seekForward, stop, jump;
    public com.darkmusic.client.library.MusicLibrary library;
    public com.darkmusic.client.audio.PlaybackController playback;
    private com.darkmusic.client.audio.JavaSoundBackend backend;
    public com.darkmusic.client.library.ListeningHistory listeningHistory;
    private boolean outputChanging;

    private com.darkmusic.client.audio.PlaybackController createPlayback() {
        backend = new com.darkmusic.client.audio.JavaSoundBackend(Minecraft.getInstance()::execute,
            com.darkmusic.client.audio.PcmDecoder::openPrecise,
            new com.darkmusic.client.audio.OutputDevices(settings.outputDevice, true, this::warn));
        backend.listening((track, session, seconds) -> {
            if (listeningHistory != null) listeningHistory.heard(track, session, seconds);
        });
        var controller = new com.darkmusic.client.audio.PlaybackController(backend, this::warn);
        controller.onSourceChanged((source, queue) -> listeningHistory.playbackSource(source.rootId(), source.mode().equals("History") ? source.group() : null,
            queue, library.snapshot().tracks()));
        return controller;
    }

    public String outputDeviceId() {
        return backend == null ? "" : backend.outputDeviceId();
    }

    public CompletableFuture<Boolean> selectOutputDevice(String id) {
        var result = new CompletableFuture<Boolean>();
        if (backend == null || mediaBusy || resetting || outputChanging) {
            result.complete(false);
            return result;
        }
        outputChanging = true;
        var selected = backend;
        selected.output(new com.darkmusic.client.audio.OutputDevices(id, false, this::warn)).whenComplete((ok, failure) -> Minecraft.getInstance().execute(() -> {
            outputChanging = false;
            boolean applied = !closed && backend == selected && failure == null && ok;
            if (applied) {
                settings.outputDevice = id;
                playback.outputChanged();
                save();
            }
            result.complete(applied);
        }));
        return result;
    }

    public CompletableFuture<Void> restoreBackup(com.darkmusic.client.config.BackupArchive.Restore restored, boolean bindings) {
        if (closed || mediaBusy || resetting || outputChanging || !playlistsReady || !listeningHistory.ready())
            return CompletableFuture.failedFuture(new IllegalStateException(com.darkmusic.client.text.Text.get("library.darkmusic.busy")));
        var keys = new java.util.LinkedHashMap<String, String>();
        var priorKeys = new java.util.LinkedHashMap<String, String>();
        if (bindings) for (var key : bindings()) {
            priorKeys.put(key.getName(), key.saveString());
            String value = restored.settings().shortcuts.getOrDefault(key.getName(), key.saveString());
            try {
                InputConstants.getKey(value);
            } catch (RuntimeException invalid) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(com.darkmusic.client.text.Text.get("error.shortcut_invalid")));
            }
            keys.put("key_" + key.getName(), value);
        }
        var contents = new java.util.LinkedHashMap<String, String>();
        if (restored.selection().settings() || bindings)
            contents.put("config.json", ConfigStore.JSON.toJson(restored.settings()));
        if (restored.selection().playlists()) {
            contents.put("playlists.json", ConfigStore.JSON.toJson(restored.playlists()));
            contents.put("playlist-retained.json", ConfigStore.JSON.toJson(restored.retained()));
        }
        if (restored.selection().history())
            contents.put("listening-history.json", com.darkmusic.client.library.ListeningHistory.json(restored.history()));
        mediaBusy = true;
        if (restored.selection().history()) listeningHistory.suspended(true);
        var finished = new CompletableFuture<Void>();
        CompletableFuture.runAsync(() -> {
            var options = config.directory().toAbsolutePath().getParent().getParent().resolve("options.txt");
            String before = null;
            boolean existed = java.nio.file.Files.exists(options), touched = false;
            try {
                if (bindings) {
                    if (java.nio.file.Files.isSymbolicLink(options) || existed && (!java.nio.file.Files.isRegularFile(options) || java.nio.file.Files.size(options) > 16_000_000))
                        throw new java.io.IOException("Unsafe options file");
                    before = existed ? java.nio.file.Files.readString(options) : null;
                    var backups = config.directory().resolve("backups");
                    if (java.nio.file.Files.isSymbolicLink(backups))
                        throw new java.io.IOException("Unsafe backup directory");
                    ConfigStore.atomicWrite(backups.resolve("shortcuts-" + java.util.UUID.randomUUID() + ".json"), ConfigStore.JSON.toJson(priorKeys));
                    touched = true;
                    config.resetShortcuts(keys);
                }
                com.darkmusic.client.config.BackupArchive.install(config.directory(), contents);
            } catch (Exception failure) {
                if (touched) try {
                    if (existed) ConfigStore.atomicWrite(options, before);
                    else java.nio.file.Files.deleteIfExists(options);
                } catch (Exception rollback) {
                    failure.addSuppressed(rollback);
                }
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, io).whenComplete((unused, failure) -> Minecraft.getInstance().execute(() -> {
            try {
                if (failure != null) {
                    finished.completeExceptionally(new java.io.IOException(com.darkmusic.client.text.Text.get("backup.failed"), failure));
                    return;
                }
                if (closed) {
                    finished.complete(null);
                    return;
                }
                if (restored.selection().settings() || bindings) settings = restored.settings();
                if (restored.selection().playlists()) playlists.restore(restored.playlists(), restored.retained());
                if (restored.selection().history()) listeningHistory.replace(restored.history());
                if (bindings) {
                    for (var key : bindings()) key.setKey(InputConstants.getKey(keys.get("key_" + key.getName())));
                    KeyMapping.resetMapping();
                }
                playback.volume(settings.volume);
                playback.speed(settings.speed);
                playback.shuffle(settings.shuffle);
                playback.repeat(settings.repeat);
                configureVisualizer();
                save();
                finished.complete(null);
            } finally {
                mediaBusy = false;
                listeningHistory.suspended(false);
            }
        }));
        return finished;
    }

    public com.darkmusic.client.library.ArtworkCache artwork;
    public com.darkmusic.client.playlist.PlaylistStore playlists;
    public com.darkmusic.client.download.DownloadService downloads;
    public com.darkmusic.client.download.AuditionPlayer audition;
    public com.darkmusic.client.update.UpdateService updates;
    public final com.darkmusic.client.lyrics.LyricsService lyrics = new com.darkmusic.client.lyrics.LyricsService();
    public boolean playlistsReady;
    public boolean mediaBusy;
    public boolean importing;
    public String importDestination = "";
    public com.darkmusic.client.library.ImportHistory importHistory;
    private com.darkmusic.client.download.ProcessRunner.Token mediaToken;

    public void importAudio(java.util.List<java.nio.file.Path> paths, String folder, String playlistId) {
        if (mediaBusy || resetting || !playlistsReady) {
            warn(com.darkmusic.client.text.Text.get("library.darkmusic.busy"));
            return;
        }
        var root = library.root();
        String rootId = library.rootId();
        var store = playlists;
        var scanned = library;
        String target = store.get(playlistId) != null ? store.displayName(store.get(playlistId)) : folder.isBlank() ? com.darkmusic.client.text.Text.get("text.darkmusic.all_tracks") : folder;
        String destination = root.toString() + " | " + target;
        long started = System.currentTimeMillis();
        mediaBusy = true;
        var token = mediaToken = new com.darkmusic.client.download.ProcessRunner.Token();
        var accepted = java.util.List.copyOf(paths);
        if (accepted.size() > 1000) {
            mediaBusy = false;
            warn(com.darkmusic.client.text.Text.get("library.darkmusic.import_limit"));
            return;
        }
        importing = true;
        importDestination = target;
        var task = browse(() -> {
            try {
                var imported = new com.darkmusic.client.library.MediaFiles().importFiles(root, rootId, folder, accepted, token);
                if (importHistory != null)
                    importHistory.record(new com.darkmusic.client.library.ImportHistory.Batch(started, destination, accepted.size(), imported.entries(), ""));
                Minecraft.getInstance().execute(() -> {
                    importing = false;
                    mediaBusy = false;
                    if (closed || resetting || playlists != store) return;
                    if (store.get(playlistId) != null) store.addAll(playlistId, imported.tracks());
                    scanned.requestRescan();
                    warn(com.darkmusic.client.text.Text.get("library.darkmusic.import_result", imported.tracks().size(), imported.errors().size()));
                    if (!imported.errors().isEmpty())
                        org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Audio import: {}", imported.errors());
                });
            } catch (Exception failure) {
                if (importHistory != null)
                    importHistory.record(new com.darkmusic.client.library.ImportHistory.Batch(started, destination, accepted.size(), java.util.List.of(), String.valueOf(failure.getMessage())));
                Minecraft.getInstance().execute(() -> {
                    importing = false;
                    mediaBusy = false;
                    warn(com.darkmusic.client.text.Text.failure(failure, "library.darkmusic.import_failed"));
                });
            }
        });
        if (task == null) importing = mediaBusy = false;
    }

    public CompletableFuture<com.darkmusic.client.library.MediaFiles.Fields> readMetadata(com.darkmusic.client.library.Track track) {
        var result = new CompletableFuture<com.darkmusic.client.library.MediaFiles.Fields>();
        if (browse(() -> {
            try {
                result.complete(new com.darkmusic.client.library.MediaFiles().fields(track));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }) == null)
            result.completeExceptionally(new IllegalStateException(com.darkmusic.client.text.Text.get("library.darkmusic.busy")));
        return result;
    }

    public CompletableFuture<com.darkmusic.client.library.Track> editMetadata(com.darkmusic.client.library.Track track,
                                                                              com.darkmusic.client.library.MediaFiles.Fields original, com.darkmusic.client.library.MediaFiles.Fields fields) {
        if (mediaBusy || resetting)
            return CompletableFuture.failedFuture(new IllegalStateException(com.darkmusic.client.text.Text.get("library.darkmusic.busy")));
        mediaBusy = true;
        var token = mediaToken = new com.darkmusic.client.download.ProcessRunner.Token();
        var player = playback;
        var store = playlists;
        var scanned = library;
        var resume = player.suspendForEdit();
        var result = new CompletableFuture<com.darkmusic.client.library.Track>();
        var task = browse(() -> {
            com.darkmusic.client.library.Track replacement = track;
            Exception problem = null;
            try {
                if (!player.awaitIdle(10))
                    throw new java.io.IOException(com.darkmusic.client.text.Text.get("library.darkmusic.file_busy"));
                replacement = new com.darkmusic.client.library.MediaFiles().edit(track, original, fields, token);
            } catch (Exception e) {
                problem = e;
            }
            var edited = replacement;
            var failure = problem;
            Minecraft.getInstance().execute(() -> {
                mediaBusy = false;
                if (closed || resetting || playback != player) {
                    result.completeExceptionally(new IllegalStateException(com.darkmusic.client.text.Text.get("library.darkmusic.busy")));
                    return;
                }
                if (failure == null) {
                    store.replaceTrack(track, edited);
                    listeningHistory.rename(track, edited);
                    scanned.requestRescan();
                }
                player.finishEdit(resume, track, edited);
                if (failure == null) result.complete(edited);
                else result.completeExceptionally(failure);
            });
        });
        if (task == null) {
            mediaBusy = false;
            player.finishEdit(resume, track, track);
            result.completeExceptionally(new IllegalStateException(com.darkmusic.client.text.Text.get("library.darkmusic.busy")));
        }
        return result;
    }

    private volatile long warningUntil;
    private long conflictWarning;
    private boolean autoplayPending;
    public final com.darkmusic.client.library.SourceLinks sourceLinks = new com.darkmusic.client.library.SourceLinks(browsing);
    private boolean playlistCleanupPending;
    private long cleanedScan = -1, cleanedPlaylists = -1;
    private volatile boolean closed;
    private boolean resetting;
    private long directoryGeneration;
    private Boolean requestedLayout;
    private long requestUntil;

    public void warn(String text) {
        if (!text.equals(warning)) announce(text);
        warning = text;
        warningUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        org.slf4j.LoggerFactory.getLogger("darkmusic").warn(text);
    }

    public void announce(String text) {
        var client = Minecraft.getInstance();
        if (client != null) client.execute(() -> {
            if (!closed && client.getNarrator().isActive())
                client.getNarrator().saySystemQueued(net.minecraft.network.chat.Component.literal(text));
        });
    }

    public KeyMapping[] bindings() {
        return new KeyMapping[]{open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute, seekBack, seekForward, stop, jump};
    }

    public void seekRelative(double seconds) {
        if (!resetting && !mediaBusy && playback != null) playback.seekRelative(seconds);
    }

    public void playbackShortcut(KeyMapping key) {
        if (resetting || mediaBusy || settings == null || playback == null || conflicts(key)) return;
        if (key == toggle) {
            if (playback.state().track() == null && !library.snapshot().tracks().isEmpty())
                playback.play(library.snapshot().tracks(), library.snapshot().tracks().getFirst());
            else playback.toggle();
        } else if (key == next) playback.next();
        else if (key == previous) playback.previous();
        else if (key == faster) shortcutSpeed(settings.speed + .1);
        else if (key == slower) shortcutSpeed(settings.speed - .1);
        else if (key == normalSpeed) shortcutSpeed(1);
        else if (key == volumeUp) shortcutVolume(settings.volume + .05);
        else if (key == volumeDown) shortcutVolume(settings.volume - .05);
        else if (key == mute) toggleMute();
        else if (key == seekBack) seekRelative(-5);
        else if (key == seekForward) seekRelative(5);
        else if (key == stop) playback.stop();
        else if (key == jump) {
            open();
            if (Minecraft.getInstance().gui.screen() instanceof com.darkmusic.client.gui.MusicScreen screen)
                screen.jumpToPlaying();
        }
    }

    public void speed(double value) {
        settings.speed = com.darkmusic.client.audio.PcmRateConverter.clamp(value);
        playback.speed(settings.speed);
        save();
    }

    private void shortcutSpeed(double value) {
        speed(value);
        speedFeedback.show(settings.speed);
    }

    public void volume(double value) {
        settings.volume = Settings.clampVolume(value);
        if (settings.volume > 0) settings.lastAudibleVolume = settings.volume;
        playback.volume(settings.volume);
        save();
    }

    public void toggleMute() {
        volume(settings.volume > 0 ? 0 : settings.lastAudibleVolume);
        volumeFeedback.show(settings.volume);
    }

    private void shortcutVolume(double value) {
        volume(value);
        volumeFeedback.show(settings.volume);
    }

    public void save() {
        if (!closed && !resetting && settings != null) {
            configureVisualizer();
            config.save(settings);
        }
    }

    public void configureVisualizer() {
        if (settings != null) {
            com.darkmusic.client.gui.UiTheme.apply(settings);
            com.darkmusic.client.audio.ExternalAudio.configure(() -> new com.darkmusic.client.audio.ExternalAudio.Paths(config.directory(), settings.ffmpegPath, settings.ffprobePath));
            if (downloads != null) downloads.format(settings.downloadFormat);
            if (downloads != null)
                downloads.lyrics(settings.lyricsEnabled, settings.lyricsLanguages, Minecraft.getInstance().options.languageCode);
            lyrics.enabled(settings.lyricsEnabled);
            if (playback != null) playback.configure(settings.audio, settings.environment);
        }
        if (playback != null && playback.spectrum() != null) {
            playback.spectrum().configure(settings.hudVisualizer || settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.CIRCULAR && settings.circularIconPulse, settings.visualizerBars, settings.visualizerSensitivity, settings.visualizerSmoothing);
            playback.spectrum().refine(settings.visualQuality);
        }
    }

    public void open() {
        var client = Minecraft.getInstance();
        var screen = client.gui.screen();
        if (!closed && !resetting && settings != null && library != null && client.gui.overlay() == null && client.gui.canInterruptScreen()
            && (screen == null || screen instanceof TitleScreen) && (client.player == null || client.player.isAlive()))
            client.gui.setScreen(new MusicScreen(this, screen));
    }

    public void requestScreen(boolean layout) {
        requestedLayout = layout;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    private void openRequestedScreen(Minecraft client) {
        if (requestedLayout == null) return;
        if (closed || resetting || System.nanoTime() > requestUntil || client.player != null && !client.player.isAlive()) {
            requestedLayout = null;
            return;
        }
        var screen = client.gui.screen();
        if (settings == null || library == null || client.gui.overlay() != null || !client.gui.canInterruptScreen()
            || screen != null && !(screen instanceof TitleScreen)) return;
        boolean layout = requestedLayout;
        requestedLayout = null;
        if (layout) client.gui.setScreen(new com.darkmusic.client.gui.HudEditorScreen(this, screen));
        else open();
    }

    private KeyMapping binding(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkmusic." + name, InputConstants.Type.KEYSYM, key, category));
    }

    public boolean conflicts(KeyMapping key) {
        if (key.isUnbound()) return false;
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings)
            if (other != key && key.same(other)) {
                if (Minecraft.getInstance().gui.screen() instanceof com.darkmusic.client.gui.MusicScreen
                    && !other.getName().startsWith("key.darkmusic.")) continue;
                if (System.nanoTime() - conflictWarning > TimeUnit.SECONDS.toNanos(2)) {
                    conflictWarning = System.nanoTime();
                    warn(com.darkmusic.client.text.Text.get("error.shortcut_conflict"));
                }
                return true;
            }
        return false;
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        updates = new com.darkmusic.client.update.UpdateService(this);
        com.darkmusic.client.command.LocalCommands.register(this);
        com.darkmusic.client.hud.NowPlayingHud.register(this);
        com.darkmusic.client.hud.VolumeFeedback.register(this);
        com.darkmusic.client.hud.SpeedFeedback.register(this);
        com.darkmusic.client.hud.DownloadHud.register(this);
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkmusic", "controls"));
        open = binding("open", org.lwjgl.glfw.GLFW.GLFW_KEY_B, category);
        toggle = binding("toggle", org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH, category);
        next = binding("next", org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET, category);
        previous = binding("previous", org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET, category);
        faster = binding("faster", org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, category);
        slower = binding("slower", org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, category);
        seekBack = binding("seek_back", org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, category);
        seekForward = binding("seek_forward", org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, category);
        stop = binding("stop", org.lwjgl.glfw.GLFW.GLFW_KEY_P, category);
        jump = binding("jump", org.lwjgl.glfw.GLFW.GLFW_KEY_O, category);
        normalSpeed = binding("normal_speed", org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0, category);
        volumeUp = binding("volume_up", org.lwjgl.glfw.GLFW.GLFW_KEY_UP, category);
        volumeDown = binding("volume_down", org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, category);
        mute = binding("mute", org.lwjgl.glfw.GLFW.GLFW_KEY_M, category);
        CompletableFuture.supplyAsync(config::load, io).thenAccept(loaded -> Minecraft.getInstance().execute(() -> {
            if (!closed) {
                settings = loaded;
                ready();
                save();
            }
        }));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen)
                Screens.getWidgets(screen).add(Button.builder(com.darkmusic.client.text.Text.component("text.darkmusic.music"), b -> open()).bounds(6, 6, 64, 20).build());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            updates.tick(client);
            boolean allowed = !resetting && settings != null && client.gui.screen() == null && client.gui.overlay() == null && client.player != null && client.player.isAlive();
            boolean pressed = false;
            while (open.consumeClick()) pressed = true;
            if (allowed && pressed && !conflicts(open)) open();
            tick(client, allowed);
            openRequestedScreen(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            updates.close();
            save();
            closed = true;
            shutdown();
            io.shutdown();
            try {
                if (!io.awaitTermination(2, TimeUnit.SECONDS)) io.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                io.shutdownNow();
            }
        });
    }

    protected void ready() {
        listeningHistory = new com.darkmusic.client.library.ListeningHistory(config.directory(), io, this::warn);
        io.execute(listeningHistory::load);
        importHistory = new com.darkmusic.client.library.ImportHistory(config.directory(), io, this::warn);
        for (KeyMapping key : bindings()) {
            String saved = settings.nativeShortcutNames.contains(key.getName()) ? null : settings.shortcuts.get(key.getName());
            if (saved != null) try {
                key.setKey(InputConstants.getKey(saved));
            } catch (IllegalArgumentException e) {
                key.setKey(InputConstants.UNKNOWN);
                warn(com.darkmusic.client.text.Text.get("error.shortcut_invalid"));
            }
        }
        migrateSpeedBindings();
        if (settings.nativeShortcutNames.containsAll(java.util.Arrays.stream(bindings()).map(KeyMapping::getName).toList()))
            settings.shortcuts.clear();
        KeyMapping.resetMapping();
        playlists = new com.darkmusic.client.playlist.PlaylistStore(config.directory(), io, this::warn);
        CompletableFuture.supplyAsync(playlists::read, io).thenAccept(list -> Minecraft.getInstance().execute(() -> {
            if (!closed) {
                playlists.loaded(list);
                playlistsReady = true;
            }
        }));
        artwork = new com.darkmusic.client.library.ArtworkCache();
        playback = createPlayback();
        playback.volume(settings.volume);
        configureVisualizer();
        playback.speed(settings.speed);
        playback.shuffle(settings.shuffle);
        playback.repeat(settings.repeat);
        autoplayPending = settings.autoplay;
        library = new com.darkmusic.client.library.MusicLibrary(config.directory());
        downloads = new com.darkmusic.client.download.DownloadService(config.directory(), () -> library.requestRescan());
        downloads.configure(new com.darkmusic.client.download.DownloadModels.Tools(settings.ytDlpPath, settings.ffmpegPath, settings.ffprobePath, settings.javascriptPath));
        downloads.format(settings.downloadFormat);
        downloads.lyrics(settings.lyricsEnabled, settings.lyricsLanguages, Minecraft.getInstance().options.languageCode);
        try {
            activateDirectory(settings.musicDirectory.isBlank() ? config.defaultMusic() : java.nio.file.Path.of(settings.musicDirectory));
        } catch (Exception e) {
            warn(com.darkmusic.client.text.Text.failure(e, "error.saved_folder"));
            activateDirectory(config.defaultMusic());
        }
    }

    private void migrateSpeedBindings() {
        if (settings.nativeShortcutNames.contains(seekBack.getName()) || settings.nativeShortcutNames.contains(seekForward.getName())
            || settings.shortcuts.containsKey(seekBack.getName()) || settings.shortcuts.containsKey(seekForward.getName())
            || !faster.saveString().equals("key.keyboard.right") || !slower.saveString().equals("key.keyboard.left"))
            return;
        faster.setKey(faster.getDefaultKey());
        slower.setKey(slower.getDefaultKey());
        settings.shortcuts.put(faster.getName(), faster.saveString());
        settings.shortcuts.put(slower.getName(), slower.saveString());
        var updated = java.util.Map.of("key_" + faster.getName(), faster.saveString(), "key_" + slower.getName(), slower.saveString());
        io.execute(() -> {
            try {
                config.resetShortcuts(updated);
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Cannot migrate default speed bindings", failure);
                Minecraft.getInstance().execute(() -> warn(com.darkmusic.client.text.Text.get("error.shortcut_migrate")));
            }
        });
    }

    private void activateDirectory(java.nio.file.Path path) {
        String normalized = path.toAbsolutePath().normalize().toString();
        String id = settings.roots.computeIfAbsent(normalized, key -> java.util.UUID.randomUUID().toString());
        library.setRoot(java.nio.file.Path.of(normalized), id);
        save();
    }

    public java.util.concurrent.Future<?> browse(Runnable task) {
        try {
            ((java.util.concurrent.ThreadPoolExecutor) browsing).purge();
            return browsing.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            if (!closed) warn(com.darkmusic.client.text.Text.get("error.folder_busy"));
            return null;
        }
    }

    public void chooseDirectory(java.nio.file.Path directory, Runnable success) {
        if (mediaBusy) {
            warn(com.darkmusic.client.text.Text.get("library.darkmusic.busy"));
            return;
        }
        long generation = directoryGeneration;
        browse(() -> {
            try {
                if (!java.nio.file.Files.isDirectory(directory) || !java.nio.file.Files.isReadable(directory))
                    throw new java.io.IOException("Directory unavailable or unreadable");
                java.nio.file.Path canonical = directory.toRealPath();
                Minecraft.getInstance().execute(() -> {
                    if (!closed && !resetting && !mediaBusy && generation == directoryGeneration) {
                        settings.musicDirectory = canonical.toString();
                        activateDirectory(canonical);
                        success.run();
                    }
                });
            } catch (Exception e) {
                warn(com.darkmusic.client.text.Text.failure(e, "error.folder"));
            }
        });
    }

    public void resetMusicFolder() {
        browse(() -> {
            try {
                java.nio.file.Files.createDirectories(config.defaultMusic());
                Minecraft.getInstance().execute(() -> {
                    if (closed || resetting) return;
                    chooseDirectory(config.defaultMusic(), () -> {
                        settings.musicDirectory = "";
                        save();
                    });
                });
            } catch (Exception e) {
                warn(com.darkmusic.client.text.Text.failure(e, "error.folder"));
            }
        });
    }

    private boolean cleanMissingPlaylistEntries() {
        if (mediaBusy || !playlistsReady || library == null || playlistCleanupPending) return false;
        var snapshot = library.snapshot();
        if (snapshot.scanning() || !snapshot.complete()) return false;
        if (cleanedScan == snapshot.revision() && cleanedPlaylists == playlists.revision()) return true;
        var store = playlists;
        var entries = store.all();
        long revision = store.revision();
        var root = library.root();
        String rootId = library.rootId();
        playlistCleanupPending = true;
        io.execute(() -> {
            java.util.Set<com.darkmusic.client.playlist.PlaylistStore.Entry> missing;
            try {
                missing = com.darkmusic.client.playlist.PlaylistStore.missing(root, rootId, entries, snapshot.tracks());
            } catch (Exception e) {
                missing = java.util.Set.of();
                warn(com.darkmusic.client.text.Text.failure(e, "error.playlist_scan"));
            }
            var result = missing;
            Minecraft.getInstance().execute(() -> {
                playlistCleanupPending = false;
                if (closed || resetting || mediaBusy || playlists != store || store.revision() != revision
                    || library.snapshot().revision() != snapshot.revision() || !rootId.equals(library.rootId())) return;
                store.removeMissing(result);
                store.acknowledgeTracks(snapshot.tracks());
                cleanedScan = snapshot.revision();
                cleanedPlaylists = store.revision();
            });
        });
        return false;
    }

    public void openFolder() {
        java.nio.file.Path directory = library.root();
        browse(() -> {
            try {
                if (!java.nio.file.Files.isDirectory(directory))
                    throw new java.io.IOException("The configured music directory is unavailable");
                net.minecraft.util.Util.getPlatform().openPath(directory);
            } catch (Exception e) {
                warn(com.darkmusic.client.text.Text.failure(e, "error.folder"));
            }
        });
    }

    protected void tick(Minecraft client, boolean allowed) {
        allowed &= !mediaBusy;
        if (!warning.isEmpty() && System.nanoTime() >= warningUntil) warning = "";
        while (toggle.consumeClick()) if (allowed) playbackShortcut(toggle);
        while (next.consumeClick()) if (allowed) playbackShortcut(next);
        while (previous.consumeClick()) if (allowed) playbackShortcut(previous);
        while (faster.consumeClick()) if (allowed) playbackShortcut(faster);
        while (slower.consumeClick()) if (allowed) playbackShortcut(slower);
        while (normalSpeed.consumeClick()) if (allowed) playbackShortcut(normalSpeed);
        while (volumeUp.consumeClick()) if (allowed) playbackShortcut(volumeUp);
        while (volumeDown.consumeClick()) if (allowed) playbackShortcut(volumeDown);
        while (mute.consumeClick()) if (allowed) playbackShortcut(mute);
        while (seekBack.consumeClick()) if (allowed) playbackShortcut(seekBack);
        while (seekForward.consumeClick()) if (allowed) playbackShortcut(seekForward);
        while (stop.consumeClick()) if (allowed) playbackShortcut(stop);
        while (jump.consumeClick()) if (allowed) playbackShortcut(jump);
        if (resetting || settings == null || playback == null) return;
        if (listeningHistory != null && !mediaBusy) listeningHistory.flush(false);
        boolean playlistsClean = cleanMissingPlaylistEntries();
        if (autoplayPending && !mediaBusy && playlistsClean) {
            autoplayPending = false;
            if (!library.snapshot().tracks().isEmpty())
                playback.play(library.snapshot().tracks(), library.snapshot().tracks().getFirst());
        }
    }

    /**
     * Called on the client thread only, after explicit confirmation.
     */
    public CompletableFuture<Void> resetDefaults() {
        if (closed || resetting || mediaBusy)
            return CompletableFuture.failedFuture(new IllegalStateException(com.darkmusic.client.text.Text.get("reset.darkmusic.busy")));
        resetting = true;
        requestedLayout = null;
        directoryGeneration++;
        autoplayPending = false;
        com.darkmusic.client.gui.DesktopFolderPicker.cancel();
        var prior = settings;
        var defaultBindings = new java.util.LinkedHashMap<String, String>();
        for (KeyMapping key : bindings()) defaultBindings.put("key_" + key.getName(), key.getDefaultKey().getName());
        var roots = new java.util.LinkedHashSet<java.nio.file.Path>();
        roots.add(library.root());
        for (String root : prior.roots.keySet())
            try {
                roots.add(java.nio.file.Path.of(root));
            } catch (java.nio.file.InvalidPathException ignored) {
            }
        playback.reset();
        speedFeedback.clear();
        volumeFeedback.clear();
        artwork.close();
        var oldPlayback = playback;
        var oldLibrary = library;
        var oldDownloads = downloads;
        var oldAudition = audition;
        audition = null;
        var finished = new CompletableFuture<Void>();
        CompletableFuture.supplyAsync(() -> {
            try {
                if (oldAudition != null) oldAudition.close();
                oldPlayback.close();
                oldDownloads.close();
                oldLibrary.close();
                if (!oldPlayback.awaitStopped(15) || !oldDownloads.awaitStopped(15) || !oldLibrary.awaitStopped(15)
                    || oldAudition != null && !oldAudition.awaitStopped(15))
                    throw new java.io.IOException(com.darkmusic.client.text.Text.get("reset.darkmusic.waiting"));
                var retained = com.darkmusic.client.config.ResetStorage.clear(config.directory(), roots);
                config.resetShortcuts(defaultBindings);
                return retained;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, io).whenComplete((retained, failure) -> Minecraft.getInstance().execute(() -> {
            if (closed) {
                finished.completeExceptionally(new IllegalStateException(com.darkmusic.client.text.Text.get("reset.darkmusic.closing")));
                return;
            }
            settings = failure == null ? Settings.defaults(prior) : prior;
            playback = createPlayback();
            artwork = new com.darkmusic.client.library.ArtworkCache();
            library = new com.darkmusic.client.library.MusicLibrary(config.directory());
            downloads = new com.darkmusic.client.download.DownloadService(config.directory(), () -> library.requestRescan());
            downloads.configure(new com.darkmusic.client.download.DownloadModels.Tools(settings.ytDlpPath, settings.ffmpegPath, settings.ffprobePath, settings.javascriptPath));
            resetting = false;
            playback.volume(settings.volume);
            playback.speed(settings.speed);
            playback.shuffle(settings.shuffle);
            playback.repeat(settings.repeat);
            configureVisualizer();
            if (failure == null) {
                warning = "";
                warningUntil = 0;
                conflictWarning = 0;
                for (KeyMapping key : bindings()) {
                    key.setKey(key.getDefaultKey());
                    key.setDown(false);
                    while (key.consumeClick()) {
                    }
                }
                KeyMapping.resetMapping();
            }
            try {
                activateDirectory(settings.musicDirectory.isBlank() ? config.defaultMusic() : java.nio.file.Path.of(settings.musicDirectory));
            } catch (Exception e) {
                activateDirectory(config.defaultMusic());
            }
            save();
            if (failure != null) finished.completeExceptionally(failure);
            else if (!retained.isEmpty()) {
                String message = com.darkmusic.client.text.Text.get("reset.darkmusic.retained");
                warn(message);
                finished.completeExceptionally(new java.io.IOException(message));
            } else {
                String expected = ConfigStore.JSON.toJson(settings);
                CompletableFuture.runAsync(() -> {
                    try {
                        if (!java.nio.file.Files.readString(config.directory().resolve("config.json")).equals(expected))
                            throw new java.io.IOException(com.darkmusic.client.text.Text.get("reset.darkmusic.save_failed"));
                    } catch (java.io.IOException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, io).whenComplete((unused, error) -> {
                    if (error == null) finished.complete(null);
                    else finished.completeExceptionally(error);
                });
            }
        }));
        return finished;
    }

    protected void shutdown() {
        com.darkmusic.client.gui.DesktopFolderPicker.cancel();
        if (mediaToken != null) mediaToken.cancel();
        browsing.shutdownNow();
        if (audition != null) audition.close();
        if (downloads != null) downloads.close();
        if (library != null) library.close();
        if (playback != null) playback.close();
        if (listeningHistory != null) listeningHistory.flush(true);
        if (artwork != null) artwork.close();
        lyrics.close();
    }
}
