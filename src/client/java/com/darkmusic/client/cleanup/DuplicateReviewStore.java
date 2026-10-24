package com.darkmusic.client.cleanup;

import com.darkmusic.client.config.ConfigStore;
import com.google.gson.Gson;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Ignore identities are tied to the exact two file stamps, not titles or transitive groups.
 */
public final class DuplicateReviewStore {
    public record Ignored(String key, String a, String b, String titleA, String titleB, String pathA, String pathB,
                          long sizeA, long modifiedA, long sizeB, long modifiedB) {
    }

    private final Map<String, Ignored> ignored = new LinkedHashMap<>();
    private final Path path;
    private final Executor executor;
    private volatile List<Ignored> snapshot = List.of();
    private volatile boolean ready;
    private volatile String error = "";
    private volatile long revision;
    private boolean writing;
    private long savedRevision;

    public DuplicateReviewStore(Path data, Executor executor) {
        this.path = data.resolve("cleanup-ignored.json");
        this.executor = executor;
        executor.execute(() -> {
            try {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) < 16_000_000) {
                    Ignored[] records;
                    try (var reader = Files.newBufferedReader(path)) {
                        records = new Gson().fromJson(reader, Ignored[].class);
                    }
                    if (records != null) synchronized (this) {
                        for (Ignored value : records)
                            if (value != null && value.key != null && value.a != null && value.b != null && value.titleA != null && value.titleB != null
                                && value.pathA != null && value.pathB != null && value.key.length() < 512 && ignored.size() < 20_000)
                                ignored.put(value.key, value);
                    }
                }
            } catch (Exception failure) {
                error = "cleanup.store_failed";
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Could not read ignored matches", failure);
            }
            synchronized (this) {
                ready = true;
                publish();
            }
        });
    }

    public boolean ready() {
        return ready;
    }

    public long revision() {
        return revision;
    }

    public String error() {
        return error;
    }

    public List<Ignored> snapshot() {
        return snapshot;
    }

    public synchronized boolean contains(DuplicateResult match) {
        return ignored.containsKey(match.reviewKey());
    }

    public synchronized boolean ignore(DuplicateResult match) {
        if (!ready) return false;
        if (ignored.size() >= 20_000 && !ignored.containsKey(match.reviewKey())) {
            error = "cleanup.ignore_limit";
            return false;
        }
        var value = new Ignored(match.reviewKey(), match.a().id(), match.b().id(), match.a().title(), match.b().title(), match.a().file().toString(), match.b().file().toString(),
            match.a().size(), match.a().modified(), match.b().size(), match.b().modified());
        ignored.put(value.key, value);
        changed();
        return true;
    }

    public synchronized void restore(String key) {
        if (ready && ignored.remove(key) != null) changed();
    }

    public synchronized void clear() {
        if (ready && !ignored.isEmpty()) {
            ignored.clear();
            changed();
        }
    }

    private void publish() {
        snapshot = List.copyOf(ignored.values());
        revision++;
    }

    private void changed() {
        publish();
        if (writing) return;
        writing = true;
        try {
            executor.execute(this::drain);
        } catch (RuntimeException rejected) {
            writing = false;
            error = "cleanup.store_failed";
        }
    }

    public synchronized void deleted(Set<String> ids) {
        if (ready && ignored.values().removeIf(v -> ids.contains(v.a) || ids.contains(v.b))) changed();
    }

    public void prune(List<com.darkmusic.client.library.Track> tracks, Set<String> registered, Set<String> complete) {
        executor.execute(() -> {
            var current = new HashMap<String, com.darkmusic.client.library.Track>();
            for (var track : tracks) current.put(track.id(), track);
            synchronized (this) {
                if (!ready) return;
                boolean changed = ignored.values().removeIf(v -> stale(v.a, v.sizeA, v.modifiedA, current, registered, complete)
                    || stale(v.b, v.sizeB, v.modifiedB, current, registered, complete));
                if (changed) changed();
            }
        });
    }

    private static boolean stale(String id, long size, long modified, Map<String, com.darkmusic.client.library.Track> current,
                                 Set<String> registered, Set<String> complete) {
        var track = current.get(id);
        if (track != null) return size > 0 && (size != track.size() || modified != track.modified());
        return registered.stream().noneMatch(root -> id.startsWith(root + ":")) || complete.stream().anyMatch(root -> id.startsWith(root + ":"));
    }

    private void drain() {
        for (; ; ) {
            List<Ignored> values;
            long version;
            synchronized (this) {
                if (savedRevision == revision) {
                    writing = false;
                    return;
                }
                values = snapshot;
                version = revision;
            }
            try {
                String json = new Gson().toJson(values);
                if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 16_000_000)
                    throw new java.io.IOException("Ignored-match storage capacity exceeded");
                ConfigStore.atomicWrite(path, json);
                error = "";
            } catch (Exception failure) {
                error = "cleanup.store_failed";
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Could not save ignored matches", failure);
                synchronized (this) {
                    writing = false;
                }
                return;
            }
            synchronized (this) {
                savedRevision = version;
            }
        }
    }
}
