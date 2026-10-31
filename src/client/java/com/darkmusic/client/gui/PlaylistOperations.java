package com.darkmusic.client.gui;

import com.darkmusic.client.library.Track;

import java.util.List;
import java.util.Set;

/**
 * Changes to playlists and favorites. Each runs only once the playlists have loaded and while no media operation owns
 * the files, and returns whether it ran.
 */
public interface PlaylistOperations {
    /**
     * The new playlist's id, or an empty string when it was not created.
     */
    String create(String name);

    boolean rename(String id, String name);

    boolean delete(String id);

    boolean addAll(String id, List<Track> tracks);

    boolean removeAll(String id, Set<String> keys);

    boolean moveBlock(String id, int start, int count, int insertion);

    boolean toggleFavorite(Track track);
}
