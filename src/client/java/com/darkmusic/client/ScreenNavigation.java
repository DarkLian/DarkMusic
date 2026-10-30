package com.darkmusic.client;

import com.darkmusic.client.cleanup.DuplicateResult;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.gui.AudioDeletionScreen;
import com.darkmusic.client.gui.AudioSettingsScreen;
import com.darkmusic.client.gui.BackupScreen;
import com.darkmusic.client.gui.ColorsScreen;
import com.darkmusic.client.gui.DownloadDiagnosticScreen;
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
import com.darkmusic.client.gui.LyricsSettingsScreen;
import com.darkmusic.client.gui.MetadataScreen;
import com.darkmusic.client.gui.MusicFoldersScreen;
import com.darkmusic.client.gui.Navigation;
import com.darkmusic.client.gui.PipScreen;
import com.darkmusic.client.gui.PlaylistScreen;
import com.darkmusic.client.gui.SettingsScreen;
import com.darkmusic.client.gui.SmartCleanupScreen;
import com.darkmusic.client.gui.SongActionsScreen;
import com.darkmusic.client.gui.SoundEnvironmentScreen;
import com.darkmusic.client.gui.TrackPickerScreen;
import com.darkmusic.client.gui.UpNextScreen;
import com.darkmusic.client.gui.UpdateScreen;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.tools.DownloadDiagnostics;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.function.Consumer;

/**
 * Builds each destination with the sources it declares. The type bound lists exactly those sources, so this class
 * can build every destination without knowing the client that supplies them.
 */
final class ScreenNavigation<S extends SettingsScreen.Sources & UpNextScreen.Sources & DownloadScreen.Sources
    & ImportHistoryScreen.Sources & LibraryManagerScreen.Sources & PipScreen.Sources & PlaylistScreen.Sources
    & SongActionsScreen.Sources & MetadataScreen.Sources & LyricsScreen.Sources & AudioSettingsScreen.Sources
    & BackupScreen.Sources & HudEditorScreen.Sources & MusicFoldersScreen.Sources & UpdateScreen.Sources
    & ColorsScreen.Sources & DownloadToolsScreen.Sources & DownloadPathsScreen.Sources & DownloadDuplicateScreen.Sources
    & ExistingDownloadsScreen.Sources & SmartCleanupScreen.Sources & IgnoredMatchesScreen.Sources
    & DuplicateComparisonScreen.Sources & AudioDeletionScreen.Sources & TrackPickerScreen.Sources> implements Navigation {
    private final S app;

    ScreenNavigation(S app) {
        this.app = app;
    }

    @Override
    public Screen settings(Screen parent) {
        return new SettingsScreen(app, parent);
    }

    @Override
    public Screen upNext(Screen parent) {
        return new UpNextScreen(app, parent);
    }

    @Override
    public Screen downloads(Screen parent) {
        return new DownloadScreen(app, parent);
    }

    @Override
    public Screen importHistory(Screen parent) {
        return new ImportHistoryScreen(app, parent);
    }

    @Override
    public Screen libraryManager(Screen parent) {
        return new LibraryManagerScreen(app, parent);
    }

    @Override
    public Screen pip(Screen parent) {
        return new PipScreen(app, parent);
    }

    @Override
    public Screen playlists(Screen parent) {
        return new PlaylistScreen(app, parent);
    }

    @Override
    public Screen songActions(Screen parent, Track track) {
        return new SongActionsScreen(app, parent, track);
    }

    @Override
    public Screen metadata(Screen parent, Track track, Consumer<Track> saved) {
        return new MetadataScreen(app, parent, track, saved);
    }

    @Override
    public Screen lyrics(Screen parent, Track track) {
        return new LyricsScreen(app, parent, track);
    }

    @Override
    public Screen audioSettings(Screen parent) {
        return new AudioSettingsScreen(app, parent);
    }

    @Override
    public Screen soundEnvironment(Screen parent) {
        return new SoundEnvironmentScreen(app, parent);
    }

    @Override
    public Screen lyricsSettings(Screen parent) {
        return new LyricsSettingsScreen(app, parent);
    }

    @Override
    public Screen backup(Screen parent) {
        return new BackupScreen(app, parent);
    }

    @Override
    public Screen hudEditor(Screen parent) {
        return new HudEditorScreen(app, parent);
    }

    @Override
    public Screen hudEditor(Screen parent, HudEditorScreen.Element selected) {
        return new HudEditorScreen(app, parent, selected);
    }

    @Override
    public Screen musicFolders(Screen parent) {
        return new MusicFoldersScreen(app, parent);
    }

    @Override
    public Screen updates(Screen parent) {
        return new UpdateScreen(app, parent);
    }

    @Override
    public Screen colors(Screen parent, boolean spectrum) {
        return new ColorsScreen(app, parent, spectrum);
    }

    @Override
    public Screen colors(Screen parent, VisualColors target, String titleKey) {
        return new ColorsScreen(app, parent, target, titleKey);
    }

    @Override
    public Screen colors(Screen parent, VisualColors target, String titleKey, ColorsScreen.Preview preview, String previewTitle) {
        return new ColorsScreen(app, parent, target, titleKey, preview, previewTitle);
    }

    @Override
    public Screen downloadTools(Screen parent) {
        return new DownloadToolsScreen(app, parent);
    }

    @Override
    public Screen downloadPaths(Screen parent) {
        return new DownloadPathsScreen(app, parent);
    }

    @Override
    public Screen downloadDiagnostic(Screen parent, DownloadDiagnostics.Diagnostic diagnostic, Runnable retry) {
        return new DownloadDiagnosticScreen(app, parent, diagnostic, retry);
    }

    @Override
    public Screen downloadDuplicate(Screen parent, DownloadService.DuplicateChoice choice) {
        return new DownloadDuplicateScreen(app, parent, choice);
    }

    @Override
    public Screen existingDownloads(Screen parent, DownloadModels.Entry entry) {
        return new ExistingDownloadsScreen(app, parent, entry);
    }

    @Override
    public Screen smartCleanup(Screen parent) {
        return new SmartCleanupScreen(app, parent);
    }

    @Override
    public Screen ignoredMatches(Screen parent) {
        return new IgnoredMatchesScreen(app, parent);
    }

    @Override
    public Screen duplicateComparison(Screen parent, DuplicateResult pair) {
        return new DuplicateComparisonScreen(app, parent, pair);
    }

    @Override
    public Screen audioDeletion(Screen parent, List<Track> tracks, Screen completed) {
        return new AudioDeletionScreen(app, parent, tracks, completed);
    }

    @Override
    public Screen trackPicker(Screen parent, String playlistId, Consumer<List<Track>> accept) {
        return new TrackPickerScreen(app, parent, playlistId, accept);
    }
}
