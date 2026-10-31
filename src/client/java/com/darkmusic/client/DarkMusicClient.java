package com.darkmusic.client;

import com.darkmusic.client.command.LocalCommands;
import com.darkmusic.client.config.RestoreJournal;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.DesktopFolderPicker;
import com.darkmusic.client.gui.Help;
import com.darkmusic.client.gui.LocalScreen;
import com.darkmusic.client.gui.RowList;
import com.darkmusic.client.hud.DownloadHud;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.hud.SongCard;
import com.darkmusic.client.pip.PipController;
import com.darkmusic.client.presence.DiscordPresence;
import com.darkmusic.client.systemmedia.MediaControls;
import com.darkmusic.client.text.Text;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Fabric entry point and the client's lifecycle: initialization, startup once the config has loaded, the client
 * tick and shutdown. It wires the services to the collaborators that act on them; screens receive
 * {@link ClientSources}, never this class.
 */
public class DarkMusicClient implements ClientModInitializer {
    public static DarkMusicClient INSTANCE;
    private final ClientServices services = new ClientServices(this::mediaAction);
    private final PlaybackCommands commands = new PlaybackCommands(services);
    private final ScreenRequests screens = new ScreenRequests(services);
    private final Shortcuts shortcuts = new Shortcuts(services, commands, this::open);
    private final FolderActions folders = new FolderActions(services);
    private final MediaActions media = new MediaActions(services, this::cancelAutoplay);
    private final ClientReset reset = new ClientReset(services, screens, folders, shortcuts, this::cancelAutoplay);
    private final ClientSources sources = new ClientSources(services, commands, new PlaylistActions(services), folders, media,
        new BackupActions(services, folders, shortcuts), new OutputActions(services), shortcuts, reset);
    // Set once startup completes, played by the tick when the library has settled; reset and deletions cancel it.
    private boolean autoplayPending;

    public ClientServices services() {
        return services;
    }

    public ClientSources sources() {
        return sources;
    }

    public PlaybackCommands commands() {
        return commands;
    }

    public ScreenRequests screens() {
        return screens;
    }

    public Shortcuts shortcuts() {
        return shortcuts;
    }

    public void open() {
        screens.open(sources);
    }

    private void mediaAction(MediaControls.Action action) {
        commands.mediaAction(action);
    }

    private void cancelAutoplay() {
        autoplayPending = false;
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LocalScreen.saveOnClose(services::save);
        Help.settings(services::settings);
        RowList.warnings(services::warn);
        services.createUpdates();
        LocalCommands.register(new ClientActions(services, commands, screens, shortcuts));
        var hud = services.hud();
        NowPlayingHud.register(hud);
        LyricsHud.register(hud);
        SongCard.register(hud);
        services.volumeFeedback().register(hud);
        services.speedFeedback().register(hud);
        DownloadHud.register(hud);
        PipController.register(services.pipSources());
        shortcuts.register();
        CompletableFuture.supplyAsync(services.config()::load, services.io()).thenAccept(loaded -> Minecraft.getInstance().execute(() -> {
            if (!services.state.closed) start(loaded);
        })).exceptionally(failure -> {
            LoggerFactory.getLogger("darkmusic").error("Could not recover or load user data", failure);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause instanceof RestoreJournal.RecoveryConflict
                ? cause.getMessage() : Text.get("backup.recovery_failed");
            Minecraft.getInstance().execute(() -> services.warn(message));
            return null;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen)
                Screens.getWidgets(screen).add(Button.builder(Text.component("text.darkmusic.music"), b -> open()).bounds(6, 6, 64, 20).build());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var state = services.state;
            var playback = services.playback();
            if (services.pip() != null) services.pip().tick();
            services.updates().tick(client);
            var current = services.settings();
            if (current != null && playback != null && !state.resetting) {
                var playing = playback.state().track();
                // Source links resolve in the background; the status gains the artwork once the link is known.
                String artwork = current.discordStatus && current.discordShowTitle && current.discordArtwork && playing != null
                    ? DiscordPresence.artwork(services.sourceLinks().get(playing)) : "";
                services.discord().update(current.discordStatus, current.discordShowTitle, playback.state(), current.speed, artwork);
            } else services.discord().update(false, true, null, 1, "");
            boolean media = current != null && current.mediaControls && playback != null && !state.resetting;
            services.mediaControls().update(media ? services.mediaPackage().executable() : null, media ? playback.state() : null, System.nanoTime());
            boolean allowed = !state.resetting && services.settings() != null && client.gui.screen() == null && client.gui.overlay() == null && client.player != null && client.player.isAlive();
            boolean pressed = false;
            while (shortcuts.open.consumeClick()) pressed = true;
            if (allowed && pressed && !shortcuts.conflicts(shortcuts.open)) open();
            tick(client, allowed);
            screens.openRequested(client, sources);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
    }

    /**
     * Publishes the loaded settings and builds the services. If building fails part way, what was built is closed
     * and the settings are withdrawn, so the client stays in its not-yet-loaded state, which every screen, HUD and
     * shortcut already handles, instead of running against a partial set. The error is logged and announced.
     */
    private void start(Settings loaded) {
        services.replaceSettings(loaded);
        Startup.run(new Startup.Steps() {
            @Override
            public void build(Cleanup acquired) {
                ready(acquired);
            }

            @Override
            public void withdraw() {
                services.withdraw();
            }

            @Override
            public void failed(Throwable failure) {
                LoggerFactory.getLogger("darkmusic").error("DarkMusic could not start", failure);
                services.warn(Text.get("error.startup"));
            }

            @Override
            public void save() {
                services.save();
            }
        });
    }

    /**
     * Builds the services in dependency order. Each one is registered with acquired as soon as it exists, so a
     * failure further on can close it.
     */
    private void ready(Cleanup acquired) {
        services.buildHistories();
        shortcuts.loadSaved();
        services.buildServices(acquired);
        var settings = services.settings();
        autoplayPending = settings.autoplay;
        var config = services.config();
        try {
            folders.activate(settings.musicDirectory.isBlank() ? config.defaultMusic() : Path.of(settings.musicDirectory));
        } catch (Exception e) {
            services.warn(Text.failure(e, "error.saved_folder"));
            settings.musicDirectory = "";
            folders.activate(config.defaultMusic());
        }
    }

    protected void tick(Minecraft client, boolean allowed) {
        var state = services.state;
        var settings = services.settings();
        var playback = services.playback();
        var library = services.library();
        var waveforms = services.waveforms();
        var lyrics = services.lyrics();
        if (waveforms != null && settings != null && playback != null) {
            var waveformTrack = playback.state().track();
            if (waveformTrack == null && client.gui.screen() instanceof HudPreview preview && preview.previewsProgress() && library != null) {
                var tracks = library.snapshot().tracks();
                if (!tracks.isEmpty()) waveformTrack = tracks.getFirst();
            }
            waveforms.update(waveformTrack, settings.waveformProgress && !state.mediaBusy);
        }
        if (settings != null && playback != null && settings.lyricsEnabled) {
            var track = playback.state().track();
            lyrics.request(track, settings.lyricsAutomaticLookup);
            if (library != null) {
                var snapshot = library.snapshot();
                if (snapshot.scanning() || state.mediaBusy) lyrics.lookup.tickRetries();
                else
                    lyrics.background(snapshot.tracks(), snapshot.completeRoots(), settings.lyricsOnline && lyrics.libraryLookupRunning());
            }
        }
        allowed &= !state.mediaBusy;
        services.warnings().tick();
        shortcuts.consume(allowed);
        if (state.resetting || services.settings() == null || services.playback() == null) return;
        boolean playlistsClean = services.reconciliation().tick();
        if (services.listeningHistory() != null && !state.mediaBusy) services.listeningHistory().flush(false);
        if (autoplayPending && !state.mediaBusy && playlistsClean && !services.library().snapshot().scanning()) {
            autoplayPending = false;
            commands.startPlayback();
        }
        if (!autoplayPending && !state.mediaBusy) commands.recordResume(true);
        services.sleep().tick(services.playback(), services.settings().volume);
    }

    /**
     * Minecraft is closing; {@link Shutdown} owns the order. A second call does nothing.
     */
    protected void shutdown() {
        var state = services.state;
        if (state.closed) return;
        Shutdown.run(new Shutdown.Steps() {
            @Override
            public void saveFinal() {
                try {
                    if (services.settings() != null && services.playback() != null && !state.resetting)
                        commands.recordResume(false);
                } finally {
                    services.save();
                }
            }

            @Override
            public void close() {
                state.closed = true;
            }

            @Override
            public void cancelWork() {
                new Cleanup().then("folder picker", DesktopFolderPicker::cancel)
                    .then("media operation", media::cancel)
                    .then("browse pool", services.browsing()::shutdownNow).run();
            }

            @Override
            public void flushHistory() {
                if (services.listeningHistory() != null) services.listeningHistory().flush(true);
            }

            @Override
            public void drainStorage() {
                var io = services.io();
                io.shutdown();
                try {
                    if (!io.awaitTermination(2, TimeUnit.SECONDS)) io.shutdownNow();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    io.shutdownNow();
                }
            }

            @Override
            public void confirmSaved() {
                var write = services.settingsStore().lastWrite();
                if (!write.isDone())
                    LoggerFactory.getLogger("darkmusic").error("The final settings were still queued when Minecraft closed; the previous config.json remains");
                else if (write.isCompletedExceptionally())
                    LoggerFactory.getLogger("darkmusic").error("The final settings could not be written", write.exceptionNow());
            }
        }, new Shutdown.Services(services.updates(), services.discord(), services.mediaControls(), services.mediaPackage(),
            services.pip(), services.sourceLinks(), services.openCleanup(), services.openSourceIndex(), services.audition(),
            services.lyrics(), services.downloads(), services.library(), services.playback(), services.artwork(),
            services.waveforms()));
    }
}
