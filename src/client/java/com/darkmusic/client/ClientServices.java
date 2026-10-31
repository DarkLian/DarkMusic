package com.darkmusic.client;

import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.OutputDevices;
import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.SleepTimer;
import com.darkmusic.client.audio.TrackWaveform;
import com.darkmusic.client.audio.WaveformCache;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.hud.HudSources;
import com.darkmusic.client.hud.SpeedFeedback;
import com.darkmusic.client.hud.VolumeFeedback;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.ListeningHistory;
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
import com.darkmusic.client.tools.ToolPaths;
import com.darkmusic.client.update.UpdateService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Every service the client runs, and the only holder of the ones Reset to Defaults replaces. Read them through the
 * getters on each use rather than keeping them: reset and restore publish replacements here, and the HUD, PiP and
 * reconciliation adapters read the current one on every call. Services are built and replaced on the client thread.
 */
public final class ClientServices implements PlaybackCommands.Inputs, PlaylistActions.Inputs, DownloadActions.Inputs, MediaActions.Inputs, FolderActions.Inputs, OutputActions.Inputs, BackupActions.Inputs, LyricsActions.Inputs {
    // Shutdown, reset and busy flags, and the checks built from them.
    final ClientState state = new ClientState();
    private final Warnings warnings = new Warnings(state);
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-storage").factory());
    private final ExecutorService browsing = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("darkmusic-browse").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final ConfigStore config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("darkmusic"), io, warnings::warn);
    private final SpeedFeedback speedFeedback = new SpeedFeedback();
    private final VolumeFeedback volumeFeedback = new VolumeFeedback();
    // Owns the live settings and the rule for writing them; load, reset and restore replace them through it.
    private final SettingsStore settingsStore = new SettingsStore(config::save, () -> state.alive() && !state.restoring, this::apply);
    // Reads the fields on every call: reset and restore replace the services behind them.
    private final HudSources hud = new HudSources() {
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
    private MusicLibrary library;
    private PlaybackController playback;
    private JavaSoundBackend backend;
    private ListeningHistory listeningHistory;
    private DuplicateScanner cleanupScanner;
    private DuplicateReviewStore cleanupReviews;
    private LibrarySourceIndex sourceIndex;
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
            return state.idle();
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
    }, io, task -> Minecraft.getInstance().execute(task), warnings::warn);
    private ArtworkCache artwork;
    private WaveformCache waveforms;
    private PipController pip;
    private PlaylistStore playlists;
    private DownloadService downloads;
    private AuditionPlayer audition;
    private UpdateService updates;
    private final DiscordPresence discord = new DiscordPresence();
    private final MediaControlsPackage mediaPackage = new MediaControlsPackage(config.directory());
    private final MediaControls mediaControls;
    private final LyricsService lyrics;
    private boolean playlistsReady;
    private ImportHistory importHistory;
    private final SourceLinks sourceLinks;
    private final SleepTimer sleep;

    /**
     * @param mediaKeys runs a media key, headset button or Windows media flyout button, on the client thread
     */
    ClientServices(Consumer<MediaControls.Action> mediaKeys) {
        mediaControls = new MediaControls(config.directory().resolve("media-native"),
            action -> Minecraft.getInstance().execute(() -> mediaKeys.accept(action)));
        lyrics = new LyricsService(config.directory().resolve("lyrics"));
        sourceLinks = new SourceLinks();
        sleep = new SleepTimer();
    }

    public Settings settings() {
        return settingsStore.settings();
    }

    public void save() {
        settingsStore.save();
    }

    public void update(Consumer<Settings> edit) {
        settingsStore.update(edit);
    }

    /**
     * For checks and captures that start from known settings; they save or reapply afterwards themselves.
     */
    public void replaceSettings(Settings settings) {
        settingsStore.replace(settings);
    }

    SettingsStore settingsStore() {
        return settingsStore;
    }

    public Warnings warnings() {
        return warnings;
    }

    public void warn(String text) {
        warnings.warn(text);
    }

    public boolean mediaBusy() {
        return state.mediaBusy;
    }

    public ExecutorService io() {
        return io;
    }

    public ExecutorService browsing() {
        return browsing;
    }

    public ConfigStore config() {
        return config;
    }

    public SpeedFeedback speedFeedback() {
        return speedFeedback;
    }

    public VolumeFeedback volumeFeedback() {
        return volumeFeedback;
    }

    public HudSources hud() {
        return hud;
    }

    PipSources pipSources() {
        return pipSources;
    }

    LibraryReconciliation reconciliation() {
        return reconciliation;
    }

    /**
     * Null until startup creates the library, and likewise for the player, histories, playlists and the services
     * Reset to Defaults replaces.
     */
    public MusicLibrary library() {
        return library;
    }

    public PlaybackController playback() {
        return playback;
    }

    public JavaSoundBackend backend() {
        return backend;
    }

    public ListeningHistory listeningHistory() {
        return listeningHistory;
    }

    public ImportHistory importHistory() {
        return importHistory;
    }

    public ArtworkCache artwork() {
        return artwork;
    }

    public ArtworkCache.Cover thumbnail(Track track) {
        return artwork.thumbnail(track);
    }

    /**
     * For the smoke check that closes the live artwork cache to test the fallback, then needs a working one again.
     */
    public void reopenArtwork() {
        artwork = new ArtworkCache();
    }

    public WaveformCache waveforms() {
        return waveforms;
    }

    public PipController pip() {
        return pip;
    }

    public PlaylistStore playlists() {
        return playlists;
    }

    public boolean playlistsReady() {
        return playlistsReady;
    }

    public DownloadService downloads() {
        return downloads;
    }

    public AuditionPlayer audition() {
        return audition;
    }

    void audition(AuditionPlayer player) {
        audition = player;
    }

    /**
     * Reset to Defaults takes the audition away before it stops the services, so nothing starts it again meanwhile.
     */
    AuditionPlayer takeAudition() {
        var old = audition;
        audition = null;
        return old;
    }

    public UpdateService updates() {
        return updates;
    }

    public DiscordPresence discord() {
        return discord;
    }

    public MediaControlsPackage mediaPackage() {
        return mediaPackage;
    }

    public MediaControls mediaControls() {
        return mediaControls;
    }

    public LyricsService lyrics() {
        return lyrics;
    }

    public SourceLinks sourceLinks() {
        return sourceLinks;
    }

    public SleepTimer sleep() {
        return sleep;
    }

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

    // The raw fields, for code that must not create a scanner, index or review store just to notify or close it.

    public DuplicateScanner openCleanup() {
        return cleanupScanner;
    }

    public DuplicateReviewStore openCleanupReviews() {
        return cleanupReviews;
    }

    public LibrarySourceIndex openSourceIndex() {
        return sourceIndex;
    }

    void closeCleanup() {
        if (cleanupScanner != null) cleanupScanner.close();
        cleanupScanner = null;
    }

    void closeSourceIndex() {
        if (sourceIndex != null) sourceIndex.close();
        sourceIndex = null;
    }

    void forgetCleanupReviews() {
        cleanupReviews = null;
    }

    void createUpdates() {
        updates = new UpdateService(config.directory(), this::settings, warnings::warn);
    }

    private PlaybackController createPlayback() {
        backend = new JavaSoundBackend(Minecraft.getInstance()::execute,
            PcmDecoder::openPrecise,
            new OutputDevices(settings().outputDevice, true, warnings::warn));
        backend.listening((track, session, seconds) -> {
            if (listeningHistory != null) listeningHistory.heard(track, session, seconds);
        });
        var controller = new PlaybackController(backend, warnings::warn);
        // Only the play-ranked lists freeze their order while they play; Recently Added and Never Played do not rank by plays.
        controller.onSourceChanged((source, queue) -> listeningHistory.playbackSource(source.rootId(),
            source.mode().equals("History") && Set.of("recent", "most").contains(source.group()) ? source.group() : null,
            queue, library.snapshot().tracks()));
        return controller;
    }

    /**
     * Pushes the current settings into the running services, in this order: normalize the values that are derived
     * from others (profile and environment names), then theme, external codec paths, download format, lyrics,
     * playback DSP and spectrum. Does not write: screens call it while a control moves and {@link #save()} (apply,
     * then write) when the change is committed. Playback compares and copies what it needs, so repeating it is cheap.
     */
    public void apply() {
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

    /**
     * The first startup step, before the saved shortcuts load: the listening and import histories.
     */
    void buildHistories() {
        listeningHistory = new ListeningHistory(config.directory(), io, warnings::warn);
        io.execute(listeningHistory::load);
        importHistory = new ImportHistory(config.directory(), io, warnings::warn);
    }

    /**
     * The rest of startup, in dependency order: playlists, PiP, the replaceable services and lyric lookup. Each
     * service is registered with acquired as soon as it exists, so a failure further on can close it.
     */
    void buildServices(Cleanup acquired) {
        var store = playlists = new PlaylistStore(config.directory(), io, warnings::warn);
        CompletableFuture.supplyAsync(store::read, io).thenAccept(list -> Minecraft.getInstance().execute(() -> {
            // A startup that failed after this read began has withdrawn the store.
            if (!state.closed && playlists == store) {
                store.loaded(list);
                playlistsReady = true;
            }
        }));
        var newPip = pip = new PipController(config.directory(), pipSources);
        acquired.first("PiP", newPip::close);
        buildReplaceable(acquired);
        // Read the field on each call: Reset to Defaults replaces the download service.
        lyrics.lookup.captions((track, preferences, generated, token) -> downloads.fetchLyrics(track, preferences, generated, token),
            (track, preferences, generated, token) -> downloads.fetchSourceLyrics(track, preferences, generated, token));
        lyrics.lookup.captionPreferences(settings().lyricsLanguages, settings().lyricsAutoCaptions);
        lyrics.lookup.configure(settings().lyricsEnabled, settings().lyricsOnline);
    }

    /**
     * Removes the settings and every partly built service, back to the state before the config loaded.
     */
    void withdraw() {
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

    /**
     * Builds the services that Reset to Defaults replaces, for startup and reset alike, and configures them from the
     * current settings. Each is installed in its field at once, so the HUD, PiP and callbacks that read the fields see
     * the new one, and registered with acquired so a failure further on closes it. The settings are applied before the
     * library exists and before folders are activated, so scans start with the configured codec paths. What differs
     * between startup and reset stays with the caller.
     */
    void buildReplaceable(Cleanup acquired) {
        var newArtwork = artwork = new ArtworkCache();
        acquired.first("artwork", newArtwork::close);
        var newWaveforms = waveforms = new WaveformCache(config.directory());
        acquired.first("waveforms", newWaveforms::close);
        var newPlayback = playback = createPlayback();
        acquired.first("playback", newPlayback::close);
        playback.volume(settings().volume);
        playback.speed(settings().speed);
        playback.shuffle(settings().shuffle);
        playback.repeat(settings().repeat);
        apply();
        var newLibrary = library = new MusicLibrary(config.directory());
        acquired.first("library", newLibrary::close);
        var newDownloads = downloads = createDownloads();
        acquired.first("downloads", newDownloads::close);
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
            if (!state.closed) warnings.warn(Text.get("error.folder_busy"));
            return null;
        }
    }
}
