package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Provider results by query: a small in-memory LRU over disk files that expire after 90 days (14 for misses)
 * and are trimmed by size in the background. A null directory keeps results in memory only.
 */
final class LyricsResultCache {
    private record Cached(int schema, long expires, List<LyricsProvider.Candidate> candidates) {
    }

    record CacheFile(Path path, long size, java.nio.file.attribute.FileTime modified, Object fileKey) {
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LyricsLookup.class);
    private final Path directory;
    private final Consumer<Runnable> background;
    private final LinkedHashMap<String, Cached> results = new LinkedHashMap<>(16, .75f, true);
    private long nextTrim, bytesSinceTrim;
    private final Object files = new Object();

    LyricsResultCache(Path directory, Consumer<Runnable> background) {
        this.directory = directory;
        this.background = background;
    }

    /**
     * Forgets every result in memory and on disk; {@code alongside} runs under the same lock.
     */
    void clear(Runnable alongside) throws java.io.IOException {
        synchronized (results) {
            results.clear();
            alongside.run();
            if (directory != null) for (String name : List.of("cache", "misses")) {
                if (!Files.isDirectory(directory.resolve(name), LinkOption.NOFOLLOW_LINKS)) continue;
                try (var entries = Files.list(directory.resolve(name))) {
                    for (Path p : entries.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList())
                        Files.deleteIfExists(p);
                }
            }
        }
    }

    List<LyricsProvider.Candidate> get(String key) {
        Cached value;
        synchronized (results) {
            value = results.get(key);
        }
        Cached initial = value;
        if (value == null && directory != null) try {
            Path miss = directory.resolve("misses").resolve(key + ".json");
            value = LyricsLookup.read(Files.isRegularFile(miss, LinkOption.NOFOLLOW_LINKS) ? miss : directory.resolve("cache").resolve(key + ".json"), Cached.class);
        } catch (Exception ignored) {
        }
        if (value == null || value.schema != 1 || value.expires < System.currentTimeMillis() || value.candidates == null || value.candidates.size() > 20)
            return null;
        try {
            var checked = value.candidates.stream().map(c -> new LyricsProvider.Candidate(LyricsLookup.validated(c.document()), c.reliable(), c.evidence())).toList();
            value = new Cached(1, value.expires, checked);
            synchronized (results) {
                Cached newer = results.get(key);
                if (newer == null || newer == initial) results.put(key, value);
                else value = newer;
                trim();
            }
            return value.candidates;
        } catch (Exception invalid) {
            return null;
        }
    }

    void put(String key, List<LyricsProvider.Candidate> candidates) {
        var value = new Cached(1, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(candidates.isEmpty() ? 14 : 90), candidates);
        synchronized (results) {
            results.put(key, value);
            trim();
        }
        if (directory == null) return;
        try {
            String json = ConfigStore.JSON.toJson(value);
            Path folder = directory.resolve(candidates.isEmpty() ? "misses" : "cache");
            synchronized (files) {
                ConfigStore.atomicWrite(folder.resolve(key + ".json"), json);
                Files.deleteIfExists(directory.resolve(candidates.isEmpty() ? "cache" : "misses").resolve(key + ".json"));
            }
            synchronized (results) {
                bytesSinceTrim += json.getBytes(StandardCharsets.UTF_8).length;
                if (System.nanoTime() < nextTrim && bytesSinceTrim < 4L * 1024 * 1024) return;
                nextTrim = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
                bytesSinceTrim = 0;
            }
            background.accept(this::trimDiskCache);
        } catch (Exception failure) {
            LOG.warn("Could not save lyric lookup cache", failure);
        }
    }

    static CacheFile file(Path path) throws java.io.IOException {
        var a = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return a.isRegularFile() ? new CacheFile(path, a.size(), a.lastModifiedTime(), a.fileKey()) : null;
    }

    boolean discard(CacheFile candidate) throws java.io.IOException {
        synchronized (files) {
            CacheFile current = file(candidate.path);
            if (!candidate.equals(current)) return false; // A refresh won the race; keep its new result.
            return Files.deleteIfExists(candidate.path);
        }
    }

    private void trimDiskCache() {
        try {
            for (String name : List.of("cache", "misses")) {
                Path retained = directory.resolve(name);
                if (!Files.isDirectory(retained, LinkOption.NOFOLLOW_LINKS)) continue;
                long limit = (name.equals("misses") ? 16L : 64L) * 1024 * 1024;
                try (var files = Files.list(retained)) {
                    var all = files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).map(p -> {
                        try {
                            return file(p);
                        } catch (java.io.IOException vanished) {
                            return null;
                        }
                    }).filter(java.util.Objects::nonNull).sorted(Comparator.comparing(CacheFile::modified)).toList();
                    long bytes = all.stream().mapToLong(CacheFile::size).sum(), count = all.size();
                    for (CacheFile file : all) {
                        if (count <= 256_000 && bytes <= limit) break;
                        if (discard(file)) {
                            bytes -= file.size;
                            count--;
                        }
                    }
                }
            }
        } catch (Exception failure) {
            LOG.warn("Could not trim lyric lookup cache", failure);
        }
    }

    private void trim() {
        while (results.size() > 128) results.remove(results.keySet().iterator().next());
    }
}
