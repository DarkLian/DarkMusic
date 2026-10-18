package com.darkmusic.client.config;

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
 * Reset removes an allowlist of owned records and executables, never a directory tree.
 * Unknown, modified, linked, and media files are deliberately left alone.
 */
public final class ResetStorage {
    private static final Set<String> EXECUTABLES = Set.of("yt-dlp", "yt-dlp.exe", "ffmpeg", "ffmpeg.exe", "ffprobe", "ffprobe.exe", "ffplay", "ffplay.exe", "deno", "deno.exe");
    private final Path data;
    private final List<String> retained = new ArrayList<>();

    private ResetStorage(Path data) throws IOException {
        this.data = data.toRealPath();
    }

    public static List<String> clear(Path data, Collection<Path> musicRoots) throws IOException {
        var reset = new ResetStorage(data);
        // These are lock-only operations, unlike DownloadWorkspace's scratch recovery.
        try (var downloads = reset.lock("downloads/workspace.lock");
             var tools = reset.lock("tools/install.lock")) {
            Files.createDirectories(reset.data.resolve("music"));
            reset.json(reset.data.resolve("library-cache.json"), JsonElement::isJsonArray);
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
            reset.tools();
        }
        return List.copyOf(reset.retained);
    }

    private static boolean executable(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var header = java.nio.ByteBuffer.allocate(64);
            while (header.hasRemaining() && channel.read(header) > 0) {
            }
            if (header.position() < 4) return false;
            byte[] bytes = header.array();
            int magic = java.nio.ByteBuffer.wrap(bytes).getInt();
            if (magic == 0x7f454c46 || magic == 0xfeedface || magic == 0xcefaedfe || magic == 0xfeedfacf || magic == 0xcffaedfe || magic == 0xcafebabe)
                return true;
            if (bytes[0] == '#' && bytes[1] == '!')
                return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).contains("python");
            if (header.position() < 64 || bytes[0] != 'M' || bytes[1] != 'Z') return false;
            long offset = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(60));
            if (offset < 64 || offset > channel.size() - 4) return false;
            channel.position(offset);
            var pe = java.nio.ByteBuffer.allocate(4);
            while (pe.hasRemaining() && channel.read(pe) > 0) {
            }
            return pe.position() == 4 && pe.getInt(0) == 0x50450000;
        }
    }

    private Locked lock(String relative) throws IOException {
        Path file = data.resolve(relative);
        for (Path ancestor = file.getParent(); !ancestor.equals(data); ancestor = ancestor.getParent())
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) && !local(ancestor))
                throw new IOException(com.darkmusic.client.text.Text.get("reset.darkmusic.linked", ancestor));
        Files.createDirectories(file.getParent());
        if (!local(file.getParent()) || Files.isSymbolicLink(file)
            || Files.exists(file, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != 0))
            throw new IOException(com.darkmusic.client.text.Text.get("reset.darkmusic.lock_unsafe", file));
        var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null)
                throw new IOException(com.darkmusic.client.text.Text.get("reset.darkmusic.lock_busy"));
            return new Locked(channel, lock);
        } catch (Exception e) {
            channel.close();
            throw new IOException(com.darkmusic.client.text.Text.get("reset.darkmusic.lock_failed"), e);
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

    private void tools() throws IOException {
        Path root = data.resolve("tools");
        if (!local(root)) throw new IOException(com.darkmusic.client.text.Text.get("reset.darkmusic.tools_linked"));
        // Legacy instance-local executables; configured external paths are never visited.
        executables(root);
        Path managed = root.resolve("managed");
        if (!Files.exists(managed, LinkOption.NOFOLLOW_LINKS)) return;
        if (!local(managed)) {
            keep(managed, null);
            return;
        }
        json(managed.resolve("current.json"), value -> value.isJsonObject()
            && value.getAsJsonObject().has("directory") && value.getAsJsonObject().get("directory").isJsonPrimitive()
            && value.getAsJsonObject().get("directory").getAsString().matches("bundle-[a-fA-F0-9-]+"));
        try (var children = Files.list(managed)) {
            for (Path bundle : children.toList()) {
                if (!bundle.getFileName().toString().matches("(?:bundle|staging)-[a-zA-Z0-9-]+")) continue;
                if (!Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS) || !local(bundle)) {
                    keep(bundle, null);
                    continue;
                }
                executables(bundle);
                json(bundle.resolve("manifest.json"), value -> value.isJsonObject() && value.getAsJsonObject().has("ytDlpRelease")
                    && value.getAsJsonObject().has("ffmpegRelease") && value.getAsJsonObject().has("digests"));
                for (String name : List.of("FFmpeg.LICENSE", "FFmpeg.README"))
                    record(bundle.resolve(name), text -> !text.isBlank() && (text.contains("FFmpeg") || text.contains("GNU")));
                try (var files = Files.list(bundle)) {
                    if (files.findAny().isEmpty()) Files.delete(bundle);
                }
            }
        }
    }

    private void executables(Path directory) {
        for (String name : EXECUTABLES) {
            Path file = directory.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && local(file) && executable(file))
                    Files.delete(file);
                else keep(file, null);
            } catch (IOException e) {
                keep(file, e);
            }
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
