package com.darkmusic.client;

import com.darkmusic.client.audio.ExternalAudio;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.audio.OutputDevices;
import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.WaveformCache;
import com.darkmusic.client.cleanup.DuplicateResult;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.command.LocalCommands;
import com.darkmusic.client.config.BackupArchive;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.ResetStorage;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.gui.DesktopFolderPicker;
import com.darkmusic.client.gui.HudEditorScreen;
import com.darkmusic.client.gui.LyricsScreen;
import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.gui.PipScreen;
import com.darkmusic.client.gui.UiTheme;
import com.darkmusic.client.hud.DownloadHud;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.hud.SpeedFeedback;
import com.darkmusic.client.hud.VolumeFeedback;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.pip.PipController;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.text.Text;
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
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DarkMusicClient implements ClientModInitializer {
    public static DarkMusicClient INSTANCE;
    public final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-storage").factory());
    public final ExecutorService browsing = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("darkmusic-browse").factory(), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    public final ConfigStore config = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("darkmusic"), io, this::warn);
    public final SpeedFeedback speedFeedback = new SpeedFeedback();
    public final VolumeFeedback volumeFeedback = new VolumeFeedback();
    public volatile Settings settings;
    public volatile String warning = "";
    public final Shortcuts shortcuts = new Shortcuts(this);
    public final FolderActions folders = new FolderActions(this);
    public final MediaActions media = new MediaActions(this);
    public MusicLibrary library;
    public PlaybackController playback;
    private JavaSoundBackend backend;
    public ListeningHistory listeningHistory;
    boolean outputChanging;
    DuplicateScanner cleanupScanner;
    DuplicateReviewStore cleanupReviews;
    LibrarySourceIndex sourceIndex;
    private long sourceEvidenceRevision = -1;
    private java.util.List<DuplicateResult> sourceEvidence = java.util.List.of();

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
        controller.onSourceChanged((source, queue) -> listeningHistory.playbackSource(source.rootId(), source.mode().equals("History") ? source.group() : null,
            queue, library.snapshot().tracks()));
        return controller;
    }

    public String outputDeviceId() {
        return backend == null ? "" : backend.outputDeviceId();
    }

    public LoudnessCache.AnalysisProgress loudnessAnalysis() {
        return backend == null ? LoudnessCache.AnalysisProgress.IDLE : backend.analysisProgress();
    }

    public void analyzeLibraryLoudness() {
        if (backend == null || library == null || closed || resetting) return;
        if (backend.analysisProgress().running()) backend.cancelAnalysis();
        else if (!library.snapshot().scanning()) backend.analyzeLibrary(library.snapshot().tracks(), settings.audio);
    }

    public CompletableFuture<Boolean> selectOutputDevice(String id) {
        var result = new CompletableFuture<Boolean>();
        if (backend == null || mediaBusy || resetting || outputChanging) {
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
        if (closed || mediaBusy || resetting || outputChanging || !playlistsReady || !listeningHistory.ready())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("library.darkmusic.busy")));
        var keys = new java.util.LinkedHashMap<String, String>();
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
                var contents = new java.util.LinkedHashMap<String, String>();
                if (restored.selection().settings() || bindings)
                    contents.put("config.json", ConfigStore.JSON.toJson(restored.settings()));
                if (restored.selection().playlists()) {
                    contents.put("playlists.json", ConfigStore.JSON.toJson(restored.playlists()));
                    contents.put("playlist-retained.json", ConfigStore.JSON.toJson(restored.retained()));
                }
                if (restored.selection().history())
                    contents.put("listening-history.json", ListeningHistory.json(restored.history()));

                if (bindings) contents.put("options.txt", config.shortcutsContents(keys));
                BackupArchive.install(config.directory(), contents);
                if (contents.containsKey("config.json")) config.committedSnapshot(contents.get("config.json"));
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, io).whenComplete((unused, failure) -> Minecraft.getInstance().execute(() -> {
            try {
                if (failure != null) {
                    finished.completeExceptionally(new java.io.IOException(Text.get("backup.failed"), failure));
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
                mediaBusy = restoring = BackupArchive.recoveryPending(config.directory());
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
    public final LyricsService lyrics = new LyricsService(config.directory().resolve("lyrics"));
    public boolean playlistsReady;
    // Set while an import, edit, deletion or backup restore owns the music files; menus read it to disable actions.
    boolean mediaBusy;

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
        return !closed && !resetting && !mediaBusy && !outputChanging && playback != null;
    }

    private volatile boolean closed;
    private boolean resetting;

    boolean resetting() {
        return resetting;
    }

    boolean closed() {
        return closed;
    }

    private enum RequestedScreen {PLAYER, HUD, LYRICS, PIP}

    private RequestedScreen requestedScreen;
    private Object requestedWorld;
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

    public void seekRelative(double seconds) {
        if (!resetting && !mediaBusy && playback != null) playback.seekRelative(seconds);
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

    public void toggleMute() {
        volume(settings.volume > 0 ? 0 : settings.lastAudibleVolume);
        volumeFeedback.show(settings.volume);
    }

    public void save() {
        if (!closed && !resetting && !restoring && settings != null) {
            configureVisualizer();
            config.save(settings);
        }
    }

    public void configureVisualizer() {
        if (settings != null) {
            UiTheme.apply(settings);
            ExternalAudio.configure(() -> new ExternalAudio.Paths(config.directory(), settings.ffmpegPath, settings.ffprobePath));
            if (downloads != null) downloads.format(settings.downloadFormat);
            if (downloads != null)
                downloads.lyrics(settings.lyricsEnabled, settings.lyricsLanguages);
            lyrics.enabled(settings.lyricsEnabled);
            lyrics.lookup.configure(settings.lyricsEnabled, settings.lyricsOnline);
            lyrics.lookup.captionPreferences(settings.lyricsLanguages, settings.lyricsAutoCaptions);
            if (downloads != null) downloads.autoCaptions(settings.lyricsAutoCaptions);
            if (playback != null) playback.configure(settings.audio, settings.environment);
        }
        if (playback != null && playback.spectrum() != null) {
            playback.spectrum().configure(settings.hudVisualizer || settings.hudStyle == Settings.HudStyle.CIRCULAR && settings.circularIconPulse, settings.visualizerBars, settings.visualizerSensitivity, settings.visualizerSmoothing);
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

    private String lyricsCommandProblem() {
        return LocalCommands.lyricsProblem(
            !closed && !resetting && !mediaBusy && settings != null && library != null && playback != null,
            settings != null && settings.lyricsEnabled, playback == null ? null : playback.state());
    }

    private void openRequestedScreen(Minecraft client) {
        if (requestedScreen == null) return;
        if (closed || resetting || client.level != requestedWorld || System.nanoTime() > requestUntil || client.player != null && !client.player.isAlive()) {
            requestedScreen = null;
            return;
        }
        var screen = client.gui.screen();
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
        else if (requested == RequestedScreen.HUD)
            client.gui.setScreen(new HudEditorScreen(this, screen));
        else open();
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        updates = new UpdateService(this);
        LocalCommands.register(this);
        NowPlayingHud.register(this);
        LyricsHud.register(this);
        VolumeFeedback.register(this);
        SpeedFeedback.register(this);
        DownloadHud.register(this);
        PipController.register(this);
        shortcuts.register();
        CompletableFuture.supplyAsync(config::load, io).thenAccept(loaded -> Minecraft.getInstance().execute(() -> {
            if (!closed) {
                settings = loaded;
                ready();
                save();
            }
        })).exceptionally(failure -> {
            org.slf4j.LoggerFactory.getLogger("darkmusic").error("Could not recover or load user data", failure);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause instanceof BackupArchive.RecoveryConflict
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
            boolean allowed = !resetting && settings != null && client.gui.screen() == null && client.gui.overlay() == null && client.player != null && client.player.isAlive();
            boolean pressed = false;
            while (shortcuts.open.consumeClick()) pressed = true;
            if (allowed && pressed && !shortcuts.conflicts(shortcuts.open)) open();
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
        pip = new PipController(this);
        playback = createPlayback();
        playback.volume(settings.volume);
        configureVisualizer();
        playback.speed(settings.speed);
        playback.shuffle(settings.shuffle);
        playback.repeat(settings.repeat);
        autoplayPending = settings.autoplay;
        library = new MusicLibrary(config.directory());
        downloads = new DownloadService(config.directory(), () -> library.requestRescan());
        downloads.configure(new DownloadModels.Tools(settings.ytDlpPath, settings.ffmpegPath, settings.ffprobePath, settings.javascriptPath));
        downloads.format(settings.downloadFormat);
        lyrics.lookup.captions(downloads::fetchLyrics, downloads::fetchSourceLyrics);
        lyrics.lookup.captionPreferences(settings.lyricsLanguages, settings.lyricsAutoCaptions);
        downloads.savedObserver(file -> Minecraft.getInstance().execute(() -> {
            if (!closed && settings.lyricsEnabled && settings.lyricsOnline && settings.lyricsDownloadLookup)
                lyrics.lookup.downloaded(file, library.roots(), settings.lyricsLanguages, settings.lyricsAutoCaptions);
        }));
        lyrics.lookup.configure(settings.lyricsEnabled, settings.lyricsOnline);
        downloads.autoCaptions(settings.lyricsAutoCaptions);
        downloads.lyrics(settings.lyricsEnabled, settings.lyricsLanguages);
        try {
            folders.activate(settings.musicDirectory.isBlank() ? config.defaultMusic() : java.nio.file.Path.of(settings.musicDirectory));
        } catch (Exception e) {
            warn(Text.failure(e, "error.saved_folder"));
            settings.musicDirectory = "";
            folders.activate(config.defaultMusic());
        }
    }

    public java.util.concurrent.Future<?> browse(Runnable task) {
        try {
            ((java.util.concurrent.ThreadPoolExecutor) browsing).purge();
            return browsing.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
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
            java.util.Set<PlaylistStore.Entry> missing;
            try {
                var all = new java.util.HashSet<PlaylistStore.Entry>();
                for (var entry : roots.entrySet())
                    if (snapshot.completeRoots().contains(entry.getValue()))
                        all.addAll(PlaylistStore.missing(entry.getKey(), entry.getValue(), entries, snapshot.tracks()));
                missing = all;
            } catch (Exception e) {
                missing = java.util.Set.of();
                warn(Text.failure(e, "error.playlist_scan"));
            }
            var result = missing;
            Minecraft.getInstance().execute(() -> {
                playlistCleanupPending = false;
                if (closed || resetting || mediaBusy || playlists != store || store.revision() != revision
                    || !roots.equals(library.roots())) return;
                var current = library.snapshot();
                var present = store.index(current.tracks());
                store.removeMissing(result.stream().filter(entry -> current.completeRoots().contains(entry.rootId()) && !present.containsKey(entry.key())).collect(java.util.stream.Collectors.toSet()));
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
            if (waveformTrack == null && client.gui.screen() instanceof HudPreview preview && preview.previewsProgress() && library != null && !library.snapshot().tracks().isEmpty())
                waveformTrack = library.snapshot().tracks().getFirst();
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
                new java.util.HashSet<>(library.roots().values()), queuedLibrary.completeRoots(), queuedLibrary.unreadableScopes());
            if (!queuedLibrary.scanning()) {
                var registered = new java.util.HashSet<>(library.roots().values());
                var cleanupRoots = new java.util.HashSet<>(queuedLibrary.completeRoots());
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
                    new java.util.HashSet<>(library.roots().values()), queuedLibrary.completeRoots());
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
        if (autoplayPending && !mediaBusy && playlistsClean && !library.snapshot().scanning()) {
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
        sourceEvidence = java.util.List.of();
        requestedScreen = null;
        requestedWorld = null;
        folders.invalidate();
        autoplayPending = false;
        DesktopFolderPicker.cancel();
        var prior = settings;
        var defaultBindings = shortcuts.defaults();
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
                    throw new java.io.IOException(Text.get("reset.darkmusic.waiting"));
                var retained = ResetStorage.clear(config.directory(), roots);
                importHistory.reset();
                config.resetShortcuts(defaultBindings);
                return retained;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
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
            downloads = new DownloadService(config.directory(), () -> library.requestRescan());
            downloads.configure(new DownloadModels.Tools(settings.ytDlpPath, settings.ffmpegPath, settings.ffprobePath, settings.javascriptPath));
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
                folders.activate(settings.musicDirectory.isBlank() ? config.defaultMusic() : java.nio.file.Path.of(settings.musicDirectory));
            } catch (Exception e) {
                folders.activate(config.defaultMusic());
            }
            save();
            if (failure != null) finished.completeExceptionally(failure);
            else if (!retained.isEmpty()) {
                String message = Text.get("reset.darkmusic.retained");
                warn(message);
                finished.completeExceptionally(new java.io.IOException(message));
            } else {
                String expected = ConfigStore.JSON.toJson(settings);
                CompletableFuture.runAsync(() -> {
                    try {
                        if (!java.nio.file.Files.readString(config.directory().resolve("config.json")).equals(expected))
                            throw new java.io.IOException(Text.get("reset.darkmusic.save_failed"));
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
