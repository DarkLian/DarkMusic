package com.darkmusic.client.gui;

import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.library.Track;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Consumer;

/**
 * The screens the player and Settings open. The composition root builds each destination with that destination's own
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
}
