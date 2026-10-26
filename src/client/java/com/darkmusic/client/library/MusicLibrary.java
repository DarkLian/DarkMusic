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
    private final java.util.Set<Path> reportedWork = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-scan").factory());
    private final ExecutorService rootScanner = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("darkmusic-root-", 0).factory());
    private final Map<Path, RootJob> rootJobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.Semaphore> storeWalkers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.function.Consumer<Path> beforeRoot;
    private final java.util.function.Predicate<Path> readableDirectory;
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
    private final Object watchLock = new Object();
    private final java.util.concurrent.CompletableFuture<Void> watchClosed = new java.util.concurrent.CompletableFuture<>();
    private volatile WatchService watcher;
    private volatile Set<Path> watchedDirectories = Set.of();
    private volatile Set<Path> desiredWatchDirectories = Set.of();
    private final Map<Path, WatchKey> watchKeys = new HashMap<>();
    private final Map<Path, Long> failedWatches = new HashMap<>();
    private final AtomicBoolean pollWarning = new AtomicBoolean();
    private boolean cacheDirty;
    private final AtomicBoolean watchWarning = new AtomicBoolean();
    private volatile Snapshot snapshot = new Snapshot(0, List.of(), false, Text.get("library.status.waiting"));

    public MusicLibrary(Path data) {
        this(data, path -> {
        }, 15_000);
    }

    MusicLibrary(Path data, java.util.function.Consumer<Path> beforeRoot, long timeoutMillis) {
        this(data, beforeRoot, timeoutMillis, Files::isReadable);
    }

    MusicLibrary(Path data, java.util.function.Consumer<Path> beforeRoot, long timeoutMillis, java.util.function.Predicate<Path> readableDirectory) {
        this.beforeRoot = beforeRoot;
        this.readableDirectory = readableDirectory;
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
        if (n.matches(".*\\.(heic|heif|avif|mov|mkv)$")) return false;
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
        // A first scan after restart also needs the persisted index when a subtree is unavailable.
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
        prior = new Snapshot(prior.revision(), List.copyOf(known.values()), prior.scanning(), prior.message(), prior.folders(), prior.complete(), prior.completeRoots());
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
            int[] unreadable = {0}, recoveryDirectories = {0};
            var unreadablePaths = new ArrayList<Path>();
            java.util.concurrent.Semaphore permit = null;
            boolean acquired = false;
            long[] publishedAt = {System.nanoTime()};
            boolean complete = false;
            String message = "";
            try {
                beforeRoot.accept(directory);
                if (!Files.isDirectory(directory) || !Files.isReadable(directory))
                    throw new IOException(Text.get("library.status.unavailable"));
                String store = Files.getFileStore(directory).toString();
                permit = storeWalkers.computeIfAbsent(store, key -> new java.util.concurrent.Semaphore(2, true));
                while (!permit.tryAcquire(250, TimeUnit.MILLISECONDS)) {
                    if (cancelled || closed || ticket != generation.get()) return;
                }
                acquired = true;
                progressAt = System.nanoTime();
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        progressAt = System.nanoTime();
                        if (cancelled || closed || ticket != generation.get()) return FileVisitResult.TERMINATE;
                        if (!readableDirectory.test(dir)) {
                            unreadable[0]++;
                            unreadablePaths.add(dir.toAbsolutePath().normalize());
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (!dir.equals(directory) && dir.getFileName().toString().startsWith(".darkmusic-work-")) {
                            recoveryDirectories[0]++;
                            if (reportedWork.add(dir))
                                org.slf4j.LoggerFactory.getLogger("darkmusic-library").warn("Metadata recovery files retained; inspect before removing: {}", dir);
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
                        if (cancelled || closed || ticket != generation.get()) return FileVisitResult.TERMINATE;
                        if (attrs.isRegularFile() && supported(file)) {
                            String relative = directory.relativize(file).toString().replace('\\', '/');
                            Cached old = cache.get(id + ":" + relative);
                            tracks.add(old != null && old.size == attrs.size() && old.modified == attrs.lastModifiedTime().toMillis()
                                ? old.track(file) : metadata.read(id, relative, file, attrs.size(), attrs.lastModifiedTime().toMillis()));
                            if (tracks.size() % 100 == 0 && System.nanoTime() - publishedAt[0] > 250_000_000L) {
                                partial = new RootResult(tracks, directories, folders, false, recoveryDirectories[0] == 0 ? "" : Text.get("library.status.recovery_folders", recoveryDirectories[0]), unreadablePaths);
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
                        unreadablePaths.add(file.toAbsolutePath().normalize());
                        return cancelled || closed || ticket != generation.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                        if (failure != null) {
                            unreadable[0]++;
                            unreadablePaths.add(dir.toAbsolutePath().normalize());
                        }
                        return cancelled || closed || ticket != generation.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
                complete = !cancelled && !closed && ticket == generation.get();
                if (unreadable[0] != 0) message = Text.get("library.status.unreadable", unreadable[0]);
            } catch (Exception failure) {
                message = Text.failure(failure, "library.status.scan_failed");
            } finally {
                if (acquired) permit.release();
                if (recoveryDirectories[0] > 0)
                    message = (message.isBlank() ? "" : message + " | ") + Text.get("library.status.recovery_folders", recoveryDirectories[0]);
                result.complete(new RootResult(tracks, directories, folders, complete, message, unreadablePaths));
                revision++;
                if (timedOut && !closed) requestRescan();
            }
        }
    }


    private synchronized void publish(long ticket, Snapshot value) {
        if (!closed && ticket == generation.get()) snapshot = value;
    }

    private void replaceWatcher(List<Path> directories) {
        synchronized (watchLock) {
            Set<Path> desired = Set.copyOf(directories);
            desiredWatchDirectories = desired;
            if (closed) return;
            if (watcher == null) try {
                watcher = FileSystems.getDefault().newWatchService();
            } catch (IOException failure) {
                watchFailed(failure);
                return;
            }
            var iterator = watchKeys.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!desired.contains(entry.getKey()) || !entry.getValue().isValid()) {
                    entry.getValue().cancel();
                    iterator.remove();
                }
            }
            failedWatches.keySet().retainAll(desired);
            long now = System.nanoTime();
            for (Path directory : desired) {
                if (watchKeys.containsKey(directory) || now < failedWatches.getOrDefault(directory, 0L)) continue;
                try {
                    watchKeys.put(directory, directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY));
                    failedWatches.remove(directory);
                } catch (Exception failure) {
                    failedWatches.put(directory, now + TimeUnit.MINUTES.toNanos(1));
                    watchFailed(failure);
                }
            }
            watchedDirectories = Set.copyOf(watchKeys.keySet());
        }
    }

    private void watchFailed(Exception failure) {
        if (watchWarning.compareAndSet(false, true))
            org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Some music folders cannot be watched; successful watches are retained and periodic scans remain active", failure);
    }

    private void poll() {
        try {
            pollEvents();
        } catch (ClosedWatchServiceException closedService) {
            if (!closed) {
                closeWatcher();
                requestRescan();
            }
        } catch (Throwable failure) {
            if (pollWarning.compareAndSet(false, true))
                org.slf4j.LoggerFactory.getLogger("darkmusic-library").warn("Library change polling failed; polling will retry and periodic rescans remain enabled", failure);
            if (!closed) requestRescan();
        }
    }

    private void pollEvents() {
        synchronized (watchLock) {
            WatchService service = watcher;
            if (service == null || closed) return;
            boolean changed = false;
            WatchKey key;
            while ((key = service.poll()) != null) {
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        changed = true;
                        continue;
                    }
                    if (!(event.context() instanceof Path relative)) continue;
                    Path path = ((Path) key.watchable()).resolve(relative).normalize();
                    changed |= relevantWatchEvent(event.kind(), path);
                }
                if (!key.reset()) {
                    watchKeys.remove((Path) key.watchable(), key);
                    watchedDirectories = Set.copyOf(watchKeys.keySet());
                    changed = true;
                }
            }
            if (changed) requestRescan();
        }
    }

    boolean relevantWatchEvent(WatchEvent.Kind<?> kind, Path path) {
        if (ignoredWatchPath(path)) return false;
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) return knownWatchPath(path);
        // Parent directory modify events do not tell us that any audio changed inside it.
        if (desiredWatchDirectories.contains(path)) return kind == StandardWatchEventKinds.ENTRY_CREATE;
        if (Files.isDirectory(path)) return kind == StandardWatchEventKinds.ENTRY_CREATE;
        return supported(path);
    }

    boolean ignoredWatchPath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return path.startsWith(cacheFile.toAbsolutePath().getParent().resolve("downloads/work")) || name.startsWith(".darkmusic-work-") ||
            name.endsWith(".lrc.darkmusic-backup") || name.endsWith(".lrc.darkmusic.json") || name.startsWith(".darkmusic-") &&
            (name.endsWith(".tmp") || name.equals(".darkmusic-downloads.json") || name.equals(".darkmusic-sources.json") ||
                name.matches("\\.darkmusic-download-commit-[a-f0-9]{64}\\.json") || name.matches("\\.darkmusic-lyrics-[a-f0-9]{24}\\.json") ||
                name.matches("\\.darkmusic-lyrics-recovery-[a-f0-9-]{36}\\.json"));
    }

    boolean knownWatchPath(Path path) {
        if (desiredWatchDirectories.contains(path)) return true;
        for (var entry : roots.entrySet())
            if (path.startsWith(entry.getKey())) {
                String relative = entry.getKey().relativize(path).toString().replace('\\', '/');
                if (cache.containsKey(entry.getValue() + ":" + relative)) return true;
            }
        // Cover initial partial scans and the short cache-publication window for extensionless tracks.
        var visible = snapshot;
        return (visible.scanning() || running.get()) && visible.tracks().stream().anyMatch(track -> track.file().equals(path));
    }

    private void closeWatcher() {
        synchronized (watchLock) {
            WatchService old = watcher;
            watcher = null;
            watchedDirectories = desiredWatchDirectories = Set.of();
            watchKeys.clear();
            failedWatches.clear();
            if (old != null) try {
                old.close();
            } catch (IOException ignored) {
            }
        }
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
                closeWatcher();
            } finally {
                watchClosed.complete(null);
            }
        });
        scanner.shutdownNow();
        rootJobs.values().forEach(job -> job.cancelled = true);
        rootScanner.shutdownNow();
    }

    public boolean awaitStopped(long seconds) throws InterruptedException {
        if (!scanner.awaitTermination(seconds, TimeUnit.SECONDS) || !monitor.awaitTermination(seconds, TimeUnit.SECONDS)
            || !rootScanner.awaitTermination(seconds, TimeUnit.SECONDS)) return false;
        try {
            watchClosed.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException failure) {
            return false;
        }
    }

    public record Snapshot(long revision, List<Track> tracks, boolean scanning, String message, List<String> folders,
                           boolean complete, Set<String> completeRoots, Map<String, List<String>> unreadableScopes) {
        public Snapshot {
            tracks = List.copyOf(tracks);
            folders = List.copyOf(folders);
            completeRoots = Set.copyOf(completeRoots);
            unreadableScopes = unreadableScopes.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
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
