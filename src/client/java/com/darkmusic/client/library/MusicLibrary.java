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
    private final ExecutorService rootScanner = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("darkmusic-root-", 0).factory());
    private final Map<Path, RootJob> rootJobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.function.Consumer<Path> beforeRoot;
    private final long rootTimeoutNanos;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("darkmusic-watch").factory());
    private final AtomicBoolean running = new AtomicBoolean(), requested = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong(), revisions = new AtomicLong();
    private final MetadataReader metadata = new MetadataReader();
    private final Path cacheFile;
    private final Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean delayed = new AtomicBoolean();
    private volatile Map<Path, String> roots = Map.of();
    private volatile Path root;
    private volatile String rootId;
    private volatile boolean closed;
    private volatile WatchService watcher;
    private volatile Set<Path> watchedDirectories = Set.of();
    private boolean cacheDirty;
    private final AtomicBoolean watchWarning = new AtomicBoolean();
    private volatile Snapshot snapshot = new Snapshot(0, List.of(), false, Text.get("library.status.waiting"));

    public MusicLibrary(Path data) {
        this(data, path -> {
        }, 15_000);
    }

    MusicLibrary(Path data, java.util.function.Consumer<Path> beforeRoot, long timeoutMillis) {
        this.beforeRoot = beforeRoot;
        rootTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
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
        var selected = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        Path nextRoot = destination.toAbsolutePath().normalize();
        String nextId = selected.get(nextRoot);
        if (nextId == null) throw new IllegalArgumentException("Write destination must be a registered music folder");
        var previousRoots = roots;
        roots = selected;
        root = nextRoot;
        rootId = nextId;
        if (previousRoots.equals(selected)) return;
        var retainedIds = new HashSet<String>();
        selected.forEach((path, id) -> {
            if (id.equals(previousRoots.get(path))) retainedIds.add(id);
        });
        generation.incrementAndGet();
        var retainedTracks = snapshot.tracks().stream().filter(t -> retainedIds.contains(t.rootId())).toList();
        var retainedFolders = snapshot.folders().stream().filter(f -> retainedIds.contains(folderRoot(f))).toList();
        snapshot = new Snapshot(revisions.incrementAndGet(), retainedTracks, true, Text.get("library.status.scanning"), retainedFolders);
        rescan();
    }

    private static String folderRoot(String folder) {
        int slash = folder.indexOf('/');
        return folder.startsWith("@") ? folder.substring(1, slash < 0 ? folder.length() : slash) : "";
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
        var prior = snapshot;
        rootJobs.entrySet().removeIf(entry -> !selected.containsKey(entry.getKey()) && entry.getValue().result.isDone());
        var jobs = new LinkedHashMap<Path, RootJob>();
        for (var entry : selected.entrySet()) {
            RootJob job = rootJobs.compute(entry.getKey(), (path, existing) -> {
                if (existing != null && !existing.result.isDone()) {
                    if (existing.ticket != ticket) {
                        existing.cancelled = true;
                        existing.result.whenComplete((value, failure) -> {
                            if (!closed) requestRescan();
                        });
                    }
                    return existing;
                }
                if (existing != null && existing.ticket == ticket && !existing.consumed) return existing;
                var next = new RootJob(path, entry.getValue(), ticket);
                rootScanner.execute(next);
                return next;
            });
            jobs.put(entry.getKey(), job);
        }
        boolean waiting;
        List<Long> previousVersions = null;
        boolean wasWaiting = false;
        Snapshot value = prior;
        do {
            if (closed || ticket != generation.get()) return;
            waiting = false;
            for (var job : jobs.values()) {
                if (!job.result.isDone() && System.nanoTime() - job.progressAt < rootTimeoutNanos) waiting = true;
                else if (!job.result.isDone()) job.timedOut = true;
            }
            var versions = jobs.values().stream().map(job -> job.revision * 2 + (job.timedOut ? 1 : 0)).toList();
            if (!versions.equals(previousVersions) || waiting != wasWaiting) {
                value = combined(ticket, selected, jobs, prior, waiting);
                if (waiting) publish(ticket, value);
                previousVersions = versions;
                wasWaiting = waiting;
            }
            if (!waiting) {
                var next = new HashMap<>(cache);
                var completeRoots = value.completeRoots();
                next.entrySet().removeIf(e -> completeRoots.contains(e.getValue().root));
                for (Track track : value.tracks()) next.put(track.id(), Cached.of(track));
                if (!next.equals(cache) || cacheDirty) {
                    cache.clear();
                    cache.putAll(next);
                    try {
                        ConfigStore.atomicWrite(cacheFile, ConfigStore.JSON.toJson(cache.values()));
                        cacheDirty = false;
                    } catch (IOException e) {
                        cacheDirty = true;
                        org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Could not save library index", e);
                    }
                }
                var directories = new ArrayList<Path>();
                for (var job : jobs.values())
                    if (job.ticket == ticket && job.result.isDone()) {
                        directories.addAll(job.result.join().directories);
                        job.consumed = true;
                    }
                replaceWatcher(directories);
                publish(ticket, value);
            } else try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (waiting);
    }

    private Snapshot combined(long ticket, Map<Path, String> selected, Map<Path, RootJob> jobs, Snapshot prior, boolean scanning) {
        var tracks = new ArrayList<Track>();
        var folders = new LinkedHashSet<String>();
        var priorTracks = prior.tracks().stream().collect(java.util.stream.Collectors.groupingBy(Track::rootId));
        var priorFolders = prior.folders().stream().collect(java.util.stream.Collectors.groupingBy(MusicLibrary::folderRoot));
        var complete = new HashSet<String>();
        var failures = new ArrayList<String>();
        for (var entry : selected.entrySet()) {
            var job = jobs.get(entry.getKey());
            RootResult result = job.ticket == ticket ? job.result.getNow(job.partial) : null;
            String prefix = "@" + entry.getValue();
            if (result == null || !result.complete) {
                var retained = new LinkedHashMap<String, Track>();
                for (var track : priorTracks.getOrDefault(entry.getValue(), List.of())) retained.put(track.id(), track);
                folders.addAll(priorFolders.getOrDefault(entry.getValue(), List.of()));
                if (result != null) {
                    for (var track : result.tracks) retained.put(track.id(), track);
                    folders.addAll(result.folders);
                }
                tracks.addAll(retained.values());
            } else {
                tracks.addAll(result.tracks);
                folders.addAll(result.folders);
            }
            folders.add(prefix);
            if (result != null && result.complete) complete.add(entry.getValue());
            if (result != null && !result.message.isBlank()) failures.add(entry.getKey() + ": " + result.message);
            else if (job.timedOut && !job.result.isDone())
                failures.add(entry.getKey() + ": " + Text.get("library.status.slow_root"));
        }
        tracks.sort(Comparator.comparing(Track::relativePath, String.CASE_INSENSITIVE_ORDER).thenComparing(Track::rootId));
        return new Snapshot(revisions.incrementAndGet(), tracks, scanning, failures.isEmpty() && scanning ? Text.get("library.status.scanning") : String.join(" | ", failures), List.copyOf(folders), complete.size() == selected.size(), complete);
    }

    private record RootResult(List<Track> tracks, List<Path> directories, List<String> folders, boolean complete,
                              String message) {
        RootResult {
            tracks = List.copyOf(tracks);
            directories = List.copyOf(directories);
            folders = List.copyOf(folders);
        }
    }

    private final class RootJob implements Runnable {
        private final Path directory;
        private final String id;
        private final long ticket;
        private final java.util.concurrent.CompletableFuture<RootResult> result = new java.util.concurrent.CompletableFuture<>();
        private volatile RootResult partial;
        private volatile long progressAt = System.nanoTime();
        private volatile long revision;
        private volatile boolean cancelled, timedOut, consumed;

        private RootJob(Path directory, String id, long ticket) {
            this.directory = directory;
            this.id = id;
            this.ticket = ticket;
        }

        @Override
        public void run() {
            var tracks = new ArrayList<Track>();
            var directories = new ArrayList<Path>();
            var folders = new ArrayList<String>();
            folders.add("@" + id);
            int[] unreadable = {0};
            long[] publishedAt = {System.nanoTime()};
            boolean complete = false;
            String message = "";
            try {
                beforeRoot.accept(directory);
                if (!Files.isDirectory(directory) || !Files.isReadable(directory))
                    throw new IOException(Text.get("library.status.unavailable"));
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        progressAt = System.nanoTime();
                        if (cancelled || closed || ticket != generation.get()) return FileVisitResult.TERMINATE;
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
                        progressAt = System.nanoTime();
                        if (cancelled || closed || ticket != generation.get()) return FileVisitResult.TERMINATE;
                        if (attrs.isRegularFile() && supported(file)) {
                            String relative = directory.relativize(file).toString().replace('\\', '/');
                            Cached old = cache.get(id + ":" + relative);
                            tracks.add(old != null && old.size == attrs.size() && old.modified == attrs.lastModifiedTime().toMillis()
                                ? old.track(file) : metadata.read(id, relative, file, attrs.size(), attrs.lastModifiedTime().toMillis()));
                            if (tracks.size() % 100 == 0 && System.nanoTime() - publishedAt[0] > 250_000_000L) {
                                partial = new RootResult(tracks, directories, folders, false, "");
                                revision++;
                                publishedAt[0] = System.nanoTime();
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        progressAt = System.nanoTime();
                        unreadable[0]++;
                        return cancelled || closed || ticket != generation.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
                complete = !cancelled && !closed && ticket == generation.get() && unreadable[0] == 0;
                if (unreadable[0] != 0) message = Text.get("library.status.unreadable", unreadable[0]);
            } catch (Exception failure) {
                message = Text.failure(failure, "library.status.scan_failed");
            } finally {
                result.complete(new RootResult(tracks, directories, folders, complete, message));
                revision++;
                if (timedOut && !closed) requestRescan();
            }
        }
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
            var registered = new HashSet<Path>();
            for (Path dir : directories)
                try {
                    dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                    registered.add(dir);
                } catch (Exception failure) {
                    watchFailed(failure);
                }
            watchedDirectories = Set.copyOf(registered);
        } catch (Exception failure) {
            closeWatcher();
            watchFailed(failure);
        }
    }

    private void watchFailed(Exception failure) {
        if (watchWarning.compareAndSet(false, true))
            org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Some music folders cannot be watched; successful watches are retained and periodic scans remain active", failure);
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
        rootJobs.values().forEach(job -> job.cancelled = true);
        rootScanner.shutdownNow();
    }

    public boolean awaitStopped(long seconds) throws InterruptedException {
        return scanner.awaitTermination(seconds, TimeUnit.SECONDS) && monitor.awaitTermination(seconds, TimeUnit.SECONDS)
            && rootScanner.awaitTermination(seconds, TimeUnit.SECONDS);
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
