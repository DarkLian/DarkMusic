package com.darkmusic.client.audio;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.Track;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The loudness readings and loudness-cache.json: loading, remembering a reading, and merging writes with readings
 * other caches (preview players) saved to the same file.
 * <p>
 * Locks, in order: flushLock, then SAVING, then persistence. persistence guards the readings' bookkeeping and the
 * dirty state and is otherwise held alone; nothing waits for flushLock or SAVING while holding it. The store never
 * calls back into LoudnessCache, whose monitor is never held while calling in.
 */
final class LoudnessStore {
    private static final Logger LOG = LoggerFactory.getLogger("darkmusic-audio");
    static final int READINGS = 65536;
    private static final long MAX_CACHE_BYTES = 64_000_000;
    // Preview players keep their own caches on the same file; one read-merge-write at a time.
    private static final Object SAVING = new Object();
    private static final Gson STORAGE_JSON = new Gson();
    private final Map<String, LoudnessCache.Reading> results = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> storedKeys = new HashMap<>();
    private final Path cache;
    private final Object persistence = new Object();
    private final Object flushLock = new Object();
    private boolean dirty;
    private long revision;
    private volatile long nextFlush;
    private boolean capacityReported;
    private boolean preserveOriginal;
    private volatile boolean closed;

    /**
     * @param cache the file, or null when there is no data directory and nothing persists
     */
    LoudnessStore(Path cache) {
        this.cache = cache;
    }

    LoudnessCache.Reading get(String key) {
        return results.get(key);
    }

    boolean has(String key) {
        return results.containsKey(key);
    }

    /**
     * Reads the file once, before the first inspection. A file over the read limit, or one that cannot be read, is
     * preserved: this store then never writes it.
     */
    void load() {
        try {
            if (cache != null && Files.isRegularFile(cache) && Files.size(cache) <= MAX_CACHE_BYTES) {
                read(cache, (key, r) -> {
                    if (results.size() >= READINGS) return;
                    synchronized (persistence) {
                        results.put(key, r);
                        storedKeys.computeIfAbsent(pathOfKey(key), ignored -> new HashSet<>()).add(key);
                    }
                });
            } else if (cache != null && Files.isRegularFile(cache)) {
                synchronized (persistence) {
                    preserveOriginal = true;
                }
                LOG.warn("Loudness cache {} exceeds the 64 MB read limit; file preserved", cache);
            }
        } catch (Exception e) {
            synchronized (persistence) {
                results.clear();
                storedKeys.clear();
                preserveOriginal = true;
            }
            LOG.warn("Could not load loudness cache {}", cache, e);
        }
    }

    /**
     * After closing, readings are no longer remembered; the final flush still writes what was remembered before.
     */
    void close() {
        closed = true;
    }

    /**
     * Whether the periodic writer should flush now: not while a failed write is backing off.
     */
    boolean due() {
        return System.nanoTime() >= nextFlush;
    }

    /**
     * This instance's readings plus those another instance saved since, without versions either has replaced.
     */
    private Map<String, LoudnessCache.Reading> withSaved(Map<String, LoudnessCache.Reading> own) throws IOException {
        var merged = new HashMap<>(own);
        if (Files.isRegularFile(cache)) {
            if (Files.size(cache) > MAX_CACHE_BYTES)
                throw new JsonParseException("Loudness cache exceeds the 64 MB read limit");
            read(cache, (key, r) -> {
                if (merged.size() < READINGS) merged.putIfAbsent(key, r);
            });
        }
        latestVersions(merged);
        return merged;
    }

    /**
     * Where readings cover more than one version of a file, keeps the version the file has now. Either cache's view
     * may be stale, so neither decides; a file that cannot be checked keeps its newest modification time.
     */
    static void latestVersions(Map<String, LoudnessCache.Reading> readings) {
        var byPath = new HashMap<String, List<String>>();
        for (String key : readings.keySet())
            byPath.computeIfAbsent(pathOfKey(key), ignored -> new ArrayList<>()).add(key);
        for (var entry : byPath.entrySet()) {
            var keys = entry.getValue();
            if (keys.stream().map(LoudnessStore::versionOfKey).distinct().count() < 2) continue;
            String keep;
            try {
                Path file = Path.of(entry.getKey());
                keep = entry.getKey() + ":" + Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis() + ":";
            } catch (IOException | InvalidPathException e) {
                keep = versionOfKey(Collections.max(keys, Comparator.comparingLong(LoudnessStore::modifiedOfKey)));
            }
            for (String key : keys) if (!versionOfKey(key).equals(keep)) readings.remove(key);
        }
    }

    /**
     * Every valid reading in a cache file. Throws when the file is not a complete cache.
     */
    private static void read(Path file, BiConsumer<String, LoudnessCache.Reading> into) throws IOException {
        try (var reader = new JsonReader(Files.newBufferedReader(file))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                LoudnessCache.Reading r = ConfigStore.JSON.fromJson(reader, LoudnessCache.Reading.class);
                if (r != null && pathOfKey(key) != null && Double.isFinite(r.lufs()) && Double.isFinite(r.peakDb()))
                    into.accept(key, r);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new JsonParseException("Trailing loudness cache contents");
        }
    }

    private static String versionOfKey(String key) {
        return key.substring(0, key.lastIndexOf(':') + 1);
    }

    private static long modifiedOfKey(String key) {
        int mode = key.lastIndexOf(':'), modified = key.lastIndexOf(':', mode - 1);
        try {
            return Long.parseLong(key.substring(modified + 1, mode));
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String pathOfKey(String key) {
        int mode = key.lastIndexOf(':'), modified = mode < 0 ? -1 : key.lastIndexOf(':', mode - 1);
        int size = modified < 0 ? -1 : key.lastIndexOf(':', modified - 1);
        return size < 0 ? null : key.substring(0, size);
    }

    void remember(Track track, String key, LoudnessCache.Reading reading) {
        synchronized (persistence) {
            if (closed) return;
            if (results.size() >= READINGS && !results.containsKey(key)) {
                if (!capacityReported)
                    LOG.warn("Loudness cache reached its 65,536-reading limit; existing readings retained");
                capacityReported = true;
                return;
            }
            // Retire changed versions only for this observed file, never missing/offline libraries.
            String path = track.file().toAbsolutePath().toString();
            String prefix = path + ":";
            String version = prefix + track.size() + ":" + track.modified() + ":";
            var keys = storedKeys.computeIfAbsent(path, ignored -> new HashSet<>());
            keys.removeIf(old -> {
                if (old.startsWith(version)) return false;
                results.remove(old);
                return true;
            });
            keys.add(key);
            results.put(key, reading);
            dirty = true;
            revision++;
        }
    }

    void flush() {
        synchronized (flushLock) {
            Map<String, LoudnessCache.Reading> snapshot;
            long captured;
            synchronized (persistence) {
                if (!dirty || cache == null || preserveOriginal) return;
                snapshot = Map.copyOf(results);
                captured = revision;
            }
            try {
                synchronized (SAVING) {
                    Map<String, LoudnessCache.Reading> merged;
                    try {
                        merged = withSaved(snapshot);
                    } catch (JsonParseException | IllegalStateException | MalformedJsonException |
                             EOFException e) {
                        synchronized (persistence) {
                            preserveOriginal = true;
                        }
                        LOG.warn("Loudness cache {} became unreadable; file preserved", cache, e);
                        return;
                    }
                    String json = STORAGE_JSON.toJson(merged);
                    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CACHE_BYTES)
                        throw new IOException("Loudness cache exceeds the 64 MB persistence limit; previous cache preserved");
                    ConfigStore.atomicWrite(cache, json);
                }
                nextFlush = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                synchronized (persistence) {
                    if (revision == captured) dirty = false;
                }
            } catch (Exception e) {
                nextFlush = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                LOG.warn("Could not save loudness cache {}; will retry", cache, e);
            }
        }
    }

    /**
     * For tests: when the periodic writer may flush next.
     */
    long nextFlush() {
        return nextFlush;
    }

    /**
     * For tests: the live readings, keyed by path, size, modification time and gain mode.
     */
    Map<String, LoudnessCache.Reading> readings() {
        return results;
    }
}
