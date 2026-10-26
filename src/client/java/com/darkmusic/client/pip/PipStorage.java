package com.darkmusic.client.pip;

import com.darkmusic.client.download.ProcessRunner;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/**
 * Instance-local setup lock and per-runtime leases protect other running clients during cleanup.
 */
final class PipStorage {
    static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Path path;

        Lease(Path path, ProcessRunner.Token token) throws IOException {
            this.path = path;
            if (Files.isSymbolicLink(path)) throw new IOException("pip.bundle_damaged");
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock acquired = null;
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while (acquired == null) {
                    token.check();
                    try {
                        acquired = channel.tryLock();
                    } catch (OverlappingFileLockException busy) {
                    }
                    if (acquired == null) {
                        if (System.nanoTime() > deadline) throw new IOException("PiP setup busy in another client");
                        java.util.concurrent.locks.LockSupport.parkNanos(20_000_000);
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

    static void prune(Path root, Path current, Path archive, long now) throws IOException {
        var retained = new HashSet<String>();
        retained.add(archive.getFileName().toString());
        boolean legacy = false;
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (Files.isSymbolicLink(path)) continue;
                if (name.startsWith("prepare-") && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && now - Files.getLastModifiedTime(path).toMillis() > 86_400_000L) {
                    removeTree(root, path);
                    continue;
                }
                if (!name.matches("[a-f0-9]{64}") || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                Path marker = path.resolve(".managed-runtime");
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 200) {
                    legacy = true;
                    continue;
                }
                String dependency = Files.readString(marker);
                if (!dependency.matches("dependencies-[a-f0-9]{64}\\.zip")) {
                    legacy = true;
                    continue;
                }
                if (path.equals(current) || leased(path) || now - Files.getLastModifiedTime(path).toMillis() < 86_400_000L)
                    retained.add(dependency);
                else removeTree(root, path);
            }
        }
        // Pre-lease releases may still use a runtime: never guess that those directories are idle.
        if (!legacy) try (var paths = Files.list(root)) {
            for (Path file : paths.toList())
                if (file.getFileName().toString().matches("dependencies-[a-f0-9]{64}\\.zip") && !retained.contains(file.getFileName().toString()) && !Files.isSymbolicLink(file))
                    Files.deleteIfExists(file);
        }
    }

    private static boolean leased(Path directory) throws IOException {
        try (var files = Files.newDirectoryStream(directory, ".lease-*")) {
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) return true;
                try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    try (var lock = channel.tryLock()) {
                        if (lock == null) return true;
                    } catch (OverlappingFileLockException inUse) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
