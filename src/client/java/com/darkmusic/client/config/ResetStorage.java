package com.darkmusic.client.config;

import com.darkmusic.client.text.Text;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Predicate;

/**
 * Reset removes an allowlist of owned records. Download tools and media are preserved.
 * Unknown, modified, linked, and media files are deliberately left alone.
 */
public final class ResetStorage {
    private final Path data;
    private final List<String> retained = new ArrayList<>();

    private ResetStorage(Path data) throws IOException {
        this.data = data.toRealPath();
    }

    public static List<String> clear(Path data, Collection<Path> musicRoots) throws IOException {
        var reset = new ResetStorage(data);
        // These are lock-only operations, unlike DownloadWorkspace's scratch recovery.
        try (var downloads = reset.lock("downloads/workspace.lock")) {
            Files.createDirectories(reset.data.resolve("music"));
            reset.json(reset.data.resolve("library-cache.json"), JsonElement::isJsonArray);
            reset.json(reset.data.resolve("import-history.json"), JsonElement::isJsonArray);
            reset.json(reset.data.resolve("loudness-cache.json"), JsonElement::isJsonObject);
            var roots = new LinkedHashSet<>(musicRoots);
            roots.add(reset.data.resolve("music"));
            for (Path root : roots) {
                try {
                    if (!Files.isDirectory(root)) continue;
                    Path canonical = root.toRealPath();
                    reset.json(canonical.resolve(".darkmusic-downloads.json"), value -> value.isJsonObject()
                        && value.getAsJsonObject().entrySet().stream().allMatch(e -> e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()));
                    reset.record(canonical.resolve("downloaded.txt"), text -> text.lines().allMatch(line -> line.isBlank() || line.matches("[a-zA-Z0-9_:.-]+ [^\\s\\p{Cntrl}]+")));
                } catch (IOException e) {
                    reset.keep(root, e);
                }
            }
        }
        return List.copyOf(reset.retained);
    }

    private Locked lock(String relative) throws IOException {
        Path file = data.resolve(relative);
        for (Path ancestor = file.getParent(); !ancestor.equals(data); ancestor = ancestor.getParent())
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) && !local(ancestor))
                throw new IOException(Text.get("reset.darkmusic.linked", ancestor));
        Files.createDirectories(file.getParent());
        if (!local(file.getParent()) || Files.isSymbolicLink(file)
            || Files.exists(file, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != 0))
            throw new IOException(Text.get("reset.darkmusic.lock_unsafe", file));
        var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null)
                throw new IOException(Text.get("reset.darkmusic.lock_busy"));
            return new Locked(channel, lock);
        } catch (Exception e) {
            channel.close();
            throw new IOException(Text.get("reset.darkmusic.lock_failed"), e);
        }
    }

    private boolean local(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(data) && normalized.toRealPath().equals(normalized);
    }

    private void json(Path path, Predicate<JsonElement> schema) {
        record(path, text -> {
            try {
                return schema.test(JsonParser.parseString(text));
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    private void record(Path path, Predicate<String> owned) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path.toAbsolutePath().normalize())
                || Files.size(path) > 16_000_000 || !owned.test(Files.readString(path))) {
                keep(path, null);
                return;
            }
            Files.delete(path);
        } catch (IOException e) {
            keep(path, e);
        }
    }

    private void keep(Path file, Exception error) {
        retained.add(file.toString());
        org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Reset preserved unrecognized, linked, or inaccessible file: {}", file, error);
    }

    private record Locked(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
