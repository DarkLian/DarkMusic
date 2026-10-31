package com.darkmusic.client.library;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Watches the library's folders and asks the library to rescan when audio may have changed. Its state is guarded by
 * its own lock, which it holds while it registers folders and drains events; it calls the library only for
 * lock-free reads and to schedule a rescan, and the library never holds a lock while calling in.
 */
final class LibraryWatcher {
    private final BooleanSupplier closed;
    private final Runnable changed;
    private final Predicate<Path> knownFile;
    private final Path work;
    private final Object lock = new Object();
    private volatile WatchService service;
    private volatile Set<Path> desired = Set.of();
    private final Map<Path, WatchKey> keys = new HashMap<>();
    private final Map<Path, Long> failed = new HashMap<>();
    private final AtomicBoolean pollWarning = new AtomicBoolean();
    private final AtomicBoolean watchWarning = new AtomicBoolean();

    /**
     * @param closed    whether the library closed; nothing is registered or reported after that
     * @param changed   schedules a rescan
     * @param knownFile whether the library's cache or a scan in progress lists the path
     * @param work      the download workspace, whose churn is never a library change
     */
    LibraryWatcher(BooleanSupplier closed, Runnable changed, Predicate<Path> knownFile, Path work) {
        this.closed = closed;
        this.changed = changed;
        this.knownFile = knownFile;
        this.work = work;
    }

    /**
     * Watches exactly these folders: keeps healthy registrations, drops the rest, and retries a folder that failed
     * after a minute.
     */
    void replace(List<Path> directories) {
        synchronized (lock) {
            Set<Path> wanted = Set.copyOf(directories);
            desired = wanted;
            if (closed.getAsBoolean()) return;
            if (service == null) try {
                service = FileSystems.getDefault().newWatchService();
            } catch (IOException failure) {
                watchFailed(failure);
                return;
            }
            var iterator = keys.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!wanted.contains(entry.getKey()) || !entry.getValue().isValid()) {
                    entry.getValue().cancel();
                    iterator.remove();
                }
            }
            failed.keySet().retainAll(wanted);
            long now = System.nanoTime();
            for (Path directory : wanted) {
                if (keys.containsKey(directory) || now < failed.getOrDefault(directory, 0L)) continue;
                try {
                    keys.put(directory, directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY));
                    failed.remove(directory);
                } catch (Exception failure) {
                    failed.put(directory, now + TimeUnit.MINUTES.toNanos(1));
                    watchFailed(failure);
                }
            }
        }
    }

    private void watchFailed(Exception failure) {
        if (watchWarning.compareAndSet(false, true))
            LoggerFactory.getLogger("darkmusic").warn("Some music folders cannot be watched; successful watches are retained and periodic scans remain active", failure);
    }

    /**
     * Drains pending events. A closed service is dropped and a rescan requested; any other failure only requests a
     * rescan, and polling continues.
     */
    void poll() {
        try {
            pollEvents();
        } catch (ClosedWatchServiceException closedService) {
            if (!closed.getAsBoolean()) {
                close();
                changed.run();
            }
        } catch (Throwable failure) {
            if (pollWarning.compareAndSet(false, true))
                LoggerFactory.getLogger("darkmusic-library").warn("Library change polling failed; polling will retry and periodic rescans remain enabled", failure);
            if (!closed.getAsBoolean()) changed.run();
        }
    }

    private void pollEvents() {
        synchronized (lock) {
            WatchService current = service;
            if (current == null || closed.getAsBoolean()) return;
            boolean relevant = false;
            WatchKey key;
            while ((key = current.poll()) != null) {
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        relevant = true;
                        continue;
                    }
                    if (!(event.context() instanceof Path relative)) continue;
                    Path path = ((Path) key.watchable()).resolve(relative).normalize();
                    relevant |= relevant(event.kind(), path);
                }
                if (!key.reset()) {
                    keys.remove((Path) key.watchable(), key);
                    relevant = true;
                }
            }
            if (relevant) changed.run();
        }
    }

    boolean relevant(WatchEvent.Kind<?> kind, Path path) {
        if (ignored(path)) return false;
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) return known(path);
        // Parent directory modify events do not tell us that any audio changed inside it.
        if (desired.contains(path)) return kind == StandardWatchEventKinds.ENTRY_CREATE;
        if (Files.isDirectory(path)) return kind == StandardWatchEventKinds.ENTRY_CREATE;
        return MusicLibrary.supported(path);
    }

    boolean ignored(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return path.startsWith(work) || name.startsWith(".darkmusic-work-") ||
            name.endsWith(".lrc.darkmusic-backup") || name.endsWith(".lrc.darkmusic.json") || name.startsWith(".darkmusic-") &&
            (name.endsWith(".tmp") || name.equals(".darkmusic-downloads.json") || name.equals(".darkmusic-sources.json") ||
                name.matches("\\.darkmusic-download-commit-[a-f0-9]{64}\\.json") || name.matches("\\.darkmusic-lyrics-[a-f0-9]{24}\\.json") ||
                name.matches("\\.darkmusic-lyrics-recovery-[a-f0-9-]{36}\\.json"));
    }

    boolean known(Path path) {
        return desired.contains(path) || knownFile.test(path);
    }

    void close() {
        synchronized (lock) {
            WatchService old = service;
            service = null;
            desired = Set.of();
            keys.clear();
            failed.clear();
            if (old != null) try {
                old.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * For tests: the watch service in use, or null before the first registration and after closing.
     */
    WatchService service() {
        return service;
    }

    /**
     * For tests: replaces the watch service, as a failing or blocking file system would present it.
     */
    void service(WatchService replacement) {
        synchronized (lock) {
            service = replacement;
        }
    }

    /**
     * For tests: the registration of a folder, or null.
     */
    WatchKey key(Path directory) {
        synchronized (lock) {
            return keys.get(directory);
        }
    }

    /**
     * For tests: lets a folder that failed register again on the next replace, without waiting a minute.
     */
    void retryNow(Path directory) {
        synchronized (lock) {
            failed.put(directory, 0L);
        }
    }

    /**
     * For tests: the folders with a live registration.
     */
    Set<Path> watched() {
        synchronized (lock) {
            return Set.copyOf(keys.keySet());
        }
    }
}
