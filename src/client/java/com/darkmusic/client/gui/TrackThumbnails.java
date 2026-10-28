package com.darkmusic.client.gui;

import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.Track;

/**
 * Cover thumbnails for song cards; null when a track has none.
 */
public interface TrackThumbnails {
    ArtworkCache.Cover thumbnail(Track track);
}
