package com.darkmusic.client;

import com.darkmusic.client.gui.PlaylistOperations;
import com.darkmusic.client.library.Track;

import java.util.List;
import java.util.Set;

/**
 * Playlist and favorite changes from the menus. Each runs only once the playlists have loaded and while no media
 * operation owns the files: an import, edit, deletion or restore reconciles the playlists itself. Client thread.
 */
final class PlaylistActions implements PlaylistOperations {
    private final ClientServices services;

    PlaylistActions(ClientServices services) {
        this.services = services;
    }

    private boolean ready() {
        return services.playlistsReady() && !services.mediaBusy() && services.playlists() != null;
    }

    @Override
    public String create(String name) {
        return ready() ? services.playlists().create(name) : "";
    }

    @Override
    public boolean rename(String id, String name) {
        if (!ready()) return false;
        services.playlists().rename(id, name);
        return true;
    }

    @Override
    public boolean delete(String id) {
        if (!ready()) return false;
        services.playlists().delete(id);
        return true;
    }

    @Override
    public boolean addAll(String id, List<Track> tracks) {
        if (!ready()) return false;
        services.playlists().addAll(id, tracks);
        return true;
    }

    @Override
    public boolean removeAll(String id, Set<String> keys) {
        if (!ready()) return false;
        services.playlists().removeAll(id, keys);
        return true;
    }

    @Override
    public boolean moveBlock(String id, int start, int count, int insertion) {
        if (!ready()) return false;
        services.playlists().moveBlock(id, start, count, insertion);
        return true;
    }

    @Override
    public boolean toggleFavorite(Track track) {
        if (!ready()) return false;
        services.playlists().toggleFavorite(track);
        return true;
    }
}
