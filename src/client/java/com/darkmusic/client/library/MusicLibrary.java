package com.darkmusic.client.library;

import com.darkmusic.client.media.AudioFormats;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.text.Text;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Read-only local library; all file work stays off Minecraft's thread.
 */
public final class MusicLibrary implements AutoCloseable {
    private final Set<Path> reportedWork = ConcurrentHashMap.newKeySet();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-scan").factory());
    private final ExecutorService rootScanner = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("darkmusic-root-", 0).factory());
    private final Map<Path, RootJob> rootJobs = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> storeWalkers = new ConcurrentHashMap<>();
    private final Consumer<Path> beforeRoot;
    private final Predicate<Path> readableDirectory;
    private final long rootTimeoutNanos;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("darkmusic-watch").factory());
    private final AtomicBoolean running = new AtomicBoolean(), requested = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong(), revisions = new AtomicLong();
    private final MetadataReader metadata = new MetadataReader();
    private final Path cacheFile;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean delayed = new AtomicBoolean();
    private volatile Map<Path, String> roots = Map.of();
    private volatile Path root;
    private volatile String rootId;
    private volatile boolean closed;
    private final CompletableFuture<Void> watchClosed = new CompletableFuture<>();
    private final LibraryWatcher watcher;
    private boolean cacheDirty;
    private volatile Snapshot snapshot = new Snapshot(0, List.of(), false, Text.get("library.status.waiting"));

    public MusicLibrary(Path data) {
        this(data, path -> {
        }, 15_000);
    }

    MusicLibrary(Path data, Consumer<Path> beforeRoot, long timeoutMillis) {
        this(data, beforeRoot, timeoutMillis, Files::isReadable);
    }

    MusicLibrary(Path data, Consumer<Path> beforeRoot, long timeoutMillis, Predicate<Path> readableDirectory) {
        this.beforeRoot = beforeRoot;
        this.readableDirectory = readableDirectory;
        rootTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        cacheFile = data.resolve("library-cache.json");
        watcher = new LibraryWatcher(() -> closed, this::requestRescan, this::knownFile, cacheFile.toAbsolutePath().getParent().resolve("downloads/work"));
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
        monitor.scheduleWithFixedDelay(watcher::poll, 2, 2, TimeUnit.SECONDS);
        monitor.scheduleWithFixedDelay(this::rescan, 120, 120, TimeUnit.SECONDS);
    }

    public static boolean supported(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.matches(".*\\.(heic|heif|avif|mov|mkv)$")) return false;
        if (AudioFormats.candidate(file)) return true;
        if (n.startsWith(".") || !Files.isRegularFile(file)) return false;
        try {
            return AudioFormats.detect(file) != AudioFormats.Container.UNKNOWN;
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
        } catch (RejectedExecutionException ignored) {
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
        } catch (RejectedExecutionException ignored) {
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
        var prior = withPersisted(selected, snapshot);
        var jobs = startJobs(selected, ticket);
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
                saveIndex(value);
                watcher.replace(consumeDirectories(jobs, ticket));
                publish(ticket, value);
            } else try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (waiting);
    }

    /**
     * The last snapshot plus the persisted index of the selected roots: a first scan after restart also needs the
     * index when a subtree is unavailable.
     */
    private Snapshot withPersisted(Map<Path, String> selected, Snapshot prior) {
        var known = new LinkedHashMap<String, Track>();
        var rootPaths = new HashMap<String, Path>();
        selected.forEach((path, id) -> rootPaths.put(id, path));
        for (Cached saved : cache.values()) {
            Path base = rootPaths.get(saved.root);
            if (base == null) continue;
            try {
                Path file = base.resolve(saved.path).normalize();
                if (!file.equals(base) && file.startsWith(base))
                    known.put(saved.root + ":" + saved.path, saved.track(file));
            } catch (InvalidPathException invalid) { /* Ignore malformed persisted paths. */ }
        }
        for (Track track : prior.tracks()) known.put(track.id(), track);
        return new Snapshot(prior.revision(), List.copyOf(known.values()), prior.scanning(), prior.message(), prior.folders(), prior.complete(), prior.completeRoots());
    }

    /**
     * A job per selected root: a running job of this scan is reused, one of an earlier scan is cancelled and asks for
     * a rescan when it ends, and a finished job of this scan not yet consumed is kept.
     */
    private Map<Path, RootJob> startJobs(Map<Path, String> selected, long ticket) {
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
        return jobs;
    }

    /**
     * Replaces the index entries of the completely scanned roots and writes the index when it changed or the last
     * write failed.
     */
    private void saveIndex(Snapshot value) {
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
                LoggerFactory.getLogger("darkmusic").warn("Could not save library index", e);
            }
        }
    }

    /**
     * The folders this scan's finished jobs found, to watch; those jobs are consumed.
     */
    private List<Path> consumeDirectories(Map<Path, RootJob> jobs, long ticket) {
        var directories = new ArrayList<Path>();
        for (var job : jobs.values())
            if (job.ticket == ticket && job.result.isDone()) {
                directories.addAll(job.result.join().directories);
                job.consumed = true;
            }
        return directories;
    }

    private Snapshot combined(long ticket, Map<Path, String> selected, Map<Path, RootJob> jobs, Snapshot prior, boolean scanning) {
        var tracks = new ArrayList<Track>();
        var folders = new LinkedHashSet<String>();
        var priorTracks = prior.tracks().stream().collect(Collectors.groupingBy(Track::rootId));
        var priorFolders = prior.folders().stream().collect(Collectors.groupingBy(MusicLibrary::folderRoot));
        var complete = new HashSet<String>();
        var unreadableScopes = new HashMap<String, List<String>>();
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
                var retained = new LinkedHashMap<String, Track>();
                for (var track : priorTracks.getOrDefault(entry.getValue(), List.of()))
                    if (result.unreadable.stream().anyMatch(path -> track.file().toAbsolutePath().normalize().startsWith(path)))
                        retained.put(track.id(), track);
                for (String folder : priorFolders.getOrDefault(entry.getValue(), List.of())) {
                    String relative = folder.equals(prefix) ? "" : folder.substring(Math.min(folder.length(), prefix.length() + 1));
                    if (result.unreadable.stream().anyMatch(path -> entry.getKey().resolve(relative).normalize().startsWith(path)))
                        folders.add(folder);
                }
                for (var track : result.tracks) retained.put(track.id(), track);
                tracks.addAll(retained.values());
                folders.addAll(result.folders);
            }
            folders.add(prefix);
            if (result != null && result.complete) complete.add(entry.getValue());
            if (result != null && !result.unreadable.isEmpty())
                unreadableScopes.put(entry.getValue(), result.unreadable.stream()
                    .map(path -> entry.getKey().toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/')).toList());
            if (result != null && !result.message.isBlank()) failures.add(entry.getKey() + ": " + result.message);
            else if (job.timedOut && !job.result.isDone())
                failures.add(entry.getKey() + ": " + Text.get("library.status.slow_root"));
        }
        tracks.sort(Comparator.comparing(Track::relativePath, String.CASE_INSENSITIVE_ORDER).thenComparing(Track::rootId));
        return new Snapshot(revisions.incrementAndGet(), tracks, scanning, failures.isEmpty() && scanning ? Text.get("library.status.scanning") : String.join(" | ", failures), List.copyOf(folders), complete.size() == selected.size(), complete, unreadableScopes);
    }

    private record RootResult(List<Track> tracks, List<Path> directories, List<String> folders, boolean complete,
                              String message, List<Path> unreadable) {
        RootResult {
            tracks = List.copyOf(tracks);
            directories = List.copyOf(directories);
            folders = List.copyOf(folders);
            unreadable = List.copyOf(unreadable);
        }
    }

    private final class RootJob implements Runnable {
        private final Path directory;
        private final String id;
        private final long ticket;
        private final CompletableFuture<RootResult> result = new CompletableFuture<>();
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
            var walk = new Walk();
            Semaphore permit = null;
            boolean acquired = false;
            boolean complete = false;
            String message = "";
            try {
                beforeRoot.accept(directory);
                if (!Files.isDirectory(directory) || !Files.isReadable(directory))
                    throw new IOException(Text.get("library.status.unavailable"));
                String store = Files.getFileStore(directory).toString();
                permit = storeWalkers.computeIfAbsent(store, key -> new Semaphore(2, true));
                while (!permit.tryAcquire(250, TimeUnit.MILLISECONDS)) {
                    if (cancelled || closed || ticket != generation.get()) return;
                }
                acquired = true;
                progressAt = System.nanoTime();
                Files.walkFileTree(directory, walk);
                complete = !cancelled && !closed && ticket == generation.get();
                if (walk.unreadable != 0) message = Text.get("library.status.unreadable", walk.unreadable);
            } catch (Exception failure) {
                message = Text.failure(failure, "library.status.scan_failed");
            } finally {
                if (acquired) permit.release();
                if (walk.recoveryDirectories > 0)
                    message = (message.isBlank() ? "" : message + " | ") + Text.get("library.status.recovery_folders", walk.recoveryDirectories);
                result.complete(new RootResult(walk.tracks, walk.directories, walk.folders, complete, message, walk.unreadablePaths));
                revision++;
                if (timedOut && !closed) requestRescan();
            }
        }

        /**
         * One walk of the root: the songs, folders and unreadable paths it finds, published as partial results while
         * it runs. It stops when the job is cancelled, the library closes or a newer scan starts.
         */
        private final class Walk extends SimpleFileVisitor<Path> {
            private final List<Track> tracks = new ArrayList<>();
            private final List<Path> directories = new ArrayList<>();
            private final List<String> folders = new ArrayList<>(List.of("@" + id));
            private final List<Path> unreadablePaths = new ArrayList<>();
            private int unreadable, recoveryDirectories;
            private long publishedAt = System.nanoTime();

            private boolean stopped() {
                return cancelled || closed || ticket != generation.get();
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                progressAt = System.nanoTime();
                if (stopped()) return FileVisitResult.TERMINATE;
                if (!readableDirectory.test(dir)) {
                    unreadable++;
                    unreadablePaths.add(dir.toAbsolutePath().normalize());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(directory) && dir.getFileName().toString().startsWith(".darkmusic-work-")) {
                    recoveryDirectories++;
                    if (reportedWork.add(dir))
                        LoggerFactory.getLogger("darkmusic-library").warn("Metadata recovery files retained; inspect before removing: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
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
                if (stopped()) return FileVisitResult.TERMINATE;
                if (attrs.isRegularFile() && supported(file)) {
                    String relative = directory.relativize(file).toString().replace('\\', '/');
                    Cached old = cache.get(id + ":" + relative);
                    tracks.add(old != null && old.size == attrs.size() && old.modified == attrs.lastModifiedTime().toMillis()
                        ? old.track(file) : metadata.read(id, relative, file, attrs.size(), attrs.lastModifiedTime().toMillis()));
                    if (tracks.size() % 100 == 0 && System.nanoTime() - publishedAt > 250_000_000L) {
                        partial = new RootResult(tracks, directories, folders, false, recoveryDirectories == 0 ? "" : Text.get("library.status.recovery_folders", recoveryDirectories), unreadablePaths);
                        revision++;
                        publishedAt = System.nanoTime();
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                progressAt = System.nanoTime();
                unreadable++;
                unreadablePaths.add(file.toAbsolutePath().normalize());
                return stopped() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                if (failure != null) {
                    unreadable++;
                    unreadablePaths.add(dir.toAbsolutePath().normalize());
                }
                return stopped() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        }
    }

    private synchronized void publish(long ticket, Snapshot value) {
        if (!closed && ticket == generation.get()) snapshot = value;
    }

    /**
     * Whether the library's cache, or a scan still publishing, lists this file: its deletion is a change.
     */
    private boolean knownFile(Path path) {
        for (var entry : roots.entrySet())
            if (path.startsWith(entry.getKey())) {
                String relative = entry.getKey().relativize(path).toString().replace('\\', '/');
                if (cache.containsKey(entry.getValue() + ":" + relative)) return true;
            }
        // Cover initial partial scans and the short cache-publication window for extensionless tracks.
        var visible = snapshot;
        return (visible.scanning() || running.get()) && visible.tracks().stream().anyMatch(track -> track.file().equals(path));
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            generation.incrementAndGet();
        }
        monitor.shutdownNow();
        // Network filesystem calls in the watcher must not hold up the client closing this library.
        Thread.ofPlatform().daemon().name("darkmusic-watch-close").start(() -> {
            try {
                watcher.close();
            } finally {
                watchClosed.complete(null);
            }
        });
        scanner.shutdownNow();
        rootJobs.values().forEach(job -> job.cancelled = true);
        rootScanner.shutdownNow();
    }

    /**
     * The folder watcher, for tests.
     */
    LibraryWatcher watcher() {
        return watcher;
    }

    public boolean awaitStopped(long seconds) throws InterruptedException {
        if (!scanner.awaitTermination(seconds, TimeUnit.SECONDS) || !monitor.awaitTermination(seconds, TimeUnit.SECONDS)
            || !rootScanner.awaitTermination(seconds, TimeUnit.SECONDS)) return false;
        try {
            watchClosed.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException | ExecutionException failure) {
            return false;
        }
    }

    public record Snapshot(long revision, List<Track> tracks, boolean scanning, String message, List<String> folders,
                           boolean complete, Set<String> completeRoots, Map<String, List<String>> unreadableScopes) {
        public Snapshot {
            tracks = List.copyOf(tracks);
            folders = List.copyOf(folders);
            completeRoots = Set.copyOf(completeRoots);
            unreadableScopes = unreadableScopes.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        }

        public Snapshot(long revision, List<Track> tracks, boolean scanning, String message, List<String> folders, boolean complete, Set<String> completeRoots) {
            this(revision, tracks, scanning, message, folders, complete, completeRoots, Map.of());
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
