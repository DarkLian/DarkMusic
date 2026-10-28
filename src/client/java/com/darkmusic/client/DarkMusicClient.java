package com.darkmusic.client;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.audio.OutputDevices;
import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.ResumeTracker;
import com.darkmusic.client.audio.SleepTimer;
import com.darkmusic.client.audio.TrackWaveform;
import com.darkmusic.client.audio.WaveformCache;
import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.cleanup.DuplicateResult;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.command.LocalCommands;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.ResetStorage;
import com.darkmusic.client.config.RestoreJournal;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.gui.AudioSettingsScreen;
import com.darkmusic.client.gui.DesktopFolderPicker;
import com.darkmusic.client.gui.FolderTree;
import com.darkmusic.client.gui.Help;
import com.darkmusic.client.gui.HudEditorScreen;
import com.darkmusic.client.gui.ImportOverlay;
import com.darkmusic.client.gui.LocalScreen;
import com.darkmusic.client.gui.LyricsScreen;
import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.gui.PipScreen;
import com.darkmusic.client.gui.PlayerProgress;
import com.darkmusic.client.gui.RowList;
import com.darkmusic.client.gui.TrackThumbnails;
import com.darkmusic.client.hud.DownloadHud;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.hud.HudSources;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.hud.SongCard;
import com.darkmusic.client.hud.SpeedFeedback;
import com.darkmusic.client.hud.VolumeFeedback;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.pip.PipController;
import com.darkmusic.client.pip.PipSources;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.presence.DiscordPresence;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.systemmedia.MediaControls;
import com.darkmusic.client.systemmedia.MediaControlsPackage;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import com.darkmusic.client.update.UpdateService;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DarkMusicClient implements ClientModInitializer, PipScreen.Sources, LyricsScreen.Sources,
    AudioSettingsScreen.Sources, ImportOverlay.Sources, PlayerProgress.Sources, TrackThumbnails {
    public static DarkMusicClient INSTANCE;
    public final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-storage").factory());
    public final ExecutorService browsing = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("darkmusic-browse").factory(), new ThreadPoolExecutor.AbortPolicy());
    public final ConfigStore config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("darkmusic"), io, this::warn);
    public final SpeedFeedback speedFeedback = new SpeedFeedback();
    public final VolumeFeedback volumeFeedback = new VolumeFeedback();
    // Reads the fields on every call: reset and restore replace the services behind them.
    public final HudSources hud = new HudSources() {
        public Settings settings() {
            return settings;
        }

        public PlaybackController playback() {
            return playback;
        }

        public LyricsService lyrics() {
            return lyrics;
        }

        public DownloadService downloads() {
            return downloads;
        }

        public List<Track> tracks() {
            return library.snapshot().tracks();
        }

        public ArtworkCache.Cover artwork(Track track) {
            return artwork.get(track);
        }

        public TrackWaveform waveform(Track track) {
            return waveforms == null ? null : waveforms.get(track);
        }
    };
    // Same for PiP.
    private final PipSources pipSources = new PipSources() {
        public Settings settings() {
            return settings;
        }

        public PlaybackController playback() {
            return playback;
        }

        public PipController pip() {
            return pip;
        }
    };
    public volatile Settings settings;
    public volatile String warning = "";
    public final Shortcuts shortcuts = new Shortcuts(this);
    public final FolderActions folders = new FolderActions(this);
    public final MediaActions media = new MediaActions(this);
    public MusicLibrary library;
    public PlaybackController playback;
    private JavaSoundBackend backend;
    public ListeningHistory listeningHistory;
    private boolean outputChanging;
    DuplicateScanner cleanupScanner;
    DuplicateReviewStore cleanupReviews;
    LibrarySourceIndex sourceIndex;
    private long sourceEvidenceRevision = -1;
    private List<DuplicateResult> sourceEvidence = List.of();

    public LibrarySourceIndex sourceIndex() {
        if (sourceIndex == null) {
            sourceIndex = new LibrarySourceIndex(config.directory());
            var s = library.snapshot();
            sourceIndex.roots(library.roots());
            if (!s.scanning()) sourceIndex.update(s.tracks(), library.roots(), s.complete());
            downloads.awareness(sourceIndex);
        }
        return sourceIndex;
    }

    public DuplicateScanner cleanup() {
        if (cleanupScanner == null)
            cleanupScanner = new DuplicateScanner(config.directory());
        return cleanupScanner;
    }

    public DuplicateReviewStore cleanupReviews() {
        if (cleanupReviews == null)
            cleanupReviews = new DuplicateReviewStore(config.directory(), io);
        return cleanupReviews;
    }

    private PlaybackController createPlayback() {
        backend = new JavaSoundBackend(Minecraft.getInstance()::execute,
            PcmDecoder::openPrecise,
            new OutputDevices(settings.outputDevice, true, this::warn));
        backend.listening((track, session, seconds) -> {
            if (listeningHistory != null) listeningHistory.heard(track, session, seconds);
        });
        var controller = new PlaybackController(backend, this::warn);
        // Only the play-ranked lists freeze their order while they play; Recently Added and Never Played do not rank by plays.
        controller.onSourceChanged((source, queue) -> listeningHistory.playbackSource(source.rootId(),
            source.mode().equals("History") && Set.of("recent", "most").contains(source.group()) ? source.group() : null,
            queue, library.snapshot().tracks()));
        return controller;
    }

    @Override
    public String outputDeviceId() {
        return backend == null ? "" : backend.outputDeviceId();
    }

    @Override
    public LoudnessCache.AnalysisProgress loudnessAnalysis() {
        return backend == null ? LoudnessCache.AnalysisProgress.IDLE : backend.analysisProgress();
    }

    @Override
    public void analyzeLibraryLoudness() {
        if (backend == null || library == null || !alive()) return;
        if (backend.analysisProgress().running()) backend.cancelAnalysis();
        else if (!library.snapshot().scanning()) backend.analyzeLibrary(library.snapshot().tracks(), settings.audio);
    }

    @Override
    public CompletableFuture<Boolean> selectOutputDevice(String id) {
        var result = new CompletableFuture<Boolean>();
        if (backend == null || !accepting()) {
            result.complete(false);
            return result;
        }
        outputChanging = true;
        var selected = backend;
        selected.output(new OutputDevices(id, false, this::warn)).whenComplete((ok, failure) -> Minecraft.getInstance().execute(() -> {
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

    public CompletableFuture<Void> restoreBackup(BackupArchive.Restore restored, boolean bindings) {
        if (!accepting() || !playlistsReady || !listeningHistory.ready())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("library.darkmusic.busy")));
        var keys = new LinkedHashMap<String, String>();
        if (bindings) for (var key : shortcuts.bindings()) {
            String value = restored.settings().shortcuts.getOrDefault(key.getName(), key.saveString());
            try {
                InputConstants.getKey(value);
            } catch (RuntimeException invalid) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(Text.get("error.shortcut_invalid")));
            }
            keys.put("key_" + key.getName(), value);
        }
        mediaBusy = restoring = true;
        if (restored.selection().history()) listeningHistory.suspended(true);
        var finished = new CompletableFuture<Void>();
        CompletableFuture.runAsync(() -> {
            try {
                var contents = new LinkedHashMap<String, String>();
                if (restored.selection().settings() || bindings)
                    contents.put("config.json", ConfigStore.JSON.toJson(restored.settings()));
                if (restored.selection().playlists()) {
                    contents.put("playlists.json", ConfigStore.JSON.toJson(restored.playlists()));
                    contents.put("playlist-retained.json", ConfigStore.JSON.toJson(restored.retained()));
                }
                if (restored.selection().history())
                    contents.put("listening-history.json", ListeningHistory.json(restored.history()));

                if (bindings) contents.put("options.txt", config.shortcutsContents(keys));
                RestoreJournal.install(config.directory(), contents);
                if (contents.containsKey("config.json")) config.committedSnapshot(contents.get("config.json"));
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, io).whenComplete((unused, failure) -> Minecraft.getInstance().execute(() -> {
            try {
                if (failure != null) {
                    finished.completeExceptionally(new IOException(Text.get("backup.failed"), failure));
                    return;
                }
                if (closed) {
                    finished.complete(null);
                    return;
                }
                if (restored.selection().settings() || bindings) settings = restored.settings();
                if (restored.selection().playlists())
                    playlists.restoredFromDisk(restored.playlists(), restored.retained());
                if (restored.selection().history()) listeningHistory.replace(restored.history());
                if (bindings) {
                    for (var key : shortcuts.bindings())
                        key.setKey(InputConstants.getKey(keys.get("key_" + key.getName())));
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
                mediaBusy = restoring = RestoreJournal.recoveryPending(config.directory());
                listeningHistory.suspended(restoring);
            }
        }));
        return finished;
    }

    public ArtworkCache artwork;
    public WaveformCache waveforms;
    public PipController pip;
    public PlaylistStore playlists;
    public DownloadService downloads;
    public AuditionPlayer audition;
    public UpdateService updates;
    public final DiscordPresence discord = new DiscordPresence();
    public final MediaControlsPackage mediaPackage = new MediaControlsPackage(config.directory());
    public final MediaControls mediaControls = new MediaControls(
        config.directory().resolve("media-native"), action -> Minecraft.getInstance().execute(() -> mediaAction(action)));
    public final LyricsService lyrics = new LyricsService(config.directory().resolve("lyrics"));
    public boolean playlistsReady;
    // Set while an import, edit, deletion or backup restore owns the music files; menus read it to disable actions.
    boolean mediaBusy;

    @Override
    public boolean mediaBusy() {
        return mediaBusy;
    }

    private boolean restoring;
    public boolean importing;
    public String importDestination = "";
    public ImportHistory importHistory;
    ProcessRunner.Token mediaToken;

    private volatile long warningUntil;
    boolean autoplayPending;
    public final SourceLinks sourceLinks = new SourceLinks();
    private boolean playlistCleanupPending;
    private long cleanedScan = -1, cleanedPlaylists = -1;
    private long queuedLibraryRevision = -1;

    public boolean canEditQueue() {
        return accepting() && playback != null;
    }

    private volatile boolean closed;
    private boolean resetting;

    /**
     * Not shutting down and not resetting storage.
     */
    boolean alive() {
        return !closed && !resetting;
    }

    /**
     * Alive, with no import, restore or other media operation in progress.
     */
    public boolean idle() {
        return alive() && !mediaBusy;
    }

    /**
     * Idle and not switching the output device: free to start another exclusive operation.
     */
    boolean accepting() {
        return idle() && !outputChanging;
    }

    private enum RequestedScreen {PLAYER, HUD, LYRICS, PIP, KEYS}

    private RequestedScreen requestedScreen;
    private Object requestedWorld;
    private long requestUntil;

    @Override
    public void warn(String text) {
        if (!text.equals(warning)) announce(text);
        warning = text;
        warningUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        LoggerFactory.getLogger("darkmusic").warn(text);
    }

    @Override
    public void announce(String text) {
        var client = Minecraft.getInstance();
        if (client != null) client.execute(() -> {
            if (!closed && client.getNarrator().isActive())
                client.getNarrator().saySystemQueued(Component.literal(text));
        });
    }

    public void seekRelative(double seconds) {
        if (idle() && playback != null) playback.seekRelative(seconds);
    }

    public void speed(double value) {
        settings.speed = PcmRateConverter.clamp(value);
        playback.speed(settings.speed);
        save();
    }

    public void volume(double value) {
        settings.volume = Settings.clampVolume(value);
        if (settings.volume > 0) settings.lastAudibleVolume = settings.volume;
        playback.volume(settings.volume);
        save();
    }

    public final SleepTimer sleep = new SleepTimer();

    public void sleepTimer(int minutes) {
        sleep.start(minutes, playback, settings == null ? 1 : settings.volume);
    }

    /**
     * A media key, headset button or Windows media flyout button, on the client thread.
     */
    private void mediaAction(MediaControls.Action action) {
        if (!idle() || settings == null || playback == null || library == null || !settings.mediaControls) return;
        var state = playback.state();
        switch (action.button()) {
            case "play" -> {
                if (state.track() == null) startPlayback();
                else if (state.status() == AudioBackend.Status.PAUSED) playback.resume();
                else if (state.status() != AudioBackend.Status.PLAYING) playback.toggle();
            }
            case "pause" -> {
                if (state.status() == AudioBackend.Status.PLAYING) playback.pause();
            }
            case "next" -> playback.next();
            case "previous" -> playback.previous();
            case "stop" -> playback.stop();
            case "seek" -> {
                if (state.track() != null && state.duration() > 0)
                    playback.seek(Math.clamp(action.position(), 0, state.duration()));
            }
            default -> {
            }
        }
    }

    public void shuffle(boolean on) {
        settings.shuffle = on;
        playback.shuffle(on);
        save();
    }

    public void repeat(Repeat mode) {
        settings.repeat = mode;
        playback.repeat(mode);
        save();
    }

    public void toggleMute() {
        volume(settings.volume > 0 ? 0 : settings.lastAudibleVolume);
        volumeFeedback.show(settings.volume);
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public HudSources hud() {
        return hud;
    }

    @Override
    public PipController pip() {
        return pip;
    }

    @Override
    public LyricsService lyrics() {
        return lyrics;
    }

    @Override
    public PlaybackController playback() {
        return playback;
    }

    @Override
    public MusicLibrary library() {
        return library;
    }

    @Override
    public String warning() {
        return warning;
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    @Override
    public boolean importing() {
        return importing;
    }

    @Override
    public String importDestination() {
        return importDestination;
    }

    @Override
    public ArtworkCache.Cover thumbnail(Track track) {
        return artwork.thumbnail(track);
    }

    @Override
    public void save() {
        if (alive() && !restoring && settings != null) {
            configureVisualizer();
            config.save(settings);
        }
    }

    @Override
    public void configureVisualizer() {
        if (settings != null) {
            UiTheme.apply(settings);
            ExternalAudio.configure(() -> new ExternalAudio.Paths(config.directory(), settings.ffmpegPath, settings.ffprobePath));
            if (downloads != null) downloads.format(settings.downloadFormat);
            lyrics.enabled(settings.lyricsEnabled);
            lyrics.lookup.configure(settings.lyricsEnabled, settings.lyricsOnline);
            lyrics.lookup.captionPreferences(settings.lyricsLanguages, settings.lyricsAutoCaptions);
            if (playback != null) playback.configure(settings.audio, settings.environment);
        }
        if (playback != null && playback.spectrum() != null) {
            playback.spectrum().configure(settings.hudVisualizer || settings.hudStyle == Settings.HudStyle.CIRCULAR && settings.circularIconPulse, settings.visualizerBars, settings.visualizerSensitivity, settings.visualizerSmoothing);
            playback.spectrum().refine(settings.visualQuality);
        }
    }

    public void open() {
        var client = Minecraft.getInstance();
        var screen = chatless(client.gui.screen());
        if (alive() && settings != null && library != null && client.gui.overlay() == null && client.gui.canInterruptScreen()
            && (screen == null || screen instanceof TitleScreen) && (client.player == null || client.player.isAlive()))
            client.gui.setScreen(new MusicScreen(this, screen));
    }

    /**
     * The songs of one player collection: a folder ("All" with a folder group), playlist, artist, album or history list.
     */
    public static final int RECENTLY_ADDED = 100;

    public List<Track> collection(String mode, String group) {
        var tracks = library.snapshot().tracks();
        if (mode.equals("History")) return switch (group) {
            case "most" -> listeningHistory.collection(tracks, true);
            case "added" -> ListeningHistory.recentlyAdded(tracks, RECENTLY_ADDED);
            case "never" -> listeningHistory.neverPlayed(tracks);
            default -> listeningHistory.collection(tracks, false);
        };
        var source = mode.equals("Playlists") ? playlists.resolve(group, tracks) : tracks;
        return source.stream().filter(t -> switch (mode) {
            case "All" -> FolderTree.includes(group, t);
            case "Artists" -> t.artist().equals(group);
            case "Albums" -> t.album().equals(group);
            default -> true;
        }).toList();
    }

    /**
     * Resume Where You Left Off: plays the remembered song from its position, in the collection it came from, or in
     * the whole library when that collection no longer has it. Returns false when there is nothing to resume.
     */
    public boolean resumeLast(boolean paused) {
        var point = settings == null || !settings.resumeLast ? null : settings.resume;
        if (point == null || library == null || playback == null || !idle()) return false;
        var tracks = collection(point.mode, point.group);
        var source = new PlaybackController.Source(point.mode, point.group, "", "");
        var track = tracks.stream().filter(t -> t.id().equals(point.track)).findFirst().orElse(null);
        if (track == null) {
            tracks = library.snapshot().tracks();
            source = new PlaybackController.Source("All", "", "", "");
            track = tracks.stream().filter(t -> t.id().equals(point.track)).findFirst().orElse(null);
        }
        if (track == null) return false;
        // Within the last few seconds the song would end at once; start it over instead.
        double position = track.duration() > 0 && point.position > track.duration() - 5 ? 0 : point.position;
        playback.play(tracks, track, source, position, paused);
        return true;
    }

    /**
     * Starts with nothing playing: the remembered song, or else the library's first. Returns the song, or null.
     */
    public Track startPlayback() {
        if (resumeLast(false)) return playback.state().track();
        var tracks = library.snapshot().tracks();
        if (tracks.isEmpty()) return null;
        playback.play(tracks, tracks.getFirst());
        return tracks.getFirst();
    }

    /**
     * Whether Play on this song should continue it from the remembered position.
     */
    public boolean resumes(Track track) {
        var point = settings == null || !settings.resumeLast ? null : settings.resume;
        return point != null && track != null && point.track.equals(track.id());
    }

    private final ResumeTracker resume = new ResumeTracker();

    private void recordResume(boolean persist) {
        if (resume.record(settings, playback, persist) && persist && alive() && !restoring) config.save(settings);
    }

    public void requestScreen(boolean layout) {
        requestedScreen = layout ? RequestedScreen.HUD : RequestedScreen.PLAYER;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    public String requestLyrics() {
        String problem = lyricsCommandProblem();
        if (!problem.isEmpty()) return problem;
        requestedScreen = RequestedScreen.LYRICS;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        return "";
    }

    public void requestPip() {
        requestedScreen = RequestedScreen.PIP;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    /**
     * Minecraft's Key Binds screen, where DarkMusic's shortcuts are changed.
     */
    public void requestKeyBinds() {
        requestedScreen = RequestedScreen.KEYS;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    /**
     * A button clicked in chat leaves chat open. The requested screen replaces it, and closing returns to the game.
     */
    private static Screen chatless(Screen screen) {
        return screen instanceof ChatScreen ? null : screen;
    }

    private String lyricsCommandProblem() {
        return LocalCommands.lyricsProblem(
            idle() && settings != null && library != null && playback != null,
            settings != null && settings.lyricsEnabled, playback == null ? null : playback.state());
    }

    private void openRequestedScreen(Minecraft client) {
        if (requestedScreen == null) return;
        if (!alive() || client.level != requestedWorld || System.nanoTime() > requestUntil || client.player != null && !client.player.isAlive()) {
            requestedScreen = null;
            return;
        }
        var screen = chatless(client.gui.screen());
        if (settings == null || library == null || client.gui.overlay() != null || !client.gui.canInterruptScreen()
            || screen != null && !(screen instanceof TitleScreen)) return;
        var requested = requestedScreen;
        requestedScreen = null;
        if (requested == RequestedScreen.LYRICS) {
            String problem = lyricsCommandProblem();
            if (!problem.isEmpty()) warn(Text.get(problem));
            else client.gui.setScreen(new LyricsScreen(this, screen));
        } else if (requested == RequestedScreen.PIP)
            client.gui.setScreen(new PipScreen(this, screen));
        else if (requested == RequestedScreen.KEYS)
            client.gui.setScreen(new KeyBindsScreen(screen, client.options));
        else if (requested == RequestedScreen.HUD)
            client.gui.setScreen(new HudEditorScreen(this, screen));
        else open();
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LocalScreen.saveOnClose(this::save);
        Help.settings(this::settings);
        RowList.warnings(this::warn);
        updates = new UpdateService(config.directory(), () -> settings, this::warn);
        LocalCommands.register(new ClientActions(this));
        NowPlayingHud.register(hud);
        LyricsHud.register(hud);
        SongCard.register(hud);
        volumeFeedback.register(hud);
        speedFeedback.register(hud);
        DownloadHud.register(hud);
        PipController.register(pipSources);
        shortcuts.register();
        CompletableFuture.supplyAsync(config::load, io).thenAccept(loaded -> Minecraft.getInstance().execute(() -> {
            if (!closed) {
                settings = loaded;
                ready();
                save();
            }
        })).exceptionally(failure -> {
            LoggerFactory.getLogger("darkmusic").error("Could not recover or load user data", failure);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause instanceof RestoreJournal.RecoveryConflict
                ? cause.getMessage() : Text.get("backup.recovery_failed");
            Minecraft.getInstance().execute(() -> warn(message));
            return null;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen)
                Screens.getWidgets(screen).add(Button.builder(Text.component("text.darkmusic.music"), b -> open()).bounds(6, 6, 64, 20).build());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pip != null) pip.tick();
            updates.tick(client);
            var current = settings;
            if (current != null && playback != null && !resetting) {
                var playing = playback.state().track();
                // Source links resolve in the background; the status gains the artwork once the link is known.
                String artwork = current.discordStatus && current.discordShowTitle && current.discordArtwork && playing != null
                    ? DiscordPresence.artwork(sourceLinks.get(playing)) : "";
                discord.update(current.discordStatus, current.discordShowTitle, playback.state(), current.speed, artwork);
            } else discord.update(false, true, null, 1, "");
            boolean media = current != null && current.mediaControls && playback != null && !resetting;
            mediaControls.update(media ? mediaPackage.executable() : null, media ? playback.state() : null, System.nanoTime());
            boolean allowed = !resetting && settings != null && client.gui.screen() == null && client.gui.overlay() == null && client.player != null && client.player.isAlive();
            boolean pressed = false;
            while (shortcuts.open.consumeClick()) pressed = true;
            if (allowed && pressed && !shortcuts.conflicts(shortcuts.open)) open();
            tick(client, allowed);
            openRequestedScreen(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (settings != null && playback != null && !resetting) recordResume(false);
            updates.close();
            discord.close();
            mediaControls.close();
            mediaPackage.close();
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
        listeningHistory = new ListeningHistory(config.directory(), io, this::warn);
        io.execute(listeningHistory::load);
        importHistory = new ImportHistory(config.directory(), io, this::warn);
        shortcuts.loadSaved();
        playlists = new PlaylistStore(config.directory(), io, this::warn);
        CompletableFuture.supplyAsync(playlists::read, io).thenAccept(list -> Minecraft.getInstance().execute(() -> {
            if (!closed) {
                playlists.loaded(list);
                playlistsReady = true;
            }
        }));
        artwork = new ArtworkCache();
        waveforms = new WaveformCache(config.directory());
        pip = new PipController(config.directory(), pipSources);
        playback = createPlayback();
        playback.volume(settings.volume);
        configureVisualizer();
        playback.speed(settings.speed);
        playback.shuffle(settings.shuffle);
        playback.repeat(settings.repeat);
        autoplayPending = settings.autoplay;
        library = new MusicLibrary(config.directory());
        downloads = createDownloads();
        // Read the field on each call: Reset to Defaults replaces the download service.
        lyrics.lookup.captions((track, preferences, generated, token) -> downloads.fetchLyrics(track, preferences, generated, token),
            (track, preferences, generated, token) -> downloads.fetchSourceLyrics(track, preferences, generated, token));
        lyrics.lookup.captionPreferences(settings.lyricsLanguages, settings.lyricsAutoCaptions);
        lyrics.lookup.configure(settings.lyricsEnabled, settings.lyricsOnline);
        try {
            folders.activate(settings.musicDirectory.isBlank() ? config.defaultMusic() : Path.of(settings.musicDirectory));
        } catch (Exception e) {
            warn(Text.failure(e, "error.saved_folder"));
            settings.musicDirectory = "";
            folders.activate(config.defaultMusic());
        }
    }

    /**
     * Startup and Reset to Defaults share this wiring, so a replaced service keeps its lyric hooks.
     */
    private DownloadService createDownloads() {
        var service = new DownloadService(config.directory(), () -> library.requestRescan());
        service.configure(new ToolPaths(settings.ytDlpPath, settings.ffmpegPath, settings.ffprobePath, settings.javascriptPath));
        service.format(settings.downloadFormat);
        service.savedObserver(file -> Minecraft.getInstance().execute(() -> {
            if (!closed && settings.lyricsEnabled && settings.lyricsOnline && settings.lyricsDownloadLookup)
                lyrics.lookup.downloaded(file, library.roots(), settings.lyricsLanguages, settings.lyricsAutoCaptions);
        }));
        if (sourceIndex != null) service.awareness(sourceIndex);
        return service;
    }

    /**
     * True when the current download service reports saved files to lyric lookup.
     */
    public boolean downloadsConnected() {
        return downloads != null && downloads.hasSavedObserver();
    }

    public Future<?> browse(Runnable task) {
        try {
            ((ThreadPoolExecutor) browsing).purge();
            return browsing.submit(task);
        } catch (RejectedExecutionException e) {
            if (!closed) warn(Text.get("error.folder_busy"));
            return null;
        }
    }

    private boolean cleanMissingPlaylistEntries() {
        if (mediaBusy || !playlistsReady || library == null || playlistCleanupPending) return false;
        var snapshot = library.snapshot();
        if (snapshot.completeRoots().isEmpty()) return false;
        if (cleanedScan == snapshot.revision() && cleanedPlaylists == playlists.revision()) return true;
        var store = playlists;
        var entries = store.all();
        long revision = store.revision();
        var roots = library.roots();
        playlistCleanupPending = true;
        io.execute(() -> {
            Set<PlaylistStore.Entry> missing;
            try {
                var all = new HashSet<PlaylistStore.Entry>();
                for (var entry : roots.entrySet())
                    if (snapshot.completeRoots().contains(entry.getValue()))
                        all.addAll(PlaylistStore.missing(entry.getKey(), entry.getValue(), entries, snapshot.tracks()));
                missing = all;
            } catch (Exception e) {
                missing = Set.of();
                warn(Text.failure(e, "error.playlist_scan"));
            }
            var result = missing;
            Minecraft.getInstance().execute(() -> {
                playlistCleanupPending = false;
                if (!idle() || playlists != store || store.revision() != revision
                    || !roots.equals(library.roots())) return;
                var current = library.snapshot();
                var present = store.index(current.tracks());
                store.removeMissing(result.stream().filter(entry -> current.completeRoots().contains(entry.rootId()) && !present.containsKey(entry.key())).collect(Collectors.toSet()));
                store.acknowledgeTracks(current.tracks());
                cleanedScan = snapshot.revision();
                cleanedPlaylists = store.revision();
            });
        });
        return false;
    }

    protected void tick(Minecraft client, boolean allowed) {
        if (waveforms != null && settings != null && playback != null) {
            var waveformTrack = playback.state().track();
            if (waveformTrack == null && client.gui.screen() instanceof HudPreview preview && preview.previewsProgress() && library != null) {
                var tracks = library.snapshot().tracks();
                if (!tracks.isEmpty()) waveformTrack = tracks.getFirst();
            }
            waveforms.update(waveformTrack, settings.waveformProgress && !mediaBusy);
        }
        if (settings != null && playback != null && settings.lyricsEnabled) {
            var track = playback.state().track();
            lyrics.request(track, settings.lyricsAutomaticLookup);
            if (library != null) {
                var snapshot = library.snapshot();
                if (snapshot.scanning() || mediaBusy) lyrics.lookup.tickRetries();
                else
                    lyrics.background(snapshot.tracks(), snapshot.completeRoots(), settings.lyricsOnline && lyrics.libraryLookupRunning());
            }
        }
        allowed &= !mediaBusy;
        if (!warning.isEmpty() && System.nanoTime() >= warningUntil) warning = "";
        shortcuts.consume(allowed);
        if (resetting || settings == null || playback == null) return;
        var queuedLibrary = library.snapshot();
        if (!mediaBusy && queuedLibraryRevision != queuedLibrary.revision()) {
            queuedLibraryRevision = queuedLibrary.revision();
            if (!queuedLibrary.scanning()) lyrics.lookup.prune(queuedLibrary.tracks(),
                new HashSet<>(library.roots().values()), queuedLibrary.completeRoots(), queuedLibrary.unreadableScopes());
            if (!queuedLibrary.scanning()) {
                var registered = new HashSet<>(library.roots().values());
                var cleanupRoots = new HashSet<>(queuedLibrary.completeRoots());
                cleanupRoots.removeAll(queuedLibrary.unreadableScopes().keySet());
                if (cleanupReviews != null)
                    cleanupReviews.prune(queuedLibrary.tracks(), registered, cleanupRoots);
                if (cleanupScanner != null)
                    cleanupScanner.prune(queuedLibrary.tracks(), registered, cleanupRoots);
            }
            if (sourceIndex != null && !mediaBusy) {
                sourceIndex.roots(library.roots());
                if (!queuedLibrary.scanning())
                    sourceIndex.update(queuedLibrary.tracks(), library.roots(), queuedLibrary.complete());
            }
            if (!mediaBusy && !queuedLibrary.scanning())
                playback.reconcileQueued(playlists.index(queuedLibrary.tracks()),
                    new HashSet<>(library.roots().values()), queuedLibrary.completeRoots());
        }
        if (listeningHistory != null && !mediaBusy) listeningHistory.flush(false);
        if (sourceIndex != null && cleanupScanner != null && sourceEvidenceRevision != cleanupScanner.snapshot().revision()) {
            sourceEvidenceRevision = cleanupScanner.snapshot().revision();
            var pairs = cleanupScanner.snapshot().pairs();
            if (pairs != sourceEvidence) {
                sourceEvidence = pairs;
                var index = sourceIndex;
                io.execute(() -> index.evidence(pairs));
            }
        }
        boolean playlistsClean = cleanMissingPlaylistEntries();
        var startup = library.snapshot();
        if (autoplayPending && !mediaBusy && playlistsClean && !startup.scanning()) {
            autoplayPending = false;
            startPlayback();
        }
        if (!autoplayPending && !mediaBusy) recordResume(true);
        sleep.tick(playback, settings.volume);
    }

    /**
     * Called on the client thread only, after explicit confirmation.
     */
    public CompletableFuture<Void> resetDefaults() {
        if (!idle())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("reset.darkmusic.busy")));
        resetting = true;
        if (cleanupScanner != null) {
            cleanupScanner.close();
            cleanupScanner = null;
        }
        if (sourceIndex != null) {
            sourceIndex.close();
            sourceIndex = null;
        }
        cleanupReviews = null;
        sourceEvidenceRevision = -1;
        sourceEvidence = List.of();
        requestedScreen = null;
        requestedWorld = null;
        folders.invalidate();
        autoplayPending = false;
        DesktopFolderPicker.cancel();
        var prior = settings;
        var defaultBindings = shortcuts.defaults();
        var roots = new LinkedHashSet<Path>();
        roots.add(library.root());
        for (String root : prior.roots.keySet())
            try {
                roots.add(Path.of(root));
            } catch (InvalidPathException ignored) {
            }
        playback.reset();
        speedFeedback.clear();
        volumeFeedback.clear();
        artwork.close();
        if (waveforms != null) waveforms.close();
        if (pip != null) pip.stop();
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
                    throw new IOException(Text.get("reset.darkmusic.waiting"));
                var retained = ResetStorage.clear(config.directory(), roots);
                importHistory.reset();
                config.resetShortcuts(defaultBindings);
                return retained;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, io).whenComplete((retained, failure) -> Minecraft.getInstance().execute(() -> {
            if (closed) {
                finished.completeExceptionally(new IllegalStateException(Text.get("reset.darkmusic.closing")));
                return;
            }
            settings = failure == null ? Settings.defaults(prior) : prior;
            playback = createPlayback();
            artwork = new ArtworkCache();
            waveforms = new WaveformCache(config.directory());
            library = new MusicLibrary(config.directory());
            downloads = createDownloads();
            resetting = false;
            playback.volume(settings.volume);
            playback.speed(settings.speed);
            playback.shuffle(settings.shuffle);
            playback.repeat(settings.repeat);
            configureVisualizer();
            if (failure == null) {
                warning = "";
                warningUntil = 0;
                shortcuts.restoreDefaults();
            }
            try {
                folders.activate(settings.musicDirectory.isBlank() ? config.defaultMusic() : Path.of(settings.musicDirectory));
            } catch (Exception e) {
                folders.activate(config.defaultMusic());
            }
            save();
            if (failure != null) finished.completeExceptionally(failure);
            else if (!retained.isEmpty()) {
                String message = Text.get("reset.darkmusic.retained");
                warn(message);
                finished.completeExceptionally(new IOException(message));
            } else {
                String expected = ConfigStore.JSON.toJson(settings);
                CompletableFuture.runAsync(() -> {
                    try {
                        if (!Files.readString(config.directory().resolve("config.json")).equals(expected))
                            throw new IOException(Text.get("reset.darkmusic.save_failed"));
                    } catch (IOException e) {
                        throw new CompletionException(e);
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
        DesktopFolderPicker.cancel();
        if (mediaToken != null) mediaToken.cancel();
        browsing.shutdownNow();
        sourceLinks.close();
        if (cleanupScanner != null) cleanupScanner.close();
        if (sourceIndex != null) sourceIndex.close();
        if (audition != null) audition.close();
        if (downloads != null) downloads.close();
        if (library != null) library.close();
        if (playback != null) playback.close();
        if (pip != null) pip.close();
        if (listeningHistory != null) listeningHistory.flush(true);
        if (artwork != null) artwork.close();
        if (waveforms != null) waveforms.close();
        lyrics.close();
    }
}
