package com.darkmusic.client.cleanup;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.download.DownloadEncoding;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.library.*;
import com.google.gson.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Shared, local-only source identities. Indexing and live existence checks never run in rendering.
 */
public final class LibrarySourceIndex implements AutoCloseable {
    public record Existing(Track track, String format, Set<DownloadEncoding.Format> requested, String stamp) {
        public boolean sameFormat(DownloadEncoding.Format value) {
            return value == DownloadEncoding.Format.MP3 ? format.equals("mp3") : requested.contains(value);
        }
    }

    public record Lookup(List<Existing> confirmed, List<Track> possible, boolean complete) {
    }

    private record Cached(String id, String stamp, String source) {
    }

    private record CacheFile(int schema, List<Cached> entries) {
    }

    private record Request(List<Track> tracks, Map<Path, String> roots, boolean complete) {
    }

    private final Path file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-source-index").factory());
    private final Map<String, Cached> cache = new HashMap<>();
    private volatile Map<String, List<Existing>> index = Map.of();
    private volatile Map<String, List<Track>> possible = Map.of();
    private volatile Map<Path, String> roots = Map.of();
    private volatile boolean complete, closed;
    private volatile long revision;
    private Request pending;
    private boolean running;
    private boolean cacheDirty;
    private final Map<String, String> deleted = new HashMap<>();

    public LibrarySourceIndex(Path data) {
        file = data.resolve("source-index.json");
        worker.execute(() -> {
            try {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) < 64_000_000)
                    try (var reader = Files.newBufferedReader(file)) {
                        var saved = new Gson().fromJson(reader, CacheFile.class);
                        if (saved != null && saved.schema == 2 && saved.entries != null) for (var v : saved.entries)
                            if (v != null && v.id != null && v.stamp != null && v.source != null && v.id.length() <= 16384 && v.stamp.length() <= 128 && v.source.length() <= 8192 && cache.size() < DuplicatePolicy.MAX_TRACKS)
                                cache.put(v.id, v);
                    }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Cannot read source index cache", e);
            }
        });
    }

    public long revision() {
        return revision;
    }

    public synchronized void roots(Map<Path, String> values) {
        if (!roots.equals(values)) {
            roots = Map.copyOf(values);
            complete = false;
            revision++;
        }
    }

    public synchronized void update(List<Track> tracks, Map<Path, String> roots, boolean complete) {
        if (closed) return;
        this.roots = Map.copyOf(roots);
        this.complete = false;
        pending = new Request(tracks, this.roots, complete);
        if (!running) {
            running = true;
            worker.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (this) {
                request = pending;
                pending = null;
                if (request == null) {
                    running = false;
                    return;
                }
            }
            var result = new HashMap<String, List<Existing>>();
            var previousCache = new HashMap<>(cache);
            var provenance = new HashMap<Path, Map<String, String>>();
            var bindings = new HashMap<Path, Map<String, String>>();
            var history = new HashMap<Path, Map<String, List<String>>>();
            boolean healthy = request.complete;
            int processed = 0;
            long nextPublication = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            for (Track t : request.tracks) {
                if (closed) return;
                try {
                    var attrs = DuplicateScanner.attributes(t);
                    String stamp = DuplicateScanner.stamp(t, attrs);
                    String source = provenance.computeIfAbsent(t.file().getParent(), SourceLinks::readDirectory).getOrDefault(t.file().getFileName().toString(), "");
                    var archive = history.computeIfAbsent(t.file().getParent(), LibrarySourceIndex::archive);
                    var archiveKeys = archive.getOrDefault(t.file().getFileName().toString(), List.of());
                    synchronized (this) {
                        if (deleted.containsKey(t.id()) && !deleted.get(t.id()).equals(stamp)) deleted.remove(t.id());
                    }
                    var modes = new HashSet<DownloadEncoding.Format>();
                    var ids = new HashSet<String>();
                    String sourceId = DuplicateMatcher.sourceId(source);
                    if (!sourceId.isEmpty()) ids.add(sourceId);
                    for (String archiveKey : archiveKeys) {
                        if (!archiveKey.equals(bindings.computeIfAbsent(t.file().getParent(), SourceLinks::archiveBindings).get(t.file().getFileName().toString())))
                            continue;
                        String key = archiveKey;
                        boolean original = key.endsWith(":original");
                        if (original) key = key.substring(0, key.length() - 9);
                        int split = key.indexOf(':');
                        if (split > 0) ids.add(identity(key.substring(0, split), key.substring(split + 1), source));
                        modes.add(original ? DownloadEncoding.Format.ORIGINAL : DownloadEncoding.Format.MP3);
                    }
                    cache.put(t.id(), new Cached(t.id(), stamp, source));
                    String name = t.file().getFileName().toString();
                    String format = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                    var existing = new Existing(t, format, Set.copyOf(modes), stamp);
                    for (String id : ids)
                        if (!id.isEmpty()) result.computeIfAbsent(id, k -> new ArrayList<>()).add(existing);
                } catch (Exception e) {
                    healthy = false;
                }
                if (++processed % 100 == 0 && System.nanoTime() >= nextPublication) {
                    publish(result, false);
                    nextPublication = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                }
            }
            publish(result, healthy);
            var present = new HashSet<String>();
            for (Track track : request.tracks) present.add(track.id());
            cache.keySet().removeIf(id -> !present.contains(id) && (request.complete || request.roots.values().stream().noneMatch(root -> id.startsWith(root + ":"))));
            while (cache.size() > DuplicatePolicy.MAX_TRACKS) cache.remove(cache.keySet().iterator().next());
            try {
                cacheDirty |= !cache.equals(previousCache);
                if (!cacheDirty) continue;
                String json = new Gson().toJson(new CacheFile(2, List.copyOf(cache.values())));
                if (json.length() < 64_000_000) {
                    ConfigStore.atomicWrite(file, json);
                    cacheDirty = false;
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Cannot save source index", e);
            }
        }
    }

    static Map<String, List<String>> archive(Path directory) {
        var map = new HashMap<String, List<String>>();
        try {
            Path p = directory.resolve(".darkmusic-downloads.json");
            if (Files.isRegularFile(p) && Files.size(p) <= 16_000_000) try (var reader = Files.newBufferedReader(p)) {
                for (var item : JsonParser.parseReader(reader).getAsJsonObject().entrySet())
                    if (item.getValue().isJsonPrimitive())
                        map.computeIfAbsent(item.getValue().getAsString(), k -> new ArrayList<>()).add(item.getKey());
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private synchronized void publish(Map<String, List<Existing>> map, boolean complete) {
        var result = new HashMap<String, List<Existing>>();
        map.forEach((key, value) -> result.put(key, value.stream().filter(v -> !deleted.containsKey(v.track.id())).toList()));
        index = Map.copyOf(result);
        this.complete = complete;
        revision++;
    }

    public void evidence(List<DuplicateResult> pairs) {
        var map = new HashMap<String, List<Track>>();
        for (var p : pairs)
            for (Track t : List.of(p.a(), p.b()))
                map.computeIfAbsent(DuplicateMatcher.normalize(t.title()), k -> new ArrayList<>()).add(t);
        var result = new HashMap<String, List<Track>>();
        map.forEach((k, v) -> result.put(k, v.stream().distinct().limit(8).toList()));
        possible = Map.copyOf(result);
        revision++;
    }

    public static String identity(String extractor, String mediaId, String url) {
        String known = DuplicateMatcher.sourceId(url);
        if (!known.isEmpty()) return known;
        String e = Objects.toString(extractor, "").toLowerCase(Locale.ROOT);
        if (e.startsWith("youtube") && mediaId.matches("[A-Za-z0-9_-]{11}")) return "youtube:" + mediaId;
        return e + ":" + mediaId;
    }

    public Lookup lookup(DownloadModels.Entry entry) {
        return new Lookup(index.getOrDefault(identity(entry.extractor(), entry.mediaId(), entry.url()), List.of()), possible.getOrDefault(DuplicateMatcher.normalize(entry.title()), List.of()), complete);
    }

    /**
     * A stale or offline match never silently skips a download. Called only by background download preparation.
     */
    public List<Existing> verified(DownloadModels.Entry entry) {
        return lookup(entry).confirmed.stream().filter(v -> {
            try {
                String source = SourceLinks.read(v.track.file());
                return !source.isEmpty() && identity(entry.extractor(), entry.mediaId(), entry.url()).equals(DuplicateMatcher.sourceId(source))
                    && roots.containsValue(v.track.rootId()) && v.stamp.equals(DuplicateScanner.stamp(v.track, DuplicateScanner.attributes(v.track)));
            } catch (Exception unavailable) {
                return false;
            }
        }).toList();
    }

    public void saved(DownloadModels.Entry entry, Path path, DownloadEncoding.Format format) {
        if (closed) return;
        try {
            worker.execute(() -> {
                try {
                    var root = roots.entrySet().stream().filter(e -> path.toAbsolutePath().normalize().startsWith(e.getKey())).findFirst().orElse(null);
                    if (root == null) return;
                    var a = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                    var t = new Track(root.getValue(), root.getKey().relativize(path).toString().replace('\\', '/'), path, entry.title(), "", "", 0, a.size(), a.lastModifiedTime().toMillis(), "");
                    String name = path.getFileName().toString();
                    var existing = new Existing(t, name.substring(name.lastIndexOf('.') + 1), Set.of(format), DuplicateScanner.stamp(t, a));
                    synchronized (this) {
                        var map = new HashMap<>(index);
                        var key = identity(entry.extractor(), entry.mediaId(), entry.url());
                        var values = new ArrayList<>(map.getOrDefault(key, List.of()));
                        values.removeIf(v -> v.track.id().equals(t.id()));
                        values.add(existing);
                        map.put(key, List.copyOf(values));
                        index = Map.copyOf(map);
                        revision++;
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    public synchronized void deleted(Set<String> ids) {
        index.values().forEach(values -> values.stream().filter(v -> ids.contains(v.track.id())).forEach(v -> deleted.put(v.track.id(), v.stamp)));
        var map = new HashMap<String, List<Existing>>();
        index.forEach((k, v) -> map.put(k, v.stream().filter(e -> !ids.contains(e.track.id())).toList()));
        index = Map.copyOf(map);
        revision++;
    }

    public CompletableFuture<Void> settled() {
        return CompletableFuture.runAsync(() -> {
        }, worker);
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
