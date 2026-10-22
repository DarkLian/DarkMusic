package com.darkmusic.client.library;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.text.Text;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only local library; all file work stays off Minecraft's thread.
 */
public final class MusicLibrary implements AutoCloseable {
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-scan").factory());
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("darkmusic-watch").factory());
    private final AtomicBoolean running = new AtomicBoolean(), requested = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong(), revisions = new AtomicLong();
    private final MetadataReader metadata = new MetadataReader();
    private final Path cacheFile;
    private final Map<String, Cached> cache = new HashMap<>();
    private final AtomicBoolean delayed = new AtomicBoolean();
    private volatile Map<Path, String> roots = Map.of();
    private volatile Path root;
    private volatile String rootId;
    private volatile boolean closed;
    private volatile WatchService watcher;
    private volatile Set<Path> watchedDirectories = Set.of();
    private boolean cacheDirty;
    private volatile Snapshot snapshot = new Snapshot(0, List.of(), false, Text.get("library.status.waiting"));

    public MusicLibrary(Path data) {
        cacheFile = data.resolve("library-cache.json");
        scanner.execute(() -> {
            try {
                if (Files.exists(cacheFile)) try (var reader = Files.newBufferedReader(cacheFile)) {
                    Cached[] entries = ConfigStore.JSON.fromJson(reader, Cached[].class);
                    if (entries != null) for (Cached c : entries)
                        if (c != null && c.root != null && c.path != null && c.title != null && c.artist != null && c.album != null && c.problem != null && Double.isFinite(c.duration) && c.duration >= 0)
                            cache.put(c.root + ":" + c.path, c);
                }
            } catch (Exception ignored) {
                cache.clear();
            }
        });
        monitor.scheduleWithFixedDelay(this::poll, 2, 2, TimeUnit.SECONDS);
        monitor.scheduleWithFixedDelay(this::rescan, 120, 120, TimeUnit.SECONDS);
    }

    public static boolean supported(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (com.darkmusic.client.audio.AudioFormats.candidate(file)) return true;
        if (n.startsWith(".") || !Files.isRegularFile(file)) return false;
        try {
            return com.darkmusic.client.audio.AudioFormats.detect(file) != com.darkmusic.client.audio.AudioFormats.Container.UNKNOWN;
        } catch (IOException ignored) {
            return false;
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public Path root() {
        return root;
    }

    public String rootId() {
        return rootId;
    }

    public Map<Path, String> roots() {
        return roots;
    }

    public Path directory(String id) {
        return roots.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public synchronized void setRoot(Path directory, String id) {
        setRoots(Map.of(directory.toAbsolutePath().normalize(), id), directory);
    }

    public synchronized void setRoots(Map<Path, String> values, Path destination) {
        roots = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        root = destination.toAbsolutePath().normalize();
        rootId = roots.get(root);
        if (rootId == null) throw new IllegalArgumentException("Write destination must be a registered music folder");
        generation.incrementAndGet();
        snapshot = new Snapshot(revisions.incrementAndGet(), List.of(), true, Text.get("library.status.scanning"));
        rescan();
    }

    public void requestRescan() {
        if (!closed && delayed.compareAndSet(false, true)) try {
            monitor.schedule(() -> {
                delayed.set(false);
                rescan();
            }, 400, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            delayed.set(false);
        }
    }

    public void rescan() {
        if (closed || root == null) return;
        requested.set(true);
        if (running.compareAndSet(false, true)) try {
            scanner.execute(() -> {
                try {
                    while (!closed && requested.getAndSet(false)) scan();
                } finally {
                    running.set(false);
                    if (requested.get() && !closed) rescan();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            running.set(false);
        }
    }

    private void scan() {
        Map<Path, String> selected;
        long ticket;
        synchronized (this) {
            selected = roots;
            ticket = generation.get();
        }
        List<Track> tracks = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        Set<String> completeRoots = new HashSet<>();
        List<String> failures = new ArrayList<>();
        long[] partialAt = {System.nanoTime()};
        publish(ticket, new Snapshot(revisions.incrementAndGet(), snapshot.tracks(), true, Text.get("library.status.scanning"), snapshot.folders()));
        for (var entry : selected.entrySet()) {
            Path directory = entry.getKey();
            String id = entry.getValue();
            int[] unreadable = {0};
            folders.add("@" + id);
            try {
                if (!Files.isDirectory(directory) || !Files.isReadable(directory))
                    throw new IOException(Text.get("library.status.unavailable"));
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (closed || ticket != generation.get()) return FileVisitResult.TERMINATE;
                        if (!dir.equals(directory) && dir.getFileName().toString().startsWith(".darkmusic-work-"))
                            return FileVisitResult.SKIP_SUBTREE;
                        if (dir.toAbsolutePath().normalize().equals(cacheFile.toAbsolutePath().getParent().resolve("downloads/work")))
                            return FileVisitResult.SKIP_SUBTREE;
                        directories.add(dir);
                        String relative = directory.relativize(dir).toString().replace('\\', '/');
                        if (!relative.isEmpty()) folders.add("@" + id + "/" + relative);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (closed || ticket != generation.get()) return FileVisitResult.TERMINATE;
                        if (attrs.isRegularFile() && supported(file)) {
                            String relative = directory.relativize(file).toString().replace('\\', '/');
                            Cached old = cache.get(id + ":" + relative);
                            tracks.add(old != null && old.size == attrs.size() && old.modified == attrs.lastModifiedTime().toMillis()
                                ? old.track(file) : metadata.read(id, relative, file, attrs.size(), attrs.lastModifiedTime().toMillis()));
                            if (tracks.size() % 100 == 0 && System.nanoTime() - partialAt[0] >= 250_000_000L) {
                                partialAt[0] = System.nanoTime();
                                publish(ticket, new Snapshot(revisions.incrementAndGet(), List.copyOf(tracks), true, Text.get("library.status.scanned_count", tracks.size()), folders));
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        unreadable[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
                if (unreadable[0] == 0) completeRoots.add(id);
                else failures.add(directory + ": " + Text.get("library.status.unreadable", unreadable[0]));
            } catch (Exception e) {
                failures.add(directory + ": " + Text.failure(e, "library.status.scan_failed"));
            }
        }
        if (closed || ticket != generation.get()) return;
        tracks.sort(Comparator.comparing(Track::relativePath, String.CASE_INSENSITIVE_ORDER).thenComparing(Track::rootId));
        var next = new HashMap<>(cache);
        next.entrySet().removeIf(e -> completeRoots.contains(e.getValue().root));
        for (Track t : tracks) next.put(t.id(), Cached.of(t));
        if (!next.equals(cache) || cacheDirty) {
            cache.clear();
            cache.putAll(next);
            try {
                ConfigStore.atomicWrite(cacheFile, ConfigStore.JSON.toJson(cache.values()));
                cacheDirty = false;
            } catch (IOException e) {
                cacheDirty = true;
                failures.add(Text.failure(e, "library.status.cache_failed"));
            }
        }
        replaceWatcher(directories);
        publish(ticket, new Snapshot(revisions.incrementAndGet(), tracks, false, String.join(" | ", failures), folders, completeRoots.size() == selected.size(), completeRoots));
    }

    private synchronized void publish(long ticket, Snapshot value) {
        if (!closed && ticket == generation.get()) snapshot = value;
    }

    private void replaceWatcher(List<Path> directories) {
        Set<Path> desired = Set.copyOf(directories);
        if (watcher != null && desired.equals(watchedDirectories)) return;
        closeWatcher();
        try {
            var service = FileSystems.getDefault().newWatchService();
            watcher = service;
            for (Path dir : directories)
                dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            watchedDirectories = desired;
        } catch (Exception ignored) {
            closeWatcher(); /* Polling remains active. */
        }
    }

    private void poll() {
        WatchService service = watcher;
        if (service == null || closed) return;
        boolean changed = false;
        try {
            WatchKey key;
            while ((key = service.poll()) != null) {
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        changed = true;
                        continue;
                    }
                    if (!(event.context() instanceof Path relative)) continue;
                    Path path = ((Path) key.watchable()).resolve(relative);
                    String name = path.getFileName().toString();
                    if (path.startsWith(cacheFile.toAbsolutePath().getParent().resolve("downloads/work")) || name.startsWith(".darkmusic-") && (name.endsWith(".tmp") || name.equals(".darkmusic-downloads.json")))
                        continue;
                    changed |= event.kind() == StandardWatchEventKinds.ENTRY_DELETE || supported(path) || Files.isDirectory(path) || watchedDirectories.contains(path);
                }
                if (!key.reset()) {
                    watchedDirectories = Set.of();
                    changed = true;
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            return;
        }
        if (changed) requestRescan();
    }

    private void closeWatcher() {
        WatchService old = watcher;
        watcher = null;
        watchedDirectories = Set.of();
        if (old != null) try {
            old.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        monitor.shutdownNow();
        closeWatcher();
        scanner.shutdownNow();
    }

    public boolean awaitStopped(long seconds) throws InterruptedException {
        return scanner.awaitTermination(seconds, TimeUnit.SECONDS) && monitor.awaitTermination(seconds, TimeUnit.SECONDS);
    }

    public record Snapshot(long revision, List<Track> tracks, boolean scanning, String message, List<String> folders,
                           boolean complete, Set<String> completeRoots) {
        public Snapshot {
            tracks = List.copyOf(tracks);
            folders = List.copyOf(folders);
            completeRoots = Set.copyOf(completeRoots);
        }

        public Snapshot(long revision, List<Track> tracks, boolean scanning, String message, List<String> folders, boolean complete) {
            this(revision, tracks, scanning, message, folders, complete, Set.of());
        }

        public Snapshot(long revision, List<Track> tracks, boolean scanning, String message) {
            this(revision, tracks, scanning, message, List.of());
        }

        public Snapshot(long revision, List<Track> tracks, boolean scanning, String message, List<String> folders) {
            this(revision, tracks, scanning, message, folders, false);
        }
    }

    private record Cached(String root, String path, String title, String artist, String album,
                          double duration, long size, long modified, String problem) {
        static Cached of(Track t) {
            return new Cached(t.rootId(), t.relativePath(), t.title(), t.artist(), t.album(), t.duration(), t.size(), t.modified(), t.problem());
        }

        Track track(Path file) {
            return new Track(root, path, file, title, artist, album, duration, size, modified, problem);
        }
    }
}
