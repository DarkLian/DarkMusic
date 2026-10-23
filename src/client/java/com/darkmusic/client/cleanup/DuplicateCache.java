package com.darkmusic.client.cleanup;

import com.darkmusic.client.config.ConfigStore;
import com.google.gson.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Sixteen bounded atomic shards avoid repeatedly serializing a large library during analysis. Worker-owned.
 */
public final class DuplicateCache {
    private final Path directory;
    private final Map<String, Saved> entries = new LinkedHashMap<>();
    private final Set<Integer> dirty = new HashSet<>();
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
        for (int shard = 0; shard < 16; shard++) {
            Path path = file(shard);
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 24_000_000) continue;
                FileData data;
                try (var reader = Files.newBufferedReader(path)) {
                    data = JSON.fromJson(reader, FileData.class);
                }
                if (data == null || data.schema != DuplicatePolicy.CACHE_SCHEMA || !FingerprintService.ALGORITHM.equals(data.algorithm) || data.entries == null)
                    continue;
                for (Saved value : data.entries)
                    if (valid(value) && entries.size() < DuplicatePolicy.MAX_TRACKS) entries.put(value.id, value);
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
            dirty.add(shard(oldest));
        }
        var bytes = ByteBuffer.allocate(fingerprint.length * 4);
        for (int value : fingerprint) bytes.putInt(value);
        var saved = new Saved(id, stamp, hash, source, Base64.getEncoder().encodeToString(bytes.array()));
        if (!saved.equals(entries.put(id, saved))) dirty.add(shard(id));
    }

    public void flush() throws IOException {
        for (int shard : List.copyOf(dirty)) {
            var values = entries.values().stream().filter(v -> shard(v.id) == shard).toList();
            String json = JSON.toJson(new FileData(DuplicatePolicy.CACHE_SCHEMA, FingerprintService.ALGORITHM, values));
            if (json.length() > 24_000_000) throw new IOException("Duplicate cache shard capacity exceeded");
            ConfigStore.atomicWrite(file(shard), json);
            dirty.remove(shard);
        }
    }

    private static int shard(String id) {
        return id.hashCode() & 15;
    }

    private Path file(int index) {
        return directory.resolve("cache-" + index + ".json");
    }
}
