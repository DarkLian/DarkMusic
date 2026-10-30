package com.darkmusic.client.gui;

import com.darkmusic.client.cleanup.DuplicateResult;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.tools.DownloadDiagnostics;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.function.Consumer;

/**
 * The screens that other screens open. The composition root builds each destination with that destination's own
 * sources, so the screen that navigates names where to go and what it is about (a song, a colour set), not the
 * services the destination needs. The parent is the destination's back target.
 * <p>
 * Adding a destination: give the screen its Sources, add a method here, and build it in the root's navigation.
 * No other screen's Sources changes.
 */
public interface Navigation {
    Screen settings(Screen parent);

    Screen upNext(Screen parent);

    Screen downloads(Screen parent);

    Screen importHistory(Screen parent);

    Screen libraryManager(Screen parent);

    Screen pip(Screen parent);

    Screen playlists(Screen parent);

    Screen songActions(Screen parent, Track track);

    /**
     * Tag editing for one song; saved receives the edited song.
     */
    Screen metadata(Screen parent, Track track, Consumer<Track> saved);

    Screen lyrics(Screen parent, Track track);

    Screen audioSettings(Screen parent);

    Screen soundEnvironment(Screen parent);

    Screen lyricsSettings(Screen parent);

    Screen backup(Screen parent);

    Screen hudEditor(Screen parent);

    Screen hudEditor(Screen parent, HudEditorScreen.Element selected);

    Screen musicFolders(Screen parent);

    Screen updates(Screen parent);

    /**
     * The progress-bar or spectrum colours.
     */
    Screen colors(Screen parent, boolean spectrum);

    Screen colors(Screen parent, VisualColors target, String titleKey);

    /**
     * A colour set edited beside a preview of what it colours.
     */
    Screen colors(Screen parent, VisualColors target, String titleKey, ColorsScreen.Preview preview, String previewTitle);

    Screen downloadTools(Screen parent);

    Screen downloadPaths(Screen parent);

    /**
     * One failed tool check; retry runs the check again.
     */
    Screen downloadDiagnostic(Screen parent, DownloadDiagnostics.Diagnostic diagnostic, Runnable retry);

    Screen downloadDuplicate(Screen parent, DownloadService.DuplicateChoice choice);

    Screen existingDownloads(Screen parent, DownloadModels.Entry entry);

    Screen smartCleanup(Screen parent);

    Screen ignoredMatches(Screen parent);

    Screen duplicateComparison(Screen parent, DuplicateResult pair);

    /**
     * Deleting songs from disk; completed is shown when the deletion finishes.
     */
    Screen audioDeletion(Screen parent, List<Track> tracks, Screen completed);

    /**
     * Choosing songs to add to a playlist; accept receives the chosen songs.
     */
    Screen trackPicker(Screen parent, String playlistId, Consumer<List<Track>> accept);
}
