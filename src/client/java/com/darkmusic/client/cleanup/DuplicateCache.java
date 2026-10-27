package com.darkmusic.client.cleanup;

import com.darkmusic.client.config.ConfigStore;
import com.google.gson.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Small insertion-ordered shards bound checkpoint writes during sequential analysis. Worker-owned.
 */
public final class DuplicateCache {
    private final Path directory;
    private final Map<String, Saved> entries = new LinkedHashMap<>();
    private final Set<Integer> dirty = new HashSet<>();
    private final Map<String, Integer> shards = new HashMap<>();
    private static final int SHARD_ENTRIES = 512;
    private static final int MAX_SHARDS = (DuplicatePolicy.MAX_TRACKS + SHARD_ENTRIES - 1) / SHARD_ENTRIES + 1;
    private final Map<Integer, Integer> counts = new HashMap<>();
    private boolean initialized;
    private int tailShard;
    private static final Gson JSON = new Gson();

    public record Saved(String id, String stamp, String hash, String source, String fingerprint) {
        public int[] values() {
            if (fingerprint == null || fingerprint.length() > 2200) return new int[0];
            try {
                byte[] raw = Base64.getDecoder().decode(fingerprint);
                if (raw.length % 4 != 0 || raw.length > DuplicatePolicy.MAX_FINGERPRINT * 4) return new int[0];
                var buffer = ByteBuffer.wrap(raw);
                int[] result = new int[raw.length / 4];
                for (int i = 0; i < result.length; i++) result[i] = buffer.getInt();
                return result;
            } catch (RuntimeException invalid) {
                return new int[0];
            }
        }
    }

    private record FileData(int schema, String algorithm, List<Saved> entries) {
    }

    public DuplicateCache(Path data) {
        directory = data.resolve("cleanup-cache");
    }

    public void load() {
        boolean current = Files.isRegularFile(directory.resolve("v2/complete"), LinkOption.NOFOLLOW_LINKS);
        initialized = current;
        int count = current ? MAX_SHARDS : 16;
        for (int shard = 0; shard < count; shard++) {
            Path path = current ? file(shard) : directory.resolve("cache-" + shard + ".json");
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 24_000_000) continue;
                FileData data;
                try (var reader = Files.newBufferedReader(path)) {
                    data = JSON.fromJson(reader, FileData.class);
                }
                if (data == null || data.schema != DuplicatePolicy.CACHE_SCHEMA || !FingerprintService.ALGORITHM.equals(data.algorithm) || data.entries == null)
                    continue;
                for (Saved value : data.entries)
                    if (valid(value) && entries.size() < DuplicatePolicy.MAX_TRACKS && !entries.containsKey(value.id)) {
                        entries.put(value.id, value);
                        int assigned = current ? shard : allocate();
                        shards.put(value.id, assigned);
                        if (current) counts.merge(assigned, 1, Integer::sum);
                        if (current && assigned > tailShard) tailShard = assigned;
                        if (!current) dirty.add(assigned);
                    }
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Ignoring damaged duplicate cache shard {}", shard, failure);
            }
        }
    }

    private boolean valid(Saved v) {
        return v != null && v.id != null && v.id.length() <= 16384 && v.stamp != null && v.stamp.length() <= 128
            && v.hash != null && (v.hash.isEmpty() || v.hash.matches("[a-f0-9]{64}")) && v.source != null && v.source.length() <= 8192
            && v.fingerprint != null && v.fingerprint.length() <= 2200;
    }

    public Saved get(String id, String stamp) {
        var entry = entries.get(id);
        return entry != null && entry.stamp.equals(stamp) ? entry : null;
    }

    public void put(String id, String stamp, String hash, String source, int[] fingerprint) {
        if (!entries.containsKey(id) && entries.size() >= DuplicatePolicy.MAX_TRACKS) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
            int removedShard = shards.remove(oldest);
            dirty.add(removedShard);
            counts.computeIfPresent(removedShard, (s, n) -> n - 1);
        }
        var bytes = ByteBuffer.allocate(fingerprint.length * 4);
        for (int value : fingerprint) bytes.putInt(value);
        var saved = new Saved(id, stamp, hash, source, Base64.getEncoder().encodeToString(bytes.array()));
        if (!shards.containsKey(id)) shards.put(id, allocate());
        if (!saved.equals(entries.put(id, saved))) dirty.add(shards.get(id));
    }

    public void flush() throws IOException {
        // Group once: filtering every entry per dirty shard is quadratic when a legacy cache migrates.
        var byShard = new HashMap<Integer, List<Saved>>();
        for (Saved value : entries.values()) {
            Integer shard = shards.get(value.id);
            if (shard != null && dirty.contains(shard))
                byShard.computeIfAbsent(shard, s -> new ArrayList<>()).add(value);
        }
        for (int shard : List.copyOf(dirty)) {
            var values = byShard.getOrDefault(shard, List.of());
            String json = JSON.toJson(new FileData(DuplicatePolicy.CACHE_SCHEMA, FingerprintService.ALGORITHM, values));
            if (json.length() > 24_000_000) throw new IOException("Duplicate cache shard capacity exceeded");
            ConfigStore.atomicWrite(file(shard), json);
            dirty.remove(shard);
        }
        if (!initialized && !entries.isEmpty()) {
            ConfigStore.atomicWrite(directory.resolve("v2/complete"), "2");
            initialized = true;
        }
    }

    public void remove(Set<String> ids) {
        for (String id : ids) {
            if (entries.remove(id) == null) continue;
            int shard = shards.remove(id);
            counts.computeIfPresent(shard, (s, count) -> count - 1);
            dirty.add(shard);
        }
    }

    public void prune(Set<String> present, Set<String> registered, Set<String> complete) {
        var missing = new HashSet<String>();
        for (String id : entries.keySet())
            if (!present.contains(id) &&
                (registered.stream().noneMatch(root -> id.startsWith(root + ":")) || complete.stream().anyMatch(root -> id.startsWith(root + ":"))))
                missing.add(id);
        remove(missing);
    }

    private int allocate() {
        while (counts.getOrDefault(tailShard, 0) >= SHARD_ENTRIES) tailShard = (tailShard + 1) % MAX_SHARDS;
        counts.merge(tailShard, 1, Integer::sum);
        return tailShard;
    }

    private Path file(int index) {
        return directory.resolve("v2").resolve("cache-" + index + ".json");
    }
}
