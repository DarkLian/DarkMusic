package com.darkmusic.client.library;

import com.darkmusic.client.config.ConfigStore;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Serial storage only; the client reads immutable snapshots. Never opens imported media.
 */
public final class ImportHistory {
    public record Batch(long timestamp, String destination, int requested, List<MediaFiles.ImportEntry> entries,
                        String error) {
        public Batch {
            if (timestamp < 0 || destination == null || requested < 0 || requested > 1000 || entries == null || entries.size() > 1000 || error == null)
                throw new IllegalArgumentException("Invalid import history record");
            entries = List.copyOf(entries);
            for (var entry : entries)
                if (entry == null || entry.source() == null || entry.destination() == null || entry.message() == null)
                    throw new IllegalArgumentException("Invalid import history item");
        }
    }

    private final Path file;
    private final ExecutorService io;
    private final Consumer<String> warning;
    private volatile List<Batch> batches = List.of();
    private volatile long revision;
    private boolean preserveInvalid;

    public ImportHistory(Path directory, ExecutorService io, Consumer<String> warning) {
        file = directory.resolve("import-history.json");
        this.io = io;
        this.warning = warning;
        io.execute(this::load);
    }

    public List<Batch> batches() {
        return batches;
    }

    public long revision() {
        return revision;
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                if (Files.size(file) > 16 * 1024 * 1024)
                    throw new IllegalArgumentException("Import history is too large");
                try (var reader = Files.newBufferedReader(file)) {
                    List<Batch> loaded = ConfigStore.JSON.fromJson(reader, new TypeToken<List<Batch>>() {
                    }.getType());
                    if (loaded == null || loaded.stream().anyMatch(java.util.Objects::isNull))
                        throw new IllegalArgumentException("Invalid import history");
                    batches = bounded(loaded);
                }
            }
        } catch (Exception e) {
            preserveInvalid = true;
            warning.accept("Cannot read import history: " + e.getMessage());
        }
        revision++;
    }

    public void record(Batch batch) {
        try {
            io.execute(() -> {
                var next = new ArrayList<Batch>();
                next.add(batch);
                next.addAll(batches);
                batches = bounded(next);
                revision++;
                try {
                    if (preserveInvalid && Files.exists(file)) {
                        Files.copy(file, file.resolveSibling("import-history.invalid-" + System.currentTimeMillis() + ".json"));
                        preserveInvalid = false;
                    }
                    ConfigStore.atomicWrite(file, ConfigStore.JSON.toJson(batches));
                } catch (Exception e) {
                    warning.accept("Cannot save import history: " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            org.slf4j.LoggerFactory.getLogger(ImportHistory.class).debug("Import history storage is closing", e);
        }
    }

    private static List<Batch> bounded(List<Batch> source) {
        var result = new ArrayList<Batch>();
        int count = 0;
        for (var batch : source) {
            int size = Math.max(1, batch.entries().size());
            if (result.size() >= 100 || count + size > 5000) break;
            result.add(batch);
            count += size;
        }
        return List.copyOf(result);
    }
}
