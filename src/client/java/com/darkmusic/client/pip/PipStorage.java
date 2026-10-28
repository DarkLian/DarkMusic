package com.darkmusic.client.pip;

import com.darkmusic.client.tools.ProcessRunner;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Instance-local setup lock and per-runtime leases protect other running clients during cleanup.
 */
final class PipStorage {
    static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Path path;

        Lease(Path path, ProcessRunner.Token token) throws IOException {
            this(path, token, TimeUnit.SECONDS.toNanos(30));
        }

        Lease(Path path, ProcessRunner.Token token, long timeoutNanos) throws IOException {
            this.path = path;
            if (Files.isSymbolicLink(path)) throw new IOException("pip.bundle_damaged");
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock acquired = null;
            try {
                long deadline = System.nanoTime() + timeoutNanos;
                while (acquired == null) {
                    token.check();
                    try {
                        acquired = channel.tryLock();
                    } catch (OverlappingFileLockException busy) {
                    }
                    if (acquired == null) {
                        if (System.nanoTime() > deadline) throw new IOException("pip.setup.busy");
                        LockSupport.parkNanos(20_000_000);
                    }
                }
                lock = acquired;
            } catch (Throwable failure) {
                channel.close();
                throw failure;
            }
        }

        public void close() throws IOException {
            lock.close();
            channel.close();
            if (path.getFileName().toString().startsWith(".lease-")) Files.deleteIfExists(path);
        }
    }

    static Lease setup(Path root, ProcessRunner.Token token) throws IOException {
        return new Lease(root.resolve(".setup-lock"), token);
    }

    static Lease use(Path directory, String archive, ProcessRunner.Token token) throws IOException {
        Path marker = directory.resolve(".managed-runtime");
        if (Files.isSymbolicLink(marker)) throw new IOException("pip.bundle_damaged");
        Files.writeString(marker, archive, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        return new Lease(directory.resolve(".lease-" + UUID.randomUUID()), token);
    }

    static boolean auxiliary(Path file) throws IOException {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 200 && (name.equals(".managed-runtime") || name.matches("\\.lease-[a-f0-9-]{36}"));
    }

    static void removeTree(Path root, Path path) throws IOException {
        Path base = root.toAbsolutePath().normalize(), target = path.toAbsolutePath().normalize();
        if (!target.getParent().equals(base) || Files.isSymbolicLink(target))
            throw new IOException("Unsafe PiP cleanup path");
        try (var tree = Files.walk(target)) {
            for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }

    /**
     * Moves a damaged extracted runtime aside so it can be extracted again. A runtime another client is using stays.
     */
    static void discard(Path root, Path runtime) throws IOException {
        if (Files.isSymbolicLink(runtime) || !Files.isDirectory(runtime, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("pip.bundle_damaged");
        if (leased(runtime)) throw new IOException("pip.setup.runtime_in_use");
        Path claimed = root.resolve("prepare-damaged-" + UUID.randomUUID());
        Files.move(runtime, claimed);
        try {
            removeTree(root, claimed);
        } catch (IOException busy) {
            // prune removes leftover prepare-* directories later.
            deferred(claimed, busy);
        }
    }

    private static final long GRACE_MILLIS = 86_400_000L;

    /**
     * Keeps exactly one dependency package: this version's. Call while holding the setup lock. Packages are only read
     * under that lock (to verify and extract them), so no other client can be using one; an extracted runtime that
     * another client is running does not need its ZIP. Partial downloads left by a crash are removed too.
     */
    static void removeOtherPackages(Path root, Path archive) throws IOException {
        try (var paths = Files.list(root)) {
            for (Path file : paths.toList()) {
                String name = file.getFileName().toString();
                boolean other = name.matches("dependencies-[a-f0-9]{64}\\.zip") && !file.equals(archive);
                boolean partial = name.matches("runtime-[0-9]+\\.download");
                if ((other || partial) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) try {
                    Files.deleteIfExists(file);
                } catch (IOException busy) {
                    deferred(file, busy);
                }
            }
        }
    }

    /**
     * Removes extracted runtimes that are neither current, in use by another client, nor younger than a day.
     */
    static void prune(Path root, Path current, long now) throws IOException {
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) continue;
                String name = path.getFileName().toString();
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                boolean old = now - Files.getLastModifiedTime(path).toMillis() > GRACE_MILLIS;
                if (name.startsWith("prepare-") && old) {
                    tryRemoveRuntime(root, path);
                    continue;
                }
                if (!name.matches("[a-f0-9]{64}")) continue;
                // Inspect even the current/young runtime to reclaim leases from crashed clients.
                boolean inUse = leased(path);
                if (!path.equals(current) && !inUse && old) tryRemoveRuntime(root, path);
            }
        }
    }

    /**
     * Preflight all files before touching a legacy runtime; Windows rejects writes to mapped EXE/DLLs.
     */
    private static boolean tryRemoveRuntime(Path root, Path directory) {
        var channels = new ArrayList<FileChannel>();
        var locks = new ArrayList<FileLock>();
        Path claimed = null;
        try {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.toList()) {
                    if (Files.isSymbolicLink(file)) return false;
                    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    channels.add(channel);
                    var lock = channel.tryLock();
                    if (lock == null) return false;
                    locks.add(lock);
                }
            }
        } catch (IOException | OverlappingFileLockException | UncheckedIOException busy) {
            deferred(directory, busy);
            return false;
        } finally {
            for (var lock : locks)
                try {
                    lock.close();
                } catch (IOException ignored) {
                }
            for (var channel : channels)
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
        }
        try {
            // Windows cannot rename a directory while our own preflight file handles are open.
            // Claim it after closing them; a sharing violation leaves the old directory in place.
            claimed = root.resolve("prepare-prune-" + UUID.randomUUID());
            Files.move(directory, claimed);
            removeTree(root, claimed);
            return true;
        } catch (IOException | UncheckedIOException busy) {
            // Keep remaining disposable runtime files for the next cleanup attempt.
            deferred(claimed != null && Files.exists(claimed, LinkOption.NOFOLLOW_LINKS) ? claimed : directory, busy);
            return false;
        }
    }

    private static void deferred(Path path, Exception reason) {
        LoggerFactory.getLogger("darkmusic-pip-setup").debug("PiP cleanup deferred for {}", path, reason);
    }

    private static boolean leased(Path directory) throws IOException {
        boolean inUse = false;
        try (var files = Files.newDirectoryStream(directory, ".lease-*")) {
            for (Path file : files) {
                try {
                    var attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!file.getFileName().toString().matches("\\.lease-[a-f0-9-]{36}") || !attrs.isRegularFile() || attrs.size() != 0) {
                        inUse = true;
                        continue;
                    }
                } catch (NoSuchFileException closedConcurrently) {
                    continue;
                } catch (IOException busy) {
                    inUse = true;
                    deferred(file, busy);
                    continue;
                }
                boolean stale = false;
                try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    try (var lock = channel.tryLock()) {
                        if (lock == null) inUse = true;
                        else stale = true;
                    } catch (OverlappingFileLockException busy) {
                        inUse = true;
                    }
                } catch (NoSuchFileException closedConcurrently) {
                    continue;
                } catch (IOException busy) {
                    inUse = true;
                    deferred(file, busy);
                }
                if (stale) try {
                    Files.deleteIfExists(file);
                } catch (IOException busy) {
                    inUse = true;
                    deferred(file, busy);
                }
            }
        }
        return inUse;
    }
}
