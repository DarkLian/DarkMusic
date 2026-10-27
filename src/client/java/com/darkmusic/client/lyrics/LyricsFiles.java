package com.darkmusic.client.lyrics;

import com.darkmusic.client.audio.AudioFormats;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Small recoverable transactions for the three reserved lyric companion paths.
 */
public final class LyricsFiles {
    private static final Object LOCK = new Object();
    private static final int LIMIT = 4_000_000;

    private record Change(String name, String before, boolean existed, String afterHash) {
    }

    private record Journal(int schema, boolean committed, List<Change> changes) {
    }

    private LyricsFiles() {
    }

    public static Path sidecar(Path audio) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return audio.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".lrc");
    }

    public static List<Path> companions(Path audio) {
        Path lrc = sidecar(audio);
        return List.of(lrc, lrc.resolveSibling(lrc.getFileName() + ".darkmusic.json"), lrc.resolveSibling(lrc.getFileName() + ".darkmusic-backup"));
    }

    public static Map<Path, Set<Path>> companionOwners(Path directory) throws IOException {
        var owners = new HashMap<Path, Set<Path>>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path file : entries) {
                if (!AudioFormats.candidate(file) || !Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                    continue;
                Path audio = file.toAbsolutePath().normalize();
                owners.computeIfAbsent(companions(audio).getFirst(), ignored -> new HashSet<>()).add(audio);
            }
        }
        return owners;
    }

    public static boolean shared(Path audio) throws IOException {
        Path normalized = audio.toAbsolutePath().normalize();
        return companionOwners(normalized.getParent()).getOrDefault(companions(normalized).getFirst(), Set.of())
            .stream().anyMatch(path -> !path.equals(normalized));
    }

    private static void safe(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LIMIT))
            throw new IOException("Unsafe lyric companion");
    }

    private static byte[] read(Path path) throws IOException {
        safe(path);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(path) : null;
    }

    private static String hash(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Path journal(Path audio) {
        return audio.resolveSibling(".darkmusic-lyrics-" + hash(audio.getFileName().toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24) + ".json");
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        safe(file);
        Path temp = Files.createTempFile(file.getParent(), ".darkmusic-lyric-write-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void recover(Path audio) throws IOException {
        synchronized (LOCK) {
            for (Path pending : pendingJournals(audio)) recoverOne(audio, pending);
        }
    }

    private record RecoveryIndex(java.nio.file.attribute.FileTime modified, Map<String, List<Path>> byCompanion) {
    }

    private static final LinkedHashMap<Path, RecoveryIndex> recoveryIndexes = new LinkedHashMap<>();

    private static List<Path> pendingJournals(Path audio) throws IOException {
        Path directory = audio.getParent();
        var modified = Files.getLastModifiedTime(directory);
        var index = recoveryIndexes.get(directory);
        if (index == null || !index.modified.equals(modified)) {
            var byCompanion = new HashMap<String, List<Path>>();
            try (var files = Files.newDirectoryStream(directory, ".darkmusic-lyrics-*.json")) {
                for (Path file : files) {
                    if (!file.getFileName().toString().matches("\\.darkmusic-lyrics-[a-f0-9]{24}\\.json")) continue;
                    try {
                        safeJournal(file);
                        var data = ConfigStore.JSON.fromJson(Files.readString(file), Journal.class);
                        if (data != null && data.changes != null && data.changes.size() <= 3)
                            for (Change change : data.changes)
                                if (change != null && change.name != null)
                                    byCompanion.computeIfAbsent(change.name, key -> new ArrayList<>()).add(file);
                    } catch (RuntimeException | IOException unrelated) {
                    }
                }
            }
            index = new RecoveryIndex(modified, byCompanion);
            recoveryIndexes.put(directory, index);
            while (recoveryIndexes.size() > 64) recoveryIndexes.remove(recoveryIndexes.keySet().iterator().next());
        }
        var pending = new LinkedHashSet<Path>();
        pending.add(journal(audio));
        for (Path companion : companions(audio))
            pending.addAll(index.byCompanion.getOrDefault(companion.getFileName().toString(), List.of()));
        return List.copyOf(pending);
    }

    /**
     * Explicit conflict resolution: keep current files and preserve the journal for manual recovery.
     */
    public static void keepCurrent(Path audio) throws IOException {
        synchronized (LOCK) {
            for (Path pending : pendingJournals(audio)) {
                if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) continue;
                safeJournal(pending);
                Path archived = pending.resolveSibling(".darkmusic-lyrics-recovery-" + UUID.randomUUID() + ".json");
                Files.move(pending, archived);
                org.slf4j.LoggerFactory.getLogger("darkmusic-lyrics").warn("Kept current lyric files; recovery data archived at {}", archived);
            }
        }
    }

    private static void safeJournal(Path pending) throws IOException {
        if (Files.isSymbolicLink(pending) || !Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS) || Files.size(pending) > 20_000_000)
            throw new IOException("Unsafe lyric recovery journal: " + pending);
    }

    private static void recoverOne(Path audio, Path pending) throws IOException {
        synchronized (LOCK) {
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(pending) || !Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS) || Files.size(pending) > 20_000_000)
                throw new IOException("Unsafe lyric recovery journal");
            Journal data;
            try {
                data = ConfigStore.JSON.fromJson(Files.readString(pending), Journal.class);
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid lyric recovery journal", invalid);
            }
            if (data == null || data.schema != 1 || data.changes == null || data.changes.size() > 3)
                throw new IOException("Invalid lyric recovery journal");
            Set<String> allowed = new HashSet<>();
            for (Path path : companions(audio)) allowed.add(path.getFileName().toString());
            var names = new HashSet<String>();
            for (Change change : data.changes) {
                if (change == null || !allowed.contains(change.name) || !names.add(change.name))
                    throw new IOException("Invalid lyric recovery path");
                Path path = pending.resolveSibling(change.name);
                byte[] prior;
                try {
                    prior = change.existed ? Base64.getDecoder().decode(change.before) : null;
                } catch (RuntimeException invalid) {
                    throw new IOException("Invalid lyric recovery data", invalid);
                }
                if (prior != null && prior.length > LIMIT) throw new IOException("Oversized lyric recovery data");
                String current = hash(read(path));
                if (!data.committed && !Objects.equals(current, hash(prior)) && !Objects.equals(current, change.afterHash))
                    throw new IOException(Text.get("lyrics.recovery.conflict", pending));
            }
            if (!data.committed) for (Change change : data.changes) {
                Path path = pending.resolveSibling(change.name);
                if (change.existed) write(path, Base64.getDecoder().decode(change.before));
                else Files.deleteIfExists(path);
            }
            Files.delete(pending);
        }
    }

    public static void save(Path audio, String text, String attribution, boolean replace) throws IOException {
        synchronized (LOCK) {
            recover(audio);
            var paths = companions(audio);
            for (Path path : paths) safe(path);
            byte[] old = read(paths.getFirst());
            if (old != null && !replace) throw new FileAlreadyExistsException(paths.getFirst().toString());
            var desired = new LinkedHashMap<Path, byte[]>();
            // The first original remains available through any number of replacements.
            if (old != null && !Files.exists(paths.get(2), LinkOption.NOFOLLOW_LINKS)) desired.put(paths.get(2), old);
            desired.put(paths.getFirst(), text.getBytes(StandardCharsets.UTF_8));
            desired.put(paths.get(1), attribution.getBytes(StandardCharsets.UTF_8));
            transaction(audio, desired);
        }
    }

    public static void restore(Path audio) throws IOException {
        synchronized (LOCK) {
            recover(audio);
            var paths = companions(audio);
            byte[] original = read(paths.get(2));
            if (original == null) throw new NoSuchFileException(paths.get(2).toString());
            var desired = new LinkedHashMap<Path, byte[]>();
            desired.put(paths.getFirst(), original);
            desired.put(paths.get(1), null); // A restored user sidecar must not retain another version's attribution.
            transaction(audio, desired);
        }
    }

    static void transaction(Path audio, LinkedHashMap<Path, byte[]> desired) throws IOException {
        transaction(audio, desired, () -> {
        });
    }

    /**
     * Failure injection is used only by disposable filesystem tests.
     */
    static void transaction(Path audio, LinkedHashMap<Path, byte[]> desired, Runnable afterFirstWrite) throws IOException {
        synchronized (LOCK) {
            recover(audio);
            var changes = new ArrayList<Change>();
            for (var entry : desired.entrySet()) {
                if (!companions(audio).contains(entry.getKey()))
                    throw new IOException("Invalid lyric transaction path");
                byte[] before = read(entry.getKey());
                if (entry.getValue() != null && entry.getValue().length > LIMIT)
                    throw new IOException("Oversized lyric transaction");
                changes.add(new Change(entry.getKey().getFileName().toString(), before == null ? "" : Base64.getEncoder().encodeToString(before), before != null, hash(entry.getValue())));
            }
            Path pending = journal(audio);
            ConfigStore.atomicWrite(pending, ConfigStore.JSON.toJson(new Journal(1, false, changes)));
            int index = 0;
            try {
                for (var entry : desired.entrySet()) {
                    Change change = changes.get(index);
                    byte[] before = change.existed ? Base64.getDecoder().decode(change.before) : null;
                    if (!Objects.equals(hash(read(entry.getKey())), hash(before)))
                        throw new IOException("Lyric file changed during transaction");
                    if (entry.getValue() == null) Files.deleteIfExists(entry.getKey());
                    else write(entry.getKey(), entry.getValue());
                    if (index++ == 0) afterFirstWrite.run();
                }
                ConfigStore.atomicWrite(pending, ConfigStore.JSON.toJson(new Journal(1, true, changes)));
            } catch (Exception failure) {
                try {
                    recover(audio);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw new IOException("Lyric transaction failed", failure);
            }
            Files.deleteIfExists(pending);
        }
    }
}
