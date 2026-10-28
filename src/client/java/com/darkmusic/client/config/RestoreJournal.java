package com.darkmusic.client.config;

import com.darkmusic.client.text.Text;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Restoring a backup rewrites several files; this journal lets an interrupted restore roll back on the next start.
 */
public final class RestoreJournal {
    private RestoreJournal() {
    }

    public static void install(Path directory, Map<String, String> contents) throws IOException {
        install(directory, contents, ConfigStore::atomicWrite);
    }

    @FunctionalInterface
    interface Writer {
        void write(Path path, String value) throws IOException;
    }

    private static final Set<String> TARGETS = Set.of("config.json", "playlists.json", "playlist-retained.json", "listening-history.json", "options.txt");
    private static final String JOURNAL = "restore-pending.json";

    private record Recovery(int version, String backup, Map<String, Boolean> existed,
                            Map<String, String> before, Map<String, String> after) {
    }

    private static Path target(Path directory, String name) throws IOException {
        if (!TARGETS.contains(name)) throw new IOException("Invalid restore target");
        Path path = name.equals("options.txt") ? directory.toAbsolutePath().getParent().getParent().resolve(name) : directory.resolve(name);
        if (Files.isSymbolicLink(path) || Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 32_000_000))
            throw new IOException("Unsafe restore target");
        return path;
    }

    static void install(Path directory, Map<String, String> contents, Writer writer) throws IOException {
        if (!TARGETS.containsAll(contents.keySet())) throw new IOException("Invalid restore target");
        recover(directory);
        if (contents.isEmpty()) return;
        Path backups = directory.resolve("backups");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(backups))
            throw new IOException("Symbolic backup directory");
        Path rollback = backups.resolve(UUID.randomUUID().toString());
        Files.createDirectories(rollback);
        var existed = new LinkedHashMap<String, Boolean>();
        var before = new LinkedHashMap<String, String>();
        var after = new LinkedHashMap<String, String>();
        for (var e : contents.entrySet()) {
            Path file = target(directory, e.getKey());
            boolean present = Files.exists(file);
            existed.put(e.getKey(), present);
            if (present) {
                String original = Files.readString(file);
                ConfigStore.atomicWrite(rollback.resolve(e.getKey()), original);
                before.put(e.getKey(), digest(original));
            } else before.put(e.getKey(), "");
            after.put(e.getKey(), digest(e.getValue()));
        }
        Path journal = directory.resolve(JOURNAL);
        if (Files.isSymbolicLink(journal)) throw new IOException("Symbolic recovery journal");
        ConfigStore.atomicWrite(journal, ConfigStore.JSON.toJson(new Recovery(2, rollback.getFileName().toString(), existed, before, after)));
        try {
            for (var e : contents.entrySet()) writer.write(target(directory, e.getKey()), e.getValue());
            Files.delete(journal);
            pruneBackups(directory);
        } catch (IOException | RuntimeException failure) {
            try {
                recover(directory);
            } catch (IOException restore) {
                failure.addSuppressed(restore);
            }
            throw failure;
        }
    }

    /**
     * Run on the serial I/O executor before loading any user data after an interrupted restore.
     */
    public static boolean recover(Path directory) throws IOException {
        Path journal = directory.resolve(JOURNAL);
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(journal) || !Files.isRegularFile(journal)
            || Files.size(journal) > 16_384) throw new IOException("Unsafe recovery journal");
        Recovery recovery;
        try {
            recovery = ConfigStore.JSON.fromJson(Files.readString(journal), Recovery.class);
            if (recovery == null || (recovery.version != 1 && recovery.version != 2) || recovery.existed == null || recovery.existed.isEmpty()
                || !TARGETS.containsAll(recovery.existed.keySet()) || recovery.existed.values().stream().anyMatch(Objects::isNull)
                || recovery.backup == null || !UUID.fromString(recovery.backup).toString().equals(recovery.backup))
                throw new IllegalArgumentException();
            if (recovery.version == 2 && (recovery.before == null || recovery.after == null
                || !recovery.before.keySet().equals(recovery.existed.keySet()) || !recovery.after.keySet().equals(recovery.existed.keySet())
                || recovery.before.values().stream().anyMatch(v -> v == null || !v.isEmpty() && !v.matches("[a-f0-9]{64}"))
                || recovery.after.values().stream().anyMatch(v -> v == null || !v.matches("[a-f0-9]{64}"))))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid restore recovery journal", invalid);
        }
        Path backups = directory.resolve("backups"), originals = backups.resolve(recovery.backup);
        if (Files.isSymbolicLink(backups) || Files.isSymbolicLink(originals))
            throw new IOException("Unsafe recovery directory");
        // Read all originals and inspect all current targets before changing anything.
        var before = new LinkedHashMap<String, String>();
        for (var e : recovery.existed.entrySet()) {
            Path destination = target(directory, e.getKey()), source = originals.resolve(e.getKey());
            if (e.getValue() && (Files.isSymbolicLink(source) || !Files.isRegularFile(source) || Files.size(source) > 32_000_000))
                throw new IOException("Missing or invalid recovery copy");
            String original = e.getValue() ? Files.readString(source) : null;
            if (recovery.version == 2 && !Objects.equals(recovery.before.get(e.getKey()), original == null ? "" : digest(original)))
                throw new IOException("Recovery copy does not match journal");
            before.put(e.getKey(), original);
            inspectRecovery(destination, recovery, e.getKey(), original, originals);
            Path held = originals.resolve("recover-" + e.getKey());
            if (Files.exists(held, LinkOption.NOFOLLOW_LINKS)) {
                targetFile(held);
                inspectRecovery(held, recovery, e.getKey(), original, originals);
            }
        }
        for (var e : before.entrySet()) {
            String name = e.getKey(), original = e.getValue();
            Path destination = target(directory, name), held = originals.resolve("recover-" + name);
            String current = Files.exists(destination) ? Files.readString(destination) : null;
            if (!Objects.equals(current, original)) {
                if (Files.exists(destination)) {
                    if (Files.exists(held, LinkOption.NOFOLLOW_LINKS)) throw conflict(destination, originals);
                    // Claim and validate the actual object, rather than overwriting a checked pathname.
                    Files.move(destination, held);
                    try {
                        targetFile(held);
                        inspectRecovery(held, recovery, name, original, originals);
                    } catch (IOException conflict) {
                        if (Files.notExists(destination)) Files.move(held, destination);
                        throw conflict;
                    }
                }
                if (original != null) {
                    Path staged = Files.createTempFile(originals, "restore-", ".tmp");
                    try {
                        Files.writeString(staged, original);
                        Files.move(staged, destination); // Never overwrite an external replacement.
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                }
            }
            Files.deleteIfExists(held);
        }
        Files.delete(journal);
        pruneBackups(directory);
        return true;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void targetFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 32_000_000)
            throw new IOException("Unsafe recovery target");
    }

    private static void inspectRecovery(Path path, Recovery recovery, String name, String original, Path originals) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        targetFile(path);
        String current = digest(Files.readString(path));
        if (original != null && current.equals(digest(original))) return;
        // Old journals have no expected replacement hash: keep unknown contents for manual recovery.
        if (recovery.version == 2 && current.equals(recovery.after.get(name))) return;
        throw conflict(path, originals);
    }

    public static final class RecoveryConflict extends IOException {
        private RecoveryConflict(Path copies) {
            super(Text.get("backup.recovery_conflict", copies));
        }
    }

    private static IOException conflict(Path path, Path originals) throws IOException {
        Path conflicts = originals.resolve("conflicts");
        if (Files.isSymbolicLink(conflicts)) throw new IOException("Unsafe recovery conflict directory");
        Files.createDirectories(conflicts);
        Files.copy(path, conflicts.resolve(UUID.randomUUID() + "-" + path.getFileName()), LinkOption.NOFOLLOW_LINKS);
        return new RecoveryConflict(conflicts);
    }

    /**
     * Keep recent recovery copies; never prune while a transaction still needs them.
     */
    static void pruneBackups(Path directory) {
        Path root = directory.resolve("backups");
        if (recoveryPending(directory) || Files.isSymbolicLink(root) || !Files.isDirectory(root)) return;
        try (var files = Files.list(root)) {
            var copies = files.filter(p -> !Files.isSymbolicLink(p) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                    && p.getFileName().toString().matches("[a-f0-9-]{36}"))
                .sorted(Comparator.comparingLong((Path p) -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return Long.MAX_VALUE;
                    }
                }).reversed()).toList();
            for (Path path : copies.stream().skip(5).toList()) {
                if (System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() < 7 * 86_400_000L) continue;
                try (var children = Files.list(path)) {
                    var entries = children.toList();
                    if (entries.stream().anyMatch(p -> !TARGETS.contains(p.getFileName().toString()) || Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)))
                        continue;
                    for (Path entry : entries) Files.delete(entry);
                }
                Files.delete(path);
            }
        } catch (IOException failure) {
            LoggerFactory.getLogger("darkmusic").debug("Deferred old recovery-copy cleanup", failure);
        }
    }

    public static boolean recoveryPending(Path directory) {
        return Files.exists(directory.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS);
    }
}
