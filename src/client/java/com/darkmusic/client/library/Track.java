package com.darkmusic.client.library;

import java.nio.file.Path;
import java.util.Arrays;

public final class Track {
    private final String rootId, relativePath, title, artist, album, problem;
    private final Path file;
    private final double duration;
    private final long size, modified;
    private transient volatile String cachedId, searchKey;
    private static final ThreadLocal<Query> QUERY = new ThreadLocal<>();

    private record Query(String raw, String normalized) {
    }

    /**
     * Rejects a stored reference that could point outside its music folder.
     */
    public static void checkReference(String rootId, String path) {
        if (rootId == null || rootId.isBlank() || path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/")).contains(".."))
            throw new IllegalArgumentException("Invalid playlist file reference");
    }

    public Track(String rootId, String relativePath, Path file, String title, String artist, String album, double duration, long size, long modified, String problem) {
        this.rootId = rootId;
        this.relativePath = relativePath;
        this.file = file;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.size = size;
        this.modified = modified;
        this.problem = problem;
        // Library/cache construction runs on storage workers, before tracks reach search widgets.
        cachedId = rootId + ":" + relativePath;
        searchKey = normalize(title + " " + artist + " " + album + " " + relativePath);
    }

    public String rootId() {
        return rootId;
    }

    public String relativePath() {
        return relativePath;
    }

    public Path file() {
        return file;
    }

    public String title() {
        return title;
    }

    public String artist() {
        return artist;
    }

    public String album() {
        return album;
    }

    public double duration() {
        return duration;
    }

    public long size() {
        return size;
    }

    public long modified() {
        return modified;
    }

    public String problem() {
        return problem;
    }

    public String id() {
        String value = cachedId;
        if (value == null) cachedId = value = rootId + ":" + relativePath;
        return value;
    }

    public String folder() {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "(root)" : relativePath.substring(0, slash);
    }

    public boolean matches(String query) {
        if (query.isBlank()) return true;
        Query previous = QUERY.get();
        if (previous == null || !previous.raw.equals(query)) {
            previous = new Query(query, normalize(query));
            QUERY.set(previous);
        }
        String value = searchKey;
        if (value == null) searchKey = value = normalize(title + " " + artist + " " + album + " " + relativePath);
        return value.contains(previous.normalized);
    }

    static String normalize(String text) {
        String decomposed = java.text.Normalizer.normalize(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC), java.text.Normalizer.Form.NFD);
        var out = new StringBuilder(decomposed.length());
        boolean latin = false;
        for (int point : decomposed.codePoints().toArray()) {
            int type = Character.getType(point);
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
            if (mark && latin) continue;
            out.appendCodePoint(point);
            if (!mark) latin = Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN;
        }
        return java.text.Normalizer.normalize(out, java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Track t)) return false;
        return Double.compare(duration, t.duration) == 0 && size == t.size && modified == t.modified && java.util.Objects.equals(rootId, t.rootId)
            && java.util.Objects.equals(relativePath, t.relativePath) && java.util.Objects.equals(file, t.file) && java.util.Objects.equals(title, t.title)
            && java.util.Objects.equals(artist, t.artist) && java.util.Objects.equals(album, t.album) && java.util.Objects.equals(problem, t.problem);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(rootId, relativePath, file, title, artist, album, duration, size, modified, problem);
    }

    @Override
    public String toString() {
        return "Track[" + id() + ", " + title + "]";
    }
}
