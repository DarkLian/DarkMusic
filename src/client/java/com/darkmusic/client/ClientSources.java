package com.darkmusic.client;

import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.gui.AudioDeletionScreen;
import com.darkmusic.client.gui.AudioSettingsScreen;
import com.darkmusic.client.gui.BackupScreen;
import com.darkmusic.client.gui.ColorsScreen;
import com.darkmusic.client.gui.DownloadDuplicateScreen;
import com.darkmusic.client.gui.DownloadPathsScreen;
import com.darkmusic.client.gui.DownloadScreen;
import com.darkmusic.client.gui.DownloadToolsScreen;
import com.darkmusic.client.gui.DuplicateComparisonScreen;
import com.darkmusic.client.gui.ExistingDownloadsScreen;
import com.darkmusic.client.gui.HudEditorScreen;
import com.darkmusic.client.gui.IgnoredMatchesScreen;
import com.darkmusic.client.gui.ImportHistoryScreen;
import com.darkmusic.client.gui.LibraryManagerScreen;
import com.darkmusic.client.gui.LyricsScreen;
import com.darkmusic.client.gui.MetadataScreen;
import com.darkmusic.client.gui.MusicFoldersScreen;
import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.gui.Navigation;
import com.darkmusic.client.gui.PipScreen;
import com.darkmusic.client.gui.PlaylistScreen;
import com.darkmusic.client.gui.SettingsScreen;
import com.darkmusic.client.gui.ShortcutKeys;
import com.darkmusic.client.gui.SmartCleanupScreen;
import com.darkmusic.client.gui.SongActionsScreen;
import com.darkmusic.client.gui.TrackPickerScreen;
import com.darkmusic.client.gui.UpNextScreen;
import com.darkmusic.client.gui.UpdateScreen;
import com.darkmusic.client.hud.HudSources;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.AudioDeletion;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.pip.PipController;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.presence.DiscordPresence;
import com.darkmusic.client.systemmedia.MediaControls;
import com.darkmusic.client.systemmedia.MediaControlsPackage;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.update.UpdateService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * What the screens are given: every screen's Sources, each method one forwarding statement to the service or
 * collaborator that owns it. Logic never lives here; a method that needs a condition belongs to a named collaborator,
 * and this class forwards to it. A new screen adds its Sources here and nothing to DarkMusicClient.
 */
public final class ClientSources implements MusicScreen.Sources, SettingsScreen.Sources, UpNextScreen.Sources,
    DownloadScreen.Sources, ImportHistoryScreen.Sources, LibraryManagerScreen.Sources, PipScreen.Sources,
    PlaylistScreen.Sources, SongActionsScreen.Sources, MetadataScreen.Sources, LyricsScreen.Sources,
    AudioSettingsScreen.Sources, BackupScreen.Sources, HudEditorScreen.Sources, MusicFoldersScreen.Sources,
    UpdateScreen.Sources, ColorsScreen.Sources, DownloadToolsScreen.Sources, DownloadPathsScreen.Sources,
    DownloadDuplicateScreen.Sources, ExistingDownloadsScreen.Sources, SmartCleanupScreen.Sources,
    IgnoredMatchesScreen.Sources, DuplicateComparisonScreen.Sources, AudioDeletionScreen.Sources, TrackPickerScreen.Sources {
    private final ClientServices services;
    private final Warnings warnings;
    private final PlaybackCommands commands;
    private final FolderActions folders;
    private final MediaActions media;
    private final BackupActions backups;
    private final OutputActions output;
    private final Shortcuts shortcuts;
    private final ClientReset reset;
    private final Navigation navigation = new ScreenNavigation<>(this);

    ClientSources(ClientServices services, PlaybackCommands commands, FolderActions folders, MediaActions media,
                  BackupActions backups, OutputActions output, Shortcuts shortcuts, ClientReset reset) {
        this.services = services;
        this.warnings = services.warnings();
        this.commands = commands;
        this.folders = folders;
        this.media = media;
        this.backups = backups;
        this.output = output;
        this.shortcuts = shortcuts;
        this.reset = reset;
    }

    @Override
    public Settings settings() {
        return services.settings();
    }

    @Override
    public void save() {
        services.save();
    }

    @Override
    public void applySettings() {
        services.apply();
    }

    @Override
    public Navigation navigation() {
        return navigation;
    }

    @Override
    public String warning() {
        return warnings.current();
    }

    @Override
    public void warn(String text) {
        warnings.warn(text);
    }

    @Override
    public void announce(String text) {
        warnings.announce(text);
    }

    @Override
    public boolean mediaBusy() {
        return services.mediaBusy();
    }

    @Override
    public ExecutorService io() {
        return services.io();
    }

    @Override
    public ExecutorService browsing() {
        return services.browsing();
    }

    @Override
    public ConfigStore config() {
        return services.config();
    }

    @Override
    public HudSources hud() {
        return services.hud();
    }

    @Override
    public PipController pip() {
        return services.pip();
    }

    @Override
    public LyricsService lyrics() {
        return services.lyrics();
    }

    @Override
    public PlaybackController playback() {
        return services.playback();
    }

    @Override
    public MusicLibrary library() {
        return services.library();
    }

    @Override
    public ArtworkCache artwork() {
        return services.artwork();
    }

    @Override
    public ArtworkCache.Cover thumbnail(Track track) {
        return services.thumbnail(track);
    }

    @Override
    public DownloadService downloads() {
        return services.downloads();
    }

    @Override
    public PlaylistStore playlists() {
        return services.playlists();
    }

    @Override
    public boolean playlistsReady() {
        return services.playlistsReady();
    }

    @Override
    public ListeningHistory listeningHistory() {
        return services.listeningHistory();
    }

    @Override
    public ImportHistory importHistory() {
        return services.importHistory();
    }

    @Override
    public UpdateService updates() {
        return services.updates();
    }

    @Override
    public AuditionPlayer audition() {
        return services.audition();
    }

    @Override
    public void audition(AuditionPlayer player) {
        services.audition(player);
    }

    @Override
    public SourceLinks sourceLinks() {
        return services.sourceLinks();
    }

    @Override
    public MediaControls mediaControls() {
        return services.mediaControls();
    }

    @Override
    public MediaControlsPackage mediaPackage() {
        return services.mediaPackage();
    }

    @Override
    public DiscordPresence discord() {
        return services.discord();
    }

    @Override
    public LibrarySourceIndex sourceIndex() {
        return services.sourceIndex();
    }

    @Override
    public DuplicateScanner cleanup() {
        return services.cleanup();
    }

    @Override
    public DuplicateReviewStore cleanupReviews() {
        return services.cleanupReviews();
    }

    @Override
    public ShortcutKeys shortcuts() {
        return shortcuts;
    }

    @Override
    public boolean canEditQueue() {
        return commands.canEditQueue();
    }

    @Override
    public void seekRelative(double seconds) {
        commands.seekRelative(seconds);
    }

    @Override
    public void speed(double value) {
        commands.speed(value);
    }

    @Override
    public void volume(double value) {
        commands.volume(value);
    }

    @Override
    public void shuffle(boolean on) {
        commands.shuffle(on);
    }

    @Override
    public void repeat(Repeat mode) {
        commands.repeat(mode);
    }

    @Override
    public void toggleMute() {
        commands.toggleMute();
    }

    @Override
    public List<Track> collection(String mode, String group) {
        return commands.collection(mode, group);
    }

    @Override
    public boolean resumeLast(boolean paused) {
        return commands.resumeLast(paused);
    }

    @Override
    public boolean resumes(Track track) {
        return commands.resumes(track);
    }

    @Override
    public void openFolder(Path directory) {
        folders.open(directory);
    }

    @Override
    public void openMusicFolder() {
        folders.open();
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
    public boolean importing() {
        return media.importing();
    }

    @Override
    public String importDestination() {
        return media.importDestination();
    }

    @Override
    public void importAudio(List<Path> paths, String folder, String playlistId) {
        media.importAudio(paths, folder, playlistId);
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
    public String outputDeviceId() {
        return output.outputDeviceId();
    }

    @Override
    public LoudnessCache.AnalysisProgress loudnessAnalysis() {
        return output.loudnessAnalysis();
    }

    @Override
    public void analyzeLibraryLoudness() {
        output.analyzeLibraryLoudness();
    }

    @Override
    public CompletableFuture<Boolean> selectOutputDevice(String id) {
        return output.selectOutputDevice(id);
    }

    @Override
    public CompletableFuture<BackupArchive.Restore> previewRestore(BackupArchive.Data data,
                                                                   BackupArchive.Selection selection) {
        return backups.previewRestore(data, selection);
    }

    @Override
    public CompletableFuture<BackupArchive.Data> readBackup(Path source) {
        return backups.readBackup(source);
    }

    @Override
    public CompletableFuture<Void> exportBackup(Path destination) {
        return backups.exportBackup(destination);
    }

    @Override
    public CompletableFuture<Void> restoreBackup(BackupArchive.Data data, BackupArchive.Selection selection) {
        return backups.restoreBackup(data, selection);
    }

    @Override
    public CompletableFuture<Void> resetDefaults() {
        return reset.resetDefaults();
    }
}
