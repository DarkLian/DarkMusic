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
    public record Ignored(String key, String a, String b, String titleA, String titleB, String pathA, String pathB) {
    }

    private final Map<String, Ignored> ignored = new LinkedHashMap<>();
    private final Path path;
    private final Executor executor;
    private volatile List<Ignored> snapshot = List.of();
    private volatile boolean ready;
    private volatile String error = "";
    private volatile long revision;

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
        if (!ready || ignored.size() >= 20_000) return false;
        var value = new Ignored(match.reviewKey(), match.a().id(), match.b().id(), match.a().title(), match.b().title(), match.a().file().toString(), match.b().file().toString());
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
        var values = snapshot;
        executor.execute(() -> {
            try {
                String json = new Gson().toJson(values);
                if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 16_000_000)
                    throw new java.io.IOException("Ignored-match storage capacity exceeded");
                ConfigStore.atomicWrite(path, json);
                error = "";
            } catch (Exception failure) {
                error = "cleanup.store_failed";
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Could not save ignored matches", failure);
            }
        });
    }
}
