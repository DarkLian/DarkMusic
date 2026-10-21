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

    static Result read(Path directory, Path installed) throws IOException {
        Result result = new Result(null, null);
        if (installed == null || !Files.isDirectory(directory)) return result;
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
