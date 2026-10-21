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
            retryMove(old, backup);
            try {
                retryMove(incoming, target);
            } catch (Exception failure) {
                retryMove(backup, old);
                throw failure;
            }
        } finally {
            Files.deleteIfExists(incoming);
        }
    }

    private static void retryMove(Path source, Path target) throws Exception {
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
