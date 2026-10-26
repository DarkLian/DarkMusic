package com.darkmusic.client.update;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/**
 * Reads the latest local installation result without requiring a network check.
 */
final class UpdateRecovery {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("darkmusic-update");

    private UpdateRecovery() {
    }

    record Result(Path pending, Path failureLog) {
    }

    static void discard(Path job) throws IOException {
        if (job == null || !job.getFileName().toString().matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
            || !Files.isDirectory(job, LinkOption.NOFOLLOW_LINKS)) return;
        java.util.List<Path> paths;
        try (var walk = Files.walk(job)) {
            paths = walk.toList();
        }
        boolean owned = paths.stream().allMatch(p -> {
            if (Files.isSymbolicLink(p)) return false;
            String relative = job.relativize(p).toString().replace('\\', '/');
            return Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? java.util.Set.of("", "com", "com/darkmusic", "com/darkmusic/client", "com/darkmusic/client/update").contains(relative)
                : java.util.Set.of("download.jar", "previous.jar", "install.properties", "install.log", "com/darkmusic/client/update/UpdateInstaller.class").contains(relative);
        });
        if (!owned) return;
        for (Path path : paths.stream().sorted(java.util.Comparator.reverseOrder()).toList())
            Files.deleteIfExists(path);
    }

    static void prune(Path directory, long now) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return;
        var completed = new java.util.ArrayList<Path>();
        var abandoned = new java.util.ArrayList<Path>();
        try (var jobs = Files.list(directory)) {
            for (Path job : jobs.toList())
                try {
                    if (!job.getFileName().toString().matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                        || !Files.isDirectory(job, LinkOption.NOFOLLOW_LINKS)) continue;
                    Path manifest = job.resolve("install.properties");
                    if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) abandoned.add(job);
                    else if (!Files.isSymbolicLink(manifest) && Files.size(manifest) <= 65536) {
                        var properties = new Properties();
                        try (var in = Files.newInputStream(manifest)) {
                            properties.load(in);
                        }
                        if ("installed".equals(properties.getProperty("result"))) completed.add(job);
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    LOG.warn("Cannot inspect update staging {}", job, failure);
                }
        }
        completed.sort(java.util.Comparator.comparingLong((Path p) -> p.resolve("install.properties").toFile().lastModified()).reversed());
        if (!completed.isEmpty()) completed.removeFirst(); // Keep the newest successful rollback, even if young.
        completed.addAll(abandoned);
        for (Path job : completed)
            try {
                if (now - Files.getLastModifiedTime(job, LinkOption.NOFOLLOW_LINKS).toMillis() < java.util.concurrent.TimeUnit.DAYS.toMillis(1))
                    continue;
                discard(job);
            } catch (IOException failure) {
                LOG.warn("Cannot prune old update staging {}", job, failure);
            }
    }

    static Result read(Path directory, Path installed) throws IOException {
        Result result = new Result(null, null);
        if (installed == null || !Files.isDirectory(directory)) return result;
        prune(directory, System.currentTimeMillis());
        long latest = Long.MIN_VALUE;
        try (var jobs = Files.list(directory)) {
            for (var job : jobs.sorted().toList()) {
                Path manifest = job.resolve("install.properties");
                if (!Files.isRegularFile(manifest)) continue;
                try {
                    var p = new Properties();
                    try (var input = Files.newInputStream(manifest)) {
                        p.load(input);
                    }
                    if (!installed.toAbsolutePath().normalize().toString().equals(p.getProperty("old"))) continue;
                    long modified = Files.getLastModifiedTime(manifest).toMillis();
                    if (modified < latest) continue;
                    latest = modified;
                    result = switch (p.getProperty("result", "")) {
                        case "" -> new Result(manifest, null);
                        case "failed" -> new Result(null, job.resolve("install.log"));
                        default -> new Result(null, null);
                    };
                } catch (IOException | IllegalArgumentException failure) {
                    LOG.warn("Cannot read update result {}", manifest, failure);
                }
            }
        }
        return result;
    }
}
