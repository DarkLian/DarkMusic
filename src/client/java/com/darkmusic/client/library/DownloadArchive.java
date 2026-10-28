package com.darkmusic.client.library;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The per-folder download history ({@value #FILE}). Code that rewrites it holds {@link #LOCK}.
 */
public final class DownloadArchive {
    public static final String FILE = ".darkmusic-downloads.json";
    public static final Object LOCK = new Object();

    private DownloadArchive() {
    }

    public static JsonObject read(Path history) throws IOException {
        if (!Files.exists(history)) return new JsonObject();
        if (Files.size(history) > 16_000_000)
            throw new IOException(Text.get("text.darkmusic.download_history_is_too_large_preserve_it_and_choose_another_folder"));
        try (var reader = Files.newBufferedReader(history)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(Text.get("download.history_read"), e);
        }
    }

    @FunctionalInterface
    public interface RenameOperation {
        void run() throws IOException;
    }

    public static void forgetRecorded(Path audio) throws IOException {
        synchronized (LOCK) {
            Path file = audio.resolveSibling(FILE);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
            var archive = read(file);
            boolean changed = archive.entrySet().removeIf(e -> e.getValue().isJsonPrimitive() && audio.getFileName().toString().equals(e.getValue().getAsString()));
            if (changed) ConfigStore.atomicWrite(file, ConfigStore.JSON.toJson(archive));
        }
    }

    /**
     * Keep duplicate detection and the file rename in one serialized transaction.
     */
    public static void renameRecorded(Path source, Path target, RenameOperation rename) throws IOException {
        synchronized (LOCK) {
            Path history = source.getParent().resolve(FILE);
            JsonObject original = read(history), updated = original.deepCopy();
            boolean changed = false;
            for (var entry : original.entrySet())
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsString().equals(source.getFileName().toString())) {
                    updated.addProperty(entry.getKey(), target.getFileName().toString());
                    changed = true;
                }
            if (changed) ConfigStore.atomicWrite(history, ConfigStore.JSON.toJson(updated));
            try {
                rename.run();
            } catch (IOException failure) {
                if (changed) try {
                    ConfigStore.atomicWrite(history, ConfigStore.JSON.toJson(original));
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }
}
