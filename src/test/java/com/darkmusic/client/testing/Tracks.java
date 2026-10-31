package com.darkmusic.client.testing;

import com.darkmusic.client.library.Track;

import java.nio.file.Path;

/**
 * Library tracks for tests that need one without reading a file.
 */
public final class Tracks {
    private Tracks() {
    }

    public static Track of(String root, String path, Path file) {
        return new Track(root, path, file, path, "Artist", "Album", 1, 0, 0, "");
    }
}
