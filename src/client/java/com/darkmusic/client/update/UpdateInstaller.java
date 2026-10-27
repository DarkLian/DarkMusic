package com.darkmusic.client.update;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Standalone JDK-only helper: never loads Minecraft or the old mod JAR.
 */
public final class UpdateInstaller {
    private UpdateInstaller() {
    }

    public static void main(String[] args) throws Exception {
        long parent = Long.parseLong(args[0]);
        Path manifest = Path.of(args[1]).toAbsolutePath().normalize();
        var properties = new Properties();
        try (var input = Files.newInputStream(manifest)) {
            properties.load(input);
        }
        var process = ProcessHandle.of(parent);
        if (process.isPresent()) process.get().onExit().get(24, TimeUnit.HOURS);
        try {
            install(manifest.getParent(), properties);
            properties.setProperty("result", "installed");
        } catch (Exception failure) {
            properties.setProperty("result", "failed");
            failure.printStackTrace();
        }
        try (var output = Files.newOutputStream(manifest)) {
            properties.store(output, "DarkMusic update result");
        }
    }

    public static void install(Path directory, Properties p) throws Exception {
        install(directory, p, (source, target) -> false);
    }

    /**
     * {@code refused} lets disposable tests fail chosen moves. A lambda adds no class file to the copied helper.
     */
    static void install(Path directory, Properties p, java.util.function.BiPredicate<Path, Path> refused) throws Exception {
        Path old = Path.of(p.getProperty("old")).toAbsolutePath().normalize();
        Path staged = directory.resolve("download.jar");
        String name = p.getProperty("name", "");
        if (!name.matches("darkmusic-[0-9]+\\.[0-9]+\\.[0-9]+\\.jar") || !Files.isRegularFile(old) ||
            Files.isSymbolicLink(old) || Files.isSymbolicLink(staged) || !hash(old).equals(p.getProperty("oldHash")) ||
            !hash(staged).equals(p.getProperty("sha256"))) throw new java.io.IOException("Update inputs changed");
        Path target = old.getParent().resolve(name), backup = directory.resolve("previous.jar");
        if (Files.exists(backup) || !target.equals(old) && Files.exists(target))
            throw new java.io.IOException("Update destination already exists");
        Path incoming = Files.createTempFile(old.getParent(), ".darkmusic-update-", ".tmp");
        try {
            Files.copy(staged, incoming, StandardCopyOption.REPLACE_EXISTING);
            if (!hash(incoming).equals(p.getProperty("sha256")))
                throw new java.io.IOException("Copied update checksum mismatch");
            if (target.equals(old)) {
                // Same file name: the old JAR must leave before the new one can take its place.
                retryMove(old, backup, refused);
                try {
                    retryMove(incoming, target, refused);
                } catch (Exception failure) {
                    restore(backup, old, failure, refused);
                    throw failure;
                }
            } else {
                // Install beside the old JAR first: a failure here leaves the working installation untouched.
                retryMove(incoming, target, refused);
                try {
                    retryMove(old, backup, refused);
                } catch (Exception failure) {
                    try {
                        retryDelete(target);
                    } catch (Exception cleanup) {
                        // Both JARs remain; Fabric names them both instead of starting without the mod.
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }
        } finally {
            Files.deleteIfExists(incoming);
        }
    }

    /**
     * Never leave the mods folder without the mod: fall back to copying when the backup cannot be moved back.
     */
    private static void restore(Path backup, Path old, Exception failure, java.util.function.BiPredicate<Path, Path> refused) {
        try {
            retryMove(backup, old, refused);
        } catch (Exception move) {
            failure.addSuppressed(move);
            try {
                Files.copy(backup, old);
            } catch (Exception copy) {
                failure.addSuppressed(copy);
                failure.addSuppressed(new java.io.IOException("Previous DarkMusic JAR retained at " + backup));
            }
        }
    }

    private static void retryMove(Path source, Path target, java.util.function.BiPredicate<Path, Path> refused) throws Exception {
        if (refused.test(source, target)) throw new java.io.IOException("Move refused: " + source + " -> " + target);
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(source, target);
                return;
            } catch (java.io.IOException failure) {
                if (attempt == 39) throw failure;
                Thread.sleep(250);
            }
        }
    }

    private static void retryDelete(Path file) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (java.io.IOException failure) {
                if (attempt == 39) throw failure;
                Thread.sleep(250);
            }
        }
    }

    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
