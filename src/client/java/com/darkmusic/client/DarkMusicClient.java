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
    public KeyMapping open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute;
    public com.darkmusic.client.library.MusicLibrary library;
    public com.darkmusic.client.audio.PlaybackController playback;
    public com.darkmusic.client.library.ArtworkCache artwork;
    public com.darkmusic.client.playlist.PlaylistStore playlists;
    public com.darkmusic.client.download.DownloadService downloads;
    public com.darkmusic.client.download.AuditionPlayer audition;
    public boolean playlistsReady;
    private volatile long warningUntil;
    private long conflictWarning;
    private boolean autoplayPending;
    private volatile boolean closed;
    private boolean resetting;
    private long directoryGeneration;
    private Boolean requestedLayout;
    private long requestUntil;

    public void warn(String text) {
        warning = text;
        warningUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        org.slf4j.LoggerFactory.getLogger("darkmusic").warn(text);
    }

    public KeyMapping[] bindings() {
        return new KeyMapping[]{open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute};
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
        if (playback != null && playback.spectrum() != null)
            playback.spectrum().configure(settings.hudVisualizer, settings.visualizerBars, settings.visualizerSensitivity, settings.visualizerSmoothing);
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
                if (System.nanoTime() - conflictWarning > TimeUnit.SECONDS.toNanos(2)) {
                    conflictWarning = System.nanoTime();
                    warn("Shortcut conflict. Change the overlapping bindings in Options > Controls > Key Binds.");
                }
                return true;
            }
        return false;
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
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
        faster = binding("faster", org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, category);
        slower = binding("slower", org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, category);
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
            boolean allowed = !resetting && settings != null && client.gui.screen() == null && client.gui.overlay() == null && client.player != null && client.player.isAlive();
            boolean pressed = false;
            while (open.consumeClick()) pressed = true;
            if (allowed && pressed && !conflicts(open)) open();
            tick(client, allowed);
            openRequestedScreen(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
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
        for (KeyMapping key : bindings()) {
            String saved = settings.nativeShortcutNames.contains(key.getName()) ? null : settings.shortcuts.get(key.getName());
            if (saved != null) try {
                key.setKey(InputConstants.getKey(saved));
            } catch (IllegalArgumentException e) {
                key.setKey(InputConstants.UNKNOWN);
                warn("Invalid shortcut was unbound: " + key.getName());
            }
        }
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
        if (settings.suppressVanillaMusic) Minecraft.getInstance().getMusicManager().stopPlaying();
        artwork = new com.darkmusic.client.library.ArtworkCache();
        playback = new com.darkmusic.client.audio.PlaybackController(new com.darkmusic.client.audio.JavaSoundBackend(Minecraft.getInstance()::execute), this::warn);
        playback.volume(settings.volume);
        configureVisualizer();
        playback.speed(settings.speed);
        playback.shuffle(settings.shuffle);
        playback.repeat(settings.repeat);
        autoplayPending = settings.autoplay;
        library = new com.darkmusic.client.library.MusicLibrary(config.directory());
        downloads = new com.darkmusic.client.download.DownloadService(config.directory(), () -> library.requestRescan());
        downloads.configure(new com.darkmusic.client.download.DownloadModels.Tools(settings.ytDlpPath, settings.ffmpegPath, settings.ffprobePath, settings.javascriptPath));
        try {
            activateDirectory(settings.musicDirectory.isBlank() ? config.defaultMusic() : java.nio.file.Path.of(settings.musicDirectory));
        } catch (Exception e) {
            warn("The saved music folder is invalid; choose another folder. " + e.getMessage());
            activateDirectory(config.defaultMusic());
        }
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
            if (!closed) warn("A folder operation is running. Wait for it to finish and try again.");
            return null;
        }
    }

    public void chooseDirectory(java.nio.file.Path directory, Runnable success) {
        long generation = directoryGeneration;
        browse(() -> {
            try {
                if (!java.nio.file.Files.isDirectory(directory) || !java.nio.file.Files.isReadable(directory))
                    throw new java.io.IOException("Directory unavailable or unreadable");
                java.nio.file.Path canonical = directory.toRealPath();
                Minecraft.getInstance().execute(() -> {
                    if (!closed && !resetting && generation == directoryGeneration) {
                        settings.musicDirectory = canonical.toString();
                        activateDirectory(canonical);
                        success.run();
                    }
                });
            } catch (Exception e) {
                warn("Cannot choose music folder: " + e.getMessage());
            }
        });
    }

    public void openFolder() {
        java.nio.file.Path directory = library.root();
        browse(() -> {
            try {
                if (!java.nio.file.Files.isDirectory(directory))
                    throw new java.io.IOException("The configured music directory is unavailable");
                net.minecraft.util.Util.getPlatform().openPath(directory);
            } catch (Exception e) {
                warn("Cannot open music folder: " + e.getMessage());
            }
        });
    }

    protected void tick(Minecraft client, boolean allowed) {
        if (!warning.isEmpty() && System.nanoTime() >= warningUntil) warning = "";
        while (toggle.consumeClick()) if (allowed && playback != null && !conflicts(toggle)) {
            if (playback.state().track() == null && !library.snapshot().tracks().isEmpty())
                playback.play(library.snapshot().tracks(), library.snapshot().tracks().getFirst());
            else playback.toggle();
        }
        while (next.consumeClick()) if (allowed && playback != null && !conflicts(next)) playback.next();
        while (previous.consumeClick()) if (allowed && playback != null && !conflicts(previous)) playback.previous();
        while (faster.consumeClick())
            if (allowed && playback != null && !conflicts(faster)) shortcutSpeed(settings.speed + .1);
        while (slower.consumeClick())
            if (allowed && playback != null && !conflicts(slower)) shortcutSpeed(settings.speed - .1);
        while (normalSpeed.consumeClick()) if (allowed && playback != null && !conflicts(normalSpeed)) shortcutSpeed(1);
        while (volumeUp.consumeClick())
            if (allowed && playback != null && !conflicts(volumeUp)) shortcutVolume(settings.volume + .05);
        while (volumeDown.consumeClick())
            if (allowed && playback != null && !conflicts(volumeDown)) shortcutVolume(settings.volume - .05);
        while (mute.consumeClick())
            if (allowed && playback != null && !conflicts(mute)) toggleMute();
        if (resetting || settings == null || playback == null) return;
        if (autoplayPending && !library.snapshot().scanning()) {
            autoplayPending = false;
            if (!library.snapshot().tracks().isEmpty())
                playback.play(library.snapshot().tracks(), library.snapshot().tracks().getFirst());
        }
    }

    /**
     * Called on the client thread only, after explicit confirmation.
     */
    public CompletableFuture<Void> resetDefaults() {
        if (closed || resetting)
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
                if (!oldDownloads.awaitStopped(15) || !oldLibrary.awaitStopped(15)
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
            playback = new com.darkmusic.client.audio.PlaybackController(new com.darkmusic.client.audio.JavaSoundBackend(Minecraft.getInstance()::execute), this::warn);
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
        browsing.shutdownNow();
        if (audition != null) audition.close();
        if (downloads != null) downloads.close();
        if (library != null) library.close();
        if (playback != null) playback.close();
        if (artwork != null) artwork.close();
    }
}
