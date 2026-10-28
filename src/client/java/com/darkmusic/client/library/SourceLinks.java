package com.darkmusic.client.library;

import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.Hashes;
import com.google.gson.*;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.net.URI;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * File-bound provenance. Filename-only legacy records are retained but never treated as verified.
 */
public final class SourceLinks implements AutoCloseable {
    private static final Object LOCK = new Object();
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Executor executor;
    private ExecutorService owned;

    public SourceLinks() {
        this(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("darkmusic-source-links").factory(),
            new ThreadPoolExecutor.AbortPolicy()));
        owned = (ExecutorService) executor;
    }

    @Override
    public void close() {
        if (owned != null) owned.shutdownNow();
    }

    public SourceLinks(Executor executor) {
        this.executor = executor;
    }

    public static String safe(String value) {
        try {
            if (value == null || value.length() > 8192) return "";
            URI uri = URI.create(value.strip());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null ? uri.toASCIIString() : "";
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    public String get(Track track) {
        if (track == null) return "";
        String key = track.file().toAbsolutePath() + ":" + track.size() + ":" + track.modified();
        synchronized (cache) {
            if (cache.containsKey(key)) return cache.get(key);
            if (cache.size() >= 128) cache.remove(cache.keySet().iterator().next());
            cache.put(key, "");
        }
        try {
            executor.execute(() -> {
                String url = resolve(track.file());
                synchronized (cache) {
                    if (cache.containsKey(key)) cache.put(key, url);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            cache.remove(key);
        }
        return "";
    }

    /**
     * Explicit display/manual lookup may inspect tags; automatic caption requests never call this.
     */
    public static String resolve(Path audio) {
        String url = read(audio);
        if (url.isEmpty()) url = legacy(audio);
        if (url.isEmpty()) try {
            var info = ExternalAudio.probe(audio);
            if (info.has("format")) url = tags(info.getAsJsonObject("format"));
            if (url.isEmpty() && info.has("streams")) for (var stream : info.getAsJsonArray("streams")) {
                url = tags(stream.getAsJsonObject());
                if (!url.isEmpty()) break;
            }
        } catch (Exception ignored) {
        }
        return url;
    }

    private static String legacy(Path audio) {
        synchronized (LOCK) {
            try {
                if (!Files.isRegularFile(audio, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(audio)) return "";
                var old = json(audio.resolveSibling(".darkmusic-sources.json")).get(audio.getFileName().toString());
                if (old != null && old.isJsonPrimitive()) return safe(old.getAsString());
                for (var entry : json(audio.resolveSibling(".darkmusic-downloads.json")).entrySet()) {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsString().equals(audio.getFileName().toString()))
                        continue;
                    var match = Pattern.compile("(?i)youtube:([A-Za-z0-9_-]{11})(?::original)?").matcher(entry.getKey());
                    if (match.matches()) return "https://www.youtube.com/watch?v=" + match.group(1);
                }
            } catch (Exception ignored) {
            }
            return "";
        }
    }

    private static String tags(JsonObject object) {
        if (!object.has("tags")) return "";
        for (var entry : object.getAsJsonObject("tags").entrySet())
            if (Set.of("purl", "webpage_url", "source_url", "url", "comment").contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                String url = safe(entry.getValue().getAsString());
                if (!url.isEmpty()) return url;
            }
        return "";
    }

    private static JsonObject json(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new JsonObject();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 64_000_000)
            throw new IOException("Invalid source history");
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid source history", invalid);
        }
    }

    public static String contentHash(Path audio) throws IOException {
        return Hashes.sha256(audio, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean valid(Path audio, JsonElement element, boolean contents) throws IOException {
        if (element == null || !element.isJsonObject() || Files.isSymbolicLink(audio)) return false;
        try {
            var o = element.getAsJsonObject();
            var attrs = Files.readAttributes(audio, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() && o.get("schema").getAsInt() == 2
                && attrs.size() == o.get("size").getAsLong() && attrs.lastModifiedTime().toMillis() == o.get("modified").getAsLong()
                && !safe(o.get("url").getAsString()).isEmpty()
                && o.get("sha256").getAsString().matches("[a-f0-9]{64}")
                && (!contents || unchanged(audio, attrs, o.get("sha256").getAsString()));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean unchanged(Path file, BasicFileAttributes before, String expectedHash) throws IOException {
        if (!contentHash(file).equals(expectedHash)) return false;
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return after.isRegularFile() && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
            && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static JsonObject binding(Path audio, String url, String identity) throws IOException {
        if (Files.isSymbolicLink(audio)) throw new IOException("Symbolic-link audio provenance");
        var attrs = Files.readAttributes(audio, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Not a regular audio file");
        var o = new JsonObject();
        o.addProperty("schema", 2);
        o.addProperty("url", safe(url));
        o.addProperty("identity", identity);
        o.addProperty("size", attrs.size());
        o.addProperty("modified", attrs.lastModifiedTime().toMillis());
        o.addProperty("sha256", contentHash(audio));
        var after = Files.readAttributes(audio, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (after.size() != attrs.size() || !after.lastModifiedTime().equals(attrs.lastModifiedTime()) || !Objects.equals(after.fileKey(), attrs.fileKey()))
            throw new IOException("File changed while recording provenance");
        return o;
    }

    public record Verified(String url, String identity, String hash) {
    }

    /**
     * One fresh content check; callers may reuse its hash while the inspected file stays unchanged.
     */
    public static Verified verify(Path audio) {
        try {
            JsonElement value;
            Path file = audio.resolveSibling(".darkmusic-sources.json");
            synchronized (LOCK) {
                var found = json(file).get(audio.getFileName().toString());
                value = found == null ? null : found.deepCopy();
            }
            if (!valid(audio, value, true)) return null;
            synchronized (LOCK) {
                if (!value.equals(json(file).get(audio.getFileName().toString())) || !valid(audio, value, false))
                    return null;
            }
            var o = value.getAsJsonObject();
            return new Verified(o.get("url").getAsString(), o.has("identity") ? o.get("identity").getAsString() : "", o.get("sha256").getAsString());
        } catch (Exception unavailable) {
            return null;
        }
    }

    public static String read(Path audio) {
        var verified = verify(audio);
        return verified == null ? "" : verified.url();
    }

    public static boolean matchesArchive(Path audio, String identity) {
        var verified = verify(audio);
        return verified != null && identity.equals(verified.identity());
    }

    /**
     * Fast indexing only; Skip and automatic network actions revalidate the contents.
     */
    public static Map<String, String> readDirectory(Path directory) {
        return readDirectory(directory, false);
    }

    public static Map<String, String> archiveBindings(Path directory) {
        return readDirectory(directory, true);
    }

    private static Map<String, String> readDirectory(Path directory, boolean archiveIdentity) {
        synchronized (LOCK) {
            var result = new HashMap<String, String>();
            try {
                for (var entry : json(directory.resolve(".darkmusic-sources.json")).entrySet()) {
                    try {
                        Path audio = directory.resolve(entry.getKey()).normalize();
                        if (audio.getParent().equals(directory.normalize()) && valid(audio, entry.getValue(), false))
                            result.put(entry.getKey(), entry.getValue().getAsJsonObject().get(archiveIdentity ? "identity" : "url").getAsString());
                    } catch (IOException |
                             RuntimeException unavailable) { /* One stale entry cannot hide its neighbours. */ }
                }
            } catch (Exception ignored) {
            }
            return Map.copyOf(result);
        }
    }

    public static boolean record(Path audio, String url) {
        return record(audio, url, "");
    }

    public static boolean record(Path audio, String url, String identity) {
        return record(audio, url, identity, null);
    }

    public static boolean record(Path audio, String url, String identity, String expectedHash) {
        try {
            var association = safe(url).isEmpty() ? null : binding(audio, url, identity);
            if (association != null && expectedHash != null && !expectedHash.equals(association.get("sha256").getAsString()))
                throw new IOException("Audio changed before provenance repair");
            synchronized (LOCK) {
                Path file = audio.resolveSibling(".darkmusic-sources.json");
                var data = json(file);
                if (association == null) {
                    if (data.remove(audio.getFileName().toString()) == null) return true;
                } else {
                    if (!valid(audio, association, false))
                        throw new IOException("Audio changed before provenance commit");
                    data.add(audio.getFileName().toString(), association);
                }
                ConfigStore.atomicWrite(file, ConfigStore.JSON.toJson(data));
            }
            return true;
        } catch (Exception failure) {
            LoggerFactory.getLogger(SourceLinks.class).warn("Could not save source association", failure);
            return false;
        }
    }

    public static void forget(Path audio) throws IOException {
        synchronized (LOCK) {
            Path file = audio.resolveSibling(".darkmusic-sources.json");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
            var data = json(file);
            if (data.remove(audio.getFileName().toString()) != null)
                ConfigStore.atomicWrite(file, ConfigStore.JSON.toJson(data));
        }
    }

    /**
     * Called only after the media operation succeeded; the caller owns rollback on failure.
     */
    public static void renamed(Path source, Path target, String verifiedSource) throws IOException {
        renamed(source, target, verifiedSource, null);
    }

    public static void renamed(Path source, Path target, String verifiedSource, String expectedHash) throws IOException {
        synchronized (LOCK) {
            Path file = source.resolveSibling(".darkmusic-sources.json");
            var data = json(file);
            var prior = data.get(source.getFileName().toString());
            if (prior == null) return;
            data.remove(source.getFileName().toString());
            if (prior.isJsonObject() && !verifiedSource.isEmpty() && verifiedSource.equals(prior.getAsJsonObject().get("url").getAsString())) {
                var o = prior.getAsJsonObject();
                String identity = o.has("identity") ? o.get("identity").getAsString() : "";
                var next = binding(target, o.get("url").getAsString(), identity);
                if (expectedHash != null && !expectedHash.equals(next.get("sha256").getAsString()))
                    throw new IOException(Text.get("library.darkmusic.file_changed"));
                data.add(target.getFileName().toString(), next);
            } else if (prior.isJsonPrimitive()) data.add(target.getFileName().toString(), prior);
            ConfigStore.atomicWrite(file, ConfigStore.JSON.toJson(data));
        }
    }
}
