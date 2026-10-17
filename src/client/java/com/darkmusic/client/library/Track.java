package com.darkmusic.client.library;

import java.nio.file.Path;

public record Track(String rootId, String relativePath, Path file, String title, String artist,
                    String album, double duration, long size, long modified, String problem) {
    public String id() {
        return rootId + ":" + relativePath;
    }

    public String folder() {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "(root)" : relativePath.substring(0, slash);
    }

    public boolean matches(String query) {
        if (query.isBlank()) return true;
        return (title + " " + artist + " " + album + " " + relativePath).toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
