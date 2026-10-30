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
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.command.LocalCommands;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.ResetStorage;
import com.darkmusic.client.config.RestoreJournal;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.gui.AudioDeletionScreen;
import com.darkmusic.client.gui.AudioSettingsScreen;
import com.darkmusic.client.gui.BackupScreen;
import com.darkmusic.client.gui.ColorsScreen;
import com.darkmusic.client.gui.DesktopFolderPicker;
import com.darkmusic.client.gui.DownloadDuplicateScreen;
import com.darkmusic.client.gui.DownloadPathsScreen;
import com.darkmusic.client.gui.DownloadScreen;
import com.darkmusic.client.gui.DownloadToolsScreen;
import com.darkmusic.client.gui.DuplicateComparisonScreen;
import com.darkmusic.client.gui.ExistingDownloadsScreen;
import com.darkmusic.client.gui.FolderTree;
import com.darkmusic.client.gui.Help;
import com.darkmusic.client.gui.HudEditorScreen;
import com.darkmusic.client.gui.IgnoredMatchesScreen;
import com.darkmusic.client.gui.ImportHistoryScreen;
import com.darkmusic.client.gui.LibraryManagerScreen;
import com.darkmusic.client.gui.LocalScreen;
import com.darkmusic.client.gui.LyricsScreen;
import com.darkmusic.client.gui.MetadataScreen;
import com.darkmusic.client.gui.MusicFoldersScreen;
import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.gui.Navigation;
import com.darkmusic.client.gui.PipScreen;
import com.darkmusic.client.gui.PlaylistScreen;
import com.darkmusic.client.gui.RowList;
import com.darkmusic.client.gui.SettingsScreen;
import com.darkmusic.client.gui.ShortcutKeys;
import com.darkmusic.client.gui.SmartCleanupScreen;
import com.darkmusic.client.gui.SongActionsScreen;
import com.darkmusic.client.gui.TrackPickerScreen;
import com.darkmusic.client.gui.UpNextScreen;
import com.darkmusic.client.gui.UpdateScreen;
import com.darkmusic.client.hud.DownloadHud;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.hud.HudSources;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.hud.SongCard;
import com.darkmusic.client.hud.SpeedFeedback;
import com.darkmusic.client.hud.VolumeFeedback;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.AudioDeletion;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsLookup;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// The composition root supplies each screen's Sources; ScreenNavigation builds the screens the player and Settings open.
public class DarkMusicClient implements ClientModInitializer, MusicScreen.Sources, SettingsScreen.Sources,
    UpNextScreen.Sources, DownloadScreen.Sources, ImportHistoryScreen.Sources, LibraryManagerScreen.Sources,
    PipScreen.Sources, PlaylistScreen.Sources, SongActionsScreen.Sources, MetadataScreen.Sources, LyricsScreen.Sources,
    AudioSettingsScreen.Sources, BackupScreen.Sources, HudEditorScreen.Sources, MusicFoldersScreen.Sources,
    UpdateScreen.Sources, ColorsScreen.Sources, DownloadToolsScreen.Sources, DownloadPathsScreen.Sources,
    DownloadDuplicateScreen.Sources, ExistingDownloadsScreen.Sources, SmartCleanupScreen.Sources,
    IgnoredMatchesScreen.Sources, DuplicateComparisonScreen.Sources, AudioDeletionScreen.Sources, TrackPickerScreen.Sources {
    public static DarkMusicClient INSTANCE;
    public final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-storage").factory());
    public final ExecutorService browsing = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("darkmusic-browse").factory(), new ThreadPoolExecutor.AbortPolicy());
    public final ConfigStore config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("darkmusic"), io, this::warn);
    public final SpeedFeedback speedFeedback = new SpeedFeedback();
    public final VolumeFeedback volumeFeedback = new VolumeFeedback();
    // Owns the live settings and the rule for writing them; load, reset and restore replace them through it.
    private final SettingsStore settingsStore = new SettingsStore(config::save, () -> alive() && !this.state.restoring, this::applySettings);
    // Reads the fields on every call: reset and restore replace the services behind them.
    public final HudSources hud = new HudSources() {
        public Settings settings() {
            return settingsStore.settings();
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
            return settingsStore.settings();
        }

        public PlaybackController playback() {
            return playback;
        }

        public PipController pip() {
            return pip;
        }
    };
    public volatile String warning = "";
    private final Navigation navigation = new ScreenNavigation<>(this);
    public final Shortcuts shortcuts = new Shortcuts(this);
    public final FolderActions folders = new FolderActions(this);
    public final MediaActions media = new MediaActions(this);
    public MusicLibrary library;
    public PlaybackController playback;
    private JavaSoundBackend backend;
    public ListeningHistory listeningHistory;
    DuplicateScanner cleanupScanner;
    DuplicateReviewStore cleanupReviews;
    LibrarySourceIndex sourceIndex;
    // Reads the fields on every call, like hud: reset replaces the library and playback behind them.
    private final LibraryReconciliation reconciliation = new LibraryReconciliation(new LibraryReconciliation.Inputs() {
        public MusicLibrary.Snapshot snapshot() {
            return library.snapshot();
        }

        public Map<Path, String> roots() {
            return library.roots();
        }

        public PlaylistStore playlists() {
            return playlists;
        }

        public boolean playlistsReady() {
            return playlistsReady;
        }

        public boolean idle() {
            return DarkMusicClient.this.idle();
        }

        public boolean mediaBusy() {
            return state.mediaBusy;
        }

        public PlaybackController playback() {
            return playback;
        }

        public LyricsLookup lyrics() {
            return lyrics.lookup;
        }

        public DuplicateScanner cleanupScanner() {
            return cleanupScanner;
        }

        public DuplicateReviewStore cleanupReviews() {
            return cleanupReviews;
        }

        public LibrarySourceIndex sourceIndex() {
            return sourceIndex;
        }
    }, io, task -> Minecraft.getInstance().execute(task), this::warn);

    @Override
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

    @Override
    public DuplicateScanner cleanup() {
        if (cleanupScanner == null)
            cleanupScanner = new DuplicateScanner(config.directory());
        return cleanupScanner;
    }

    @Override
    public DuplicateReviewStore cleanupReviews() {
        if (cleanupReviews == null)
            cleanupReviews = new DuplicateReviewStore(config.directory(), io);
        return cleanupReviews;
    }

    private PlaybackController createPlayback() {
        backend = new JavaSoundBackend(Minecraft.getInstance()::execute,
            PcmDecoder::openPrecise,
            new OutputDevices(settings().outputDevice, true, this::warn));
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
        else if (!library.snapshot().scanning()) backend.analyzeLibrary(library.snapshot().tracks(), settings().audio);
    }

    @Override
    public CompletableFuture<Boolean> selectOutputDevice(String id) {
        var result = new CompletableFuture<Boolean>();
        if (backend == null || !accepting()) {
            result.complete(false);
            return result;
        }
        var selected = backend;
        CompletableFuture<Boolean> switching;
        try {
            switching = selected.output(new OutputDevices(id, false, this::warn));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger("darkmusic").warn("Could not start switching the output device", failure);
            result.complete(false);
            return result;
        }
        state.outputChanging = true;
        // Applies only to the backend it was asked of: a reset in between replaces the backend and voids the switch.
        switching.whenComplete((ok, failure) -> Minecraft.getInstance().execute(() -> {
            state.outputChanging = false;
            boolean applied = !state.closed && backend == selected && failure == null && ok;
            try {
                if (applied) {
                    settings().outputDevice = id;
                    playback.outputChanged();
                    save();
                }
            } finally {
                result.complete(applied);
            }
        }));
        return result;
    }

    @Override
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
        return RestoreOperation.run(new RestoreOperation.Steps() {
            @Override
            public void begin() {
                state.mediaBusy = state.restoring = true;
                if (restored.selection().history()) listeningHistory.suspended(true);
            }

            @Override
            public void install() throws IOException {
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
            }

            @Override
            public void apply() {
                if (restored.selection().settings() || bindings) settingsStore.replace(restored.settings());
                if (restored.selection().playlists())
                    playlists.restoredFromDisk(restored.playlists(), restored.retained());
                if (restored.selection().history()) listeningHistory.replace(restored.history());
                if (bindings) {
                    for (var key : shortcuts.bindings())
                        key.setKey(InputConstants.getKey(keys.get("key_" + key.getName())));
                    KeyMapping.resetMapping();
                }
                playback.volume(settings().volume);
                playback.speed(settings().speed);
                playback.shuffle(settings().shuffle);
                playback.repeat(settings().repeat);
                // The journal already wrote the files; the next save writes anything applying normalized.
                applySettings();
            }

            @Override
            public boolean recoveryPending() {
                return RestoreJournal.recoveryPending(config.directory());
            }

            @Override
            public void end(boolean recoveryPending) {
                state.mediaBusy = state.restoring = recoveryPending;
                listeningHistory.suspended(recoveryPending);
            }

            @Override
            public boolean closed() {
                return state.closed;
            }
        }, Minecraft.getInstance()::execute, io);
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
    // Shutdown, reset and busy flags, and the checks built from them.
    final ClientState state = new ClientState();

    @Override
    public boolean mediaBusy() {
        return state.mediaBusy;
    }

    public boolean importing;
    public String importDestination = "";
    public ImportHistory importHistory;
    ProcessRunner.Token mediaToken;

    private volatile long warningUntil;
    boolean autoplayPending;
    public final SourceLinks sourceLinks = new SourceLinks();

    @Override
    public boolean canEditQueue() {
        return accepting() && playback != null;
    }

    boolean alive() {
        return state.alive();
    }

    public boolean idle() {
        return state.idle();
    }

    boolean accepting() {
        return state.accepting();
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
            if (!state.closed && client.getNarrator().isActive())
                client.getNarrator().saySystemQueued(Component.literal(text));
        });
    }

    @Override
    public void seekRelative(double seconds) {
        if (idle() && playback != null) playback.seekRelative(seconds);
    }

    @Override
    public void speed(double value) {
        settings().speed = PcmRateConverter.clamp(value);
        playback.speed(settings().speed);
        save();
    }

    @Override
    public void volume(double value) {
        settings().volume = Settings.clampVolume(value);
        if (settings().volume > 0) settings().lastAudibleVolume = settings().volume;
        playback.volume(settings().volume);
        save();
    }

    public final SleepTimer sleep = new SleepTimer();

    public void sleepTimer(int minutes) {
        sleep.start(minutes, playback, settings() == null ? 1 : settings().volume);
    }

    /**
     * A media key, headset button or Windows media flyout button, on the client thread.
     */
    private void mediaAction(MediaControls.Action action) {
        if (!idle() || settings() == null || playback == null || library == null || !settings().mediaControls) return;
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

    @Override
    public void shuffle(boolean on) {
        settings().shuffle = on;
        playback.shuffle(on);
        save();
    }

    @Override
    public void repeat(Repeat mode) {
        settings().repeat = mode;
        playback.repeat(mode);
        save();
    }

    @Override
    public void toggleMute() {
        volume(settings().volume > 0 ? 0 : settings().lastAudibleVolume);
        volumeFeedback.show(settings().volume);
    }

    @Override
    public Settings settings() {
        return settingsStore.settings();
    }

    @Override
    public Navigation navigation() {
        return navigation;
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
    public ArtworkCache artwork() {
        return artwork;
    }

    @Override
    public DownloadService downloads() {
        return downloads;
    }

    @Override
    public PlaylistStore playlists() {
        return playlists;
    }

    @Override
    public boolean playlistsReady() {
        return playlistsReady;
    }

    @Override
    public ListeningHistory listeningHistory() {
        return listeningHistory;
    }

    @Override
    public ImportHistory importHistory() {
        return importHistory;
    }

    @Override
    public UpdateService updates() {
        return updates;
    }

    @Override
    public ConfigStore config() {
        return config;
    }

    @Override
    public KeyMapping[] shortcutBindings() {
        return shortcuts.bindings();
    }

    @Override
    public void openFolder(Path directory) {
        folders.open(directory);
    }

    @Override
    public void resetMusicFolder() {
        folders.reset();
    }

    @Override
    public void chooseMusicFolder(Path directory, Runnable success) {
        folders.choose(directory, success);
    }

    @Override
    public boolean removeMusicFolder(Path directory) {
        return folders.remove(directory);
    }

    @Override
    public void addMusicFolder(Path directory) {
        folders.add(directory);
    }

    @Override
    public CompletableFuture<MediaFiles.Fields> readMetadata(Track track) {
        return media.readMetadata(track);
    }

    @Override
    public CompletableFuture<Track> editMetadata(Track track, MediaFiles.Fields original, MediaFiles.Fields fields) {
        return media.editMetadata(track, original, fields);
    }

    @Override
    public ExecutorService browsing() {
        return browsing;
    }

    @Override
    public AuditionPlayer audition() {
        return audition;
    }

    @Override
    public void audition(AuditionPlayer player) {
        audition = player;
    }

    @Override
    public ShortcutKeys shortcuts() {
        return shortcuts;
    }

    @Override
    public SourceLinks sourceLinks() {
        return sourceLinks;
    }

    @Override
    public MediaControls mediaControls() {
        return mediaControls;
    }

    @Override
    public MediaControlsPackage mediaPackage() {
        return mediaPackage;
    }

    @Override
    public DiscordPresence discord() {
        return discord;
    }

    @Override
    public void importAudio(List<Path> paths, String folder, String playlistId) {
        media.importAudio(paths, folder, playlistId);
    }

    @Override
    public void openMusicFolder() {
        folders.open();
    }

    @Override
    public CompletableFuture<AudioDeletion.Plan> prepareAudioDeletion(List<Track> tracks) {
        return media.prepareAudioDeletion(tracks);
    }

    @Override
    public CompletableFuture<AudioDeletion.Result> deleteAudio(AudioDeletion.Plan plan, AudioDeletion.Mode mode,
                                                               ProcessRunner.Token token,
                                                               Consumer<AudioDeletion.Progress> progress) {
        return media.deleteAudio(plan, mode, token, progress);
    }

    @Override
    public void save() {
        settingsStore.save();
    }

    /**
     * For checks and captures that start from known settings; they save or reapply afterwards themselves.
     */
    public void replaceSettings(Settings settings) {
        settingsStore.replace(settings);
    }

    /**
     * Pushes the current settings into the running services, in this order: normalize the values that are derived
     * from others (profile and environment names), then theme, external codec paths, download format, lyrics,
     * playback DSP and spectrum. Does not write: screens call it while a control moves and {@link #save()} (apply,
     * then write) when the change is committed. Playback compares and copies what it needs, so repeating it is cheap.
     */
    @Override
    public void applySettings() {
        var s = settings();
        if (s == null) return;
        // An edit while a profile or environment is selected turns it into Custom; saved entries stay themselves.
        s.audio.matchProfile();
        s.environment.matchPreset();
        UiTheme.apply(s);
        // Workers read a fixed copy; changed paths take effect the next time settings are applied, like the rest.
        var tools = new ExternalAudio.Paths(config.directory(), s.ffmpegPath, s.ffprobePath);
        ExternalAudio.configure(() -> tools);
        if (downloads != null) downloads.format(s.downloadFormat);
        lyrics.enabled(s.lyricsEnabled);
        lyrics.lookup.configure(s.lyricsEnabled, s.lyricsOnline);
        lyrics.lookup.captionPreferences(s.lyricsLanguages, s.lyricsAutoCaptions);
        if (playback == null) return;
        playback.configure(s.audio, s.environment);
        if (playback.spectrum() != null) {
            playback.spectrum().configure(s.hudVisualizer || s.hudStyle == Settings.HudStyle.CIRCULAR && s.circularIconPulse,
                s.visualizerBars, s.visualizerSensitivity, s.visualizerSmoothing);
            playback.spectrum().refine(s.visualQuality);
        }
    }

    public void open() {
        var client = Minecraft.getInstance();
        var screen = chatless(client.gui.screen());
        if (alive() && settings() != null && library != null && client.gui.overlay() == null && client.gui.canInterruptScreen()
            && (screen == null || screen instanceof TitleScreen) && (client.player == null || client.player.isAlive()))
            client.gui.setScreen(new MusicScreen(this, screen));
    }

    /**
     * The songs of one player collection: a folder ("All" with a folder group), playlist, artist, album or history list.
     */
    public static final int RECENTLY_ADDED = 100;

    @Override
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
    @Override
    public boolean resumeLast(boolean paused) {
        var point = settings() == null || !settings().resumeLast ? null : settings().resume;
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
    @Override
    public boolean resumes(Track track) {
        var point = settings() == null || !settings().resumeLast ? null : settings().resume;
        return point != null && track != null && point.track.equals(track.id());
    }

    private final ResumeTracker resume = new ResumeTracker();

    private void recordResume(boolean persist) {
        if (resume.record(settings(), playback, persist) && persist) settingsStore.persist();
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
            idle() && settings() != null && library != null && playback != null,
            settings() != null && settings().lyricsEnabled, playback == null ? null : playback.state());
    }

    private void openRequestedScreen(Minecraft client) {
        if (requestedScreen == null) return;
        if (!alive() || client.level != requestedWorld || System.nanoTime() > requestUntil || client.player != null && !client.player.isAlive()) {
            requestedScreen = null;
            return;
        }
        var screen = chatless(client.gui.screen());
        if (settings() == null || library == null || client.gui.overlay() != null || !client.gui.canInterruptScreen()
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
        updates = new UpdateService(config.directory(), this::settings, this::warn);
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
            if (!state.closed) start(loaded);
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
            var current = settings();
            if (current != null && playback != null && !state.resetting) {
                var playing = playback.state().track();
                // Source links resolve in the background; the status gains the artwork once the link is known.
                String artwork = current.discordStatus && current.discordShowTitle && current.discordArtwork && playing != null
                    ? DiscordPresence.artwork(sourceLinks.get(playing)) : "";
                discord.update(current.discordStatus, current.discordShowTitle, playback.state(), current.speed, artwork);
            } else discord.update(false, true, null, 1, "");
            boolean media = current != null && current.mediaControls && playback != null && !state.resetting;
            mediaControls.update(media ? mediaPackage.executable() : null, media ? playback.state() : null, System.nanoTime());
            boolean allowed = !state.resetting && settings() != null && client.gui.screen() == null && client.gui.overlay() == null && client.player != null && client.player.isAlive();
            boolean pressed = false;
            while (shortcuts.open.consumeClick()) pressed = true;
            if (allowed && pressed && !shortcuts.conflicts(shortcuts.open)) open();
            tick(client, allowed);
            openRequestedScreen(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
    }

    /**
     * Publishes the loaded settings and builds the services. If building fails part way, what was built is closed
     * and the settings are withdrawn, so the client stays in its not-yet-loaded state, which every screen, HUD and
     * shortcut already handles, instead of running against a partial set. The error is logged and announced.
     */
    private void start(Settings loaded) {
        settingsStore.replace(loaded);
        Startup.run(new Startup.Steps() {
            @Override
            public void build(Cleanup acquired) {
                ready(acquired);
            }

            @Override
            public void withdraw() {
                listeningHistory = null;
                importHistory = null;
                playlists = null;
                artwork = null;
                waveforms = null;
                pip = null;
                playback = null;
                backend = null;
                library = null;
                downloads = null;
                settingsStore.replace(null);
            }

            @Override
            public void failed(Throwable failure) {
                LoggerFactory.getLogger("darkmusic").error("DarkMusic could not start", failure);
                warn(Text.get("error.startup"));
            }

            @Override
            public void save() {
                DarkMusicClient.this.save();
            }
        });
    }

    /**
     * Builds the services in dependency order. Each one is registered with acquired as soon as it exists, so a
     * failure further on can close it.
     */
    private void ready(Cleanup acquired) {
        listeningHistory = new ListeningHistory(config.directory(), io, this::warn);
        io.execute(listeningHistory::load);
        importHistory = new ImportHistory(config.directory(), io, this::warn);
        shortcuts.loadSaved();
        var store = playlists = new PlaylistStore(config.directory(), io, this::warn);
        CompletableFuture.supplyAsync(store::read, io).thenAccept(list -> Minecraft.getInstance().execute(() -> {
            // A startup that failed after this read began has withdrawn the store.
            if (!state.closed && playlists == store) {
                store.loaded(list);
                playlistsReady = true;
            }
        }));
        var newArtwork = artwork = new ArtworkCache();
        acquired.first("artwork", newArtwork::close);
        var newWaveforms = waveforms = new WaveformCache(config.directory());
        acquired.first("waveforms", newWaveforms::close);
        var newPip = pip = new PipController(config.directory(), pipSources);
        acquired.first("PiP", newPip::close);
        var newPlayback = playback = createPlayback();
        acquired.first("playback", newPlayback::close);
        playback.volume(settings().volume);
        applySettings();
        playback.speed(settings().speed);
        playback.shuffle(settings().shuffle);
        playback.repeat(settings().repeat);
        autoplayPending = settings().autoplay;
        var newLibrary = library = new MusicLibrary(config.directory());
        acquired.first("library", newLibrary::close);
        var newDownloads = downloads = createDownloads();
        acquired.first("downloads", newDownloads::close);
        // Read the field on each call: Reset to Defaults replaces the download service.
        lyrics.lookup.captions((track, preferences, generated, token) -> downloads.fetchLyrics(track, preferences, generated, token),
            (track, preferences, generated, token) -> downloads.fetchSourceLyrics(track, preferences, generated, token));
        lyrics.lookup.captionPreferences(settings().lyricsLanguages, settings().lyricsAutoCaptions);
        lyrics.lookup.configure(settings().lyricsEnabled, settings().lyricsOnline);
        try {
            folders.activate(settings().musicDirectory.isBlank() ? config.defaultMusic() : Path.of(settings().musicDirectory));
        } catch (Exception e) {
            warn(Text.failure(e, "error.saved_folder"));
            settings().musicDirectory = "";
            folders.activate(config.defaultMusic());
        }
    }

    /**
     * Startup and Reset to Defaults share this wiring, so a replaced service keeps its lyric hooks.
     */
    private DownloadService createDownloads() {
        var service = new DownloadService(config.directory(), () -> library.requestRescan());
        service.configure(new ToolPaths(settings().ytDlpPath, settings().ffmpegPath, settings().ffprobePath, settings().javascriptPath));
        service.format(settings().downloadFormat);
        service.savedObserver(file -> Minecraft.getInstance().execute(() -> {
            if (!state.closed && settings().lyricsEnabled && settings().lyricsOnline && settings().lyricsDownloadLookup)
                lyrics.lookup.downloaded(file, library.roots(), settings().lyricsLanguages, settings().lyricsAutoCaptions);
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
            if (!state.closed) warn(Text.get("error.folder_busy"));
            return null;
        }
    }

    protected void tick(Minecraft client, boolean allowed) {
        if (waveforms != null && settings() != null && playback != null) {
            var waveformTrack = playback.state().track();
            if (waveformTrack == null && client.gui.screen() instanceof HudPreview preview && preview.previewsProgress() && library != null) {
                var tracks = library.snapshot().tracks();
                if (!tracks.isEmpty()) waveformTrack = tracks.getFirst();
            }
            waveforms.update(waveformTrack, settings().waveformProgress && !state.mediaBusy);
        }
        if (settings() != null && playback != null && settings().lyricsEnabled) {
            var track = playback.state().track();
            lyrics.request(track, settings().lyricsAutomaticLookup);
            if (library != null) {
                var snapshot = library.snapshot();
                if (snapshot.scanning() || state.mediaBusy) lyrics.lookup.tickRetries();
                else
                    lyrics.background(snapshot.tracks(), snapshot.completeRoots(), settings().lyricsOnline && lyrics.libraryLookupRunning());
            }
        }
        allowed &= !state.mediaBusy;
        if (!warning.isEmpty() && System.nanoTime() >= warningUntil) warning = "";
        shortcuts.consume(allowed);
        if (state.resetting || settings() == null || playback == null) return;
        boolean playlistsClean = reconciliation.tick();
        if (listeningHistory != null && !state.mediaBusy) listeningHistory.flush(false);
        if (autoplayPending && !state.mediaBusy && playlistsClean && !library.snapshot().scanning()) {
            autoplayPending = false;
            startPlayback();
        }
        if (!autoplayPending && !state.mediaBusy) recordResume(true);
        sleep.tick(playback, settings().volume);
    }

    /**
     * Called on the client thread only, after explicit confirmation. {@link ResetOperation} owns the phases and what
     * each failure leaves behind; the steps here are the services to stop, clear and rebuild.
     */
    @Override
    public CompletableFuture<Void> resetDefaults() {
        if (!idle())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("reset.darkmusic.busy")));
        return ResetOperation.run(new ResetSteps(), Minecraft.getInstance()::execute, io);
    }

    private final class ResetSteps implements ResetOperation.Steps {
        private Settings prior;
        private String expected;
        private final Cleanup built = new Cleanup();

        @Override
        public Callable<List<String>> begin() {
            state.resetting = true;
            prior = settings();
            var defaultBindings = shortcuts.defaults();
            var roots = new LinkedHashSet<Path>();
            roots.add(library.root());
            for (String root : prior.roots.keySet())
                try {
                    roots.add(Path.of(root));
                } catch (InvalidPathException ignored) {
                }
            var oldPlayback = playback;
            var oldLibrary = library;
            var oldDownloads = downloads;
            var oldAudition = audition;
            audition = null;
            // Each step runs even if one before it fails: the storage task must still stop the captured services.
            new Cleanup()
                .then("cleanup scanner", () -> {
                    if (cleanupScanner != null) cleanupScanner.close();
                    cleanupScanner = null;
                })
                .then("source index", () -> {
                    if (sourceIndex != null) sourceIndex.close();
                    sourceIndex = null;
                })
                .then("pending requests", () -> {
                    cleanupReviews = null;
                    reconciliation.reset();
                    requestedScreen = null;
                    requestedWorld = null;
                    folders.invalidate();
                    autoplayPending = false;
                })
                .then("folder picker", DesktopFolderPicker::cancel)
                .then("playback", oldPlayback::reset)
                .then("feedback", () -> {
                    speedFeedback.clear();
                    volumeFeedback.clear();
                })
                .then("artwork", artwork::close)
                .then("waveforms", () -> {
                    if (waveforms != null) waveforms.close();
                })
                .then("PiP", () -> {
                    if (pip != null) pip.stop();
                }).run();
            return () -> {
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
            };
        }

        @Override
        public void publish(boolean defaults) {
            settingsStore.replace(defaults ? Settings.defaults(prior) : prior);
            var newPlayback = playback = createPlayback();
            built.first("playback", newPlayback::close);
            var newArtwork = artwork = new ArtworkCache();
            built.first("artwork", newArtwork::close);
            var newWaveforms = waveforms = new WaveformCache(config.directory());
            built.first("waveforms", newWaveforms::close);
            var newLibrary = library = new MusicLibrary(config.directory());
            built.first("library", newLibrary::close);
            var newDownloads = downloads = createDownloads();
            built.first("downloads", newDownloads::close);
            playback.volume(settings().volume);
            playback.speed(settings().speed);
            playback.shuffle(settings().shuffle);
            playback.repeat(settings().repeat);
            applySettings();
            if (defaults) {
                warning = "";
                warningUntil = 0;
                shortcuts.restoreDefaults();
            }
            try {
                folders.activate(settings().musicDirectory.isBlank() ? config.defaultMusic() : Path.of(settings().musicDirectory));
            } catch (Exception e) {
                folders.activate(config.defaultMusic());
            }
        }

        @Override
        public void abandon() {
            built.run();
        }

        @Override
        public void release() {
            state.resetting = false;
            save();
            expected = ConfigStore.JSON.toJson(settings());
        }

        @Override
        public void verify() throws IOException {
            if (!Files.readString(config.directory().resolve("config.json")).equals(expected))
                throw new IOException(Text.get("reset.darkmusic.save_failed"));
        }

        @Override
        public boolean closed() {
            return state.closed;
        }

        @Override
        public void warn(String message) {
            DarkMusicClient.this.warn(message);
        }
    }

    /**
     * Minecraft is closing; {@link Shutdown} owns the order. A second call does nothing.
     */
    protected void shutdown() {
        if (state.closed) return;
        Shutdown.run(new Shutdown.Steps() {
            @Override
            public void saveFinal() {
                try {
                    if (settings() != null && playback != null && !state.resetting) recordResume(false);
                } finally {
                    save();
                }
            }

            @Override
            public void close() {
                state.closed = true;
            }

            @Override
            public void cancelWork() {
                new Cleanup().then("folder picker", DesktopFolderPicker::cancel)
                    .then("media operation", () -> {
                        if (mediaToken != null) mediaToken.cancel();
                    })
                    .then("browse pool", browsing::shutdownNow).run();
            }

            @Override
            public void flushHistory() {
                if (listeningHistory != null) listeningHistory.flush(true);
            }

            @Override
            public void drainStorage() {
                io.shutdown();
                try {
                    if (!io.awaitTermination(2, TimeUnit.SECONDS)) io.shutdownNow();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    io.shutdownNow();
                }
            }
        }, new Shutdown.Services(updates, discord, mediaControls, mediaPackage, pip, sourceLinks, cleanupScanner,
            sourceIndex, audition, lyrics, downloads, library, playback, artwork, waveforms));
    }
}
