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
import net.minecraft.network.chat.Component;
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
    public volatile Settings settings;
    public volatile String warning = "";
    public KeyMapping open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown;
    public com.darkmusic.client.library.MusicLibrary library;
    public com.darkmusic.client.audio.PlaybackController playback;
    public com.darkmusic.client.library.ArtworkCache artwork;
    public com.darkmusic.client.playlist.PlaylistStore playlists;
    public com.darkmusic.client.download.DownloadService downloads;
    public boolean playlistsReady;
    private volatile long warningUntil;
    private long conflictWarning;
    private boolean autoplayPending;
    private volatile boolean closed;

    public void warn(String text) {
        warning = text;
        warningUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        org.slf4j.LoggerFactory.getLogger("darkmusic").warn(text);
    }

    public KeyMapping[] bindings() {
        return new KeyMapping[]{open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown};
    }

    public void speed(double value) {
        settings.speed = com.darkmusic.client.audio.PcmRateConverter.clamp(value);
        playback.speed(settings.speed);
        save();
    }

    public void volume(double value) {
        settings.volume = Settings.clampVolume(value);
        playback.volume(settings.volume);
        save();
    }

    public void save() {
        if (!closed && settings != null) {
            configureVisualizer();
            config.save(settings);
        }
    }

    public void configureVisualizer() {
        if (playback != null && playback.spectrum() != null)
            playback.spectrum().configure(settings.playerVisualizer || settings.hudVisualizer, settings.visualizerBars, settings.visualizerSensitivity, settings.visualizerSmoothing);
    }

    public void open() {
        var client = Minecraft.getInstance();
        var screen = client.gui.screen();
        if (!closed && settings != null && library != null && client.gui.overlay() == null && client.gui.canInterruptScreen()
            && (screen == null || screen instanceof TitleScreen) && (client.player == null || client.player.isAlive()))
            client.gui.setScreen(new MusicScreen(this, screen));
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
        com.darkmusic.client.hud.NowPlayingHud.register(this);
        com.darkmusic.client.hud.SpectrumHud.register(this);
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkmusic", "controls"));
        open = binding("open", 77, category);
        toggle = binding("toggle", -1, category);
        next = binding("next", org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, category);
        previous = binding("previous", org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, category);
        faster = binding("faster", -1, category);
        slower = binding("slower", -1, category);
        normalSpeed = binding("normal_speed", -1, category);
        volumeUp = binding("volume_up", org.lwjgl.glfw.GLFW.GLFW_KEY_UP, category);
        volumeDown = binding("volume_down", org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, category);
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
            boolean allowed = settings != null && client.gui.screen() == null && client.gui.overlay() == null && client.player != null && client.player.isAlive();
            boolean pressed = false;
            while (open.consumeClick()) pressed = true;
            if (allowed && pressed && !conflicts(open)) open();
            tick(client, allowed);
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
            warn("Invalid saved directory; select another folder. " + e.getMessage());
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
            if (!closed) warn("Folder browser is busy. Wait for pending filesystem requests before trying again.");
            return null;
        }
    }

    public void chooseDirectory(java.nio.file.Path directory, Runnable success) {
        browse(() -> {
            try {
                if (!java.nio.file.Files.isDirectory(directory) || !java.nio.file.Files.isReadable(directory))
                    throw new java.io.IOException("Directory unavailable or unreadable");
                java.nio.file.Path canonical = directory.toRealPath();
                Minecraft.getInstance().execute(() -> {
                    if (!closed) {
                        settings.musicDirectory = canonical.toString();
                        activateDirectory(canonical);
                        success.run();
                    }
                });
            } catch (Exception e) {
                warn("Cannot select directory: " + e.getMessage());
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
        while (faster.consumeClick()) if (allowed && playback != null && !conflicts(faster)) speed(settings.speed + .1);
        while (slower.consumeClick()) if (allowed && playback != null && !conflicts(slower)) speed(settings.speed - .1);
        while (normalSpeed.consumeClick()) if (allowed && playback != null && !conflicts(normalSpeed)) speed(1);
        while (volumeUp.consumeClick())
            if (allowed && playback != null && !conflicts(volumeUp)) volume(settings.volume + .05);
        while (volumeDown.consumeClick())
            if (allowed && playback != null && !conflicts(volumeDown)) volume(settings.volume - .05);
        if (settings == null || playback == null) return;
        if (autoplayPending && !library.snapshot().scanning()) {
            autoplayPending = false;
            if (!library.snapshot().tracks().isEmpty())
                playback.play(library.snapshot().tracks(), library.snapshot().tracks().getFirst());
        }
    }

    protected void shutdown() {
        browsing.shutdownNow();
        if (downloads != null) downloads.close();
        if (library != null) library.close();
        if (playback != null) playback.close();
        if (artwork != null) artwork.close();
    }
}
