package com.darkmusic.client.config;

import com.darkmusic.client.playlist.PlaylistStore;
import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Portable user data only. Archives never extract paths or contain executable files.
 */
public final class BackupArchive {
    public record Data(int version, String root, JsonObject settings, List<PlaylistStore.Playlist> playlists,
                       Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history,
                       Map<String, String> rootNames) {
        public Data(int version, String root, JsonObject settings, List<PlaylistStore.Playlist> playlists,
                    Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history) {
            this(version, root, settings, playlists, history, Map.of());
        }
    }

    public record Selection(boolean settings, boolean playlists, boolean shortcuts, boolean history, boolean replace,
                            boolean remap) {
    }

    public record Restore(Settings settings, List<PlaylistStore.Playlist> playlists, Set<PlaylistStore.Entry> retained,
                          Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history, int unresolved,
                          Selection selection) {
    }

    /**
     * Capturing this job is cheap; preparation, history copying, and ZIP serialization belong on the storage executor.
     */
    public record Export(String settingsJson, String root, List<PlaylistStore.Playlist> playlists,
                         Map<String, String> shortcuts,
                         java.util.function.Supplier<Map<String, com.darkmusic.client.library.ListeningHistory.Entry>> history) {
        public Export {
            Objects.requireNonNull(settingsJson);
            Objects.requireNonNull(history);
            playlists = List.copyOf(playlists);
            shortcuts = Map.copyOf(shortcuts);
        }

        public void write(Path destination) throws IOException {
            var settings = ConfigStore.JSON.fromJson(settingsJson, Settings.class);
            settings.shortcuts = new LinkedHashMap<>(shortcuts);
            BackupArchive.write(destination, snapshot(settings, root, playlists, history.get()));
        }
    }

    private static final int LIMIT = 8 * 1024 * 1024;

    private BackupArchive() {
    }

    public static Data snapshot(Settings settings, String root, List<PlaylistStore.Playlist> playlists,
                                Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history) {
        JsonObject values = ConfigStore.JSON.toJsonTree(settings).getAsJsonObject();
        for (String name : List.of("musicDirectory", "musicFolders", "roots", "ytDlpPath", "ffmpegPath", "ffprobePath", "javascriptPath", "outputDevice"))
            values.remove(name);
        var names = new LinkedHashMap<String, String>();
        if (settings.musicFolders != null) for (String folder : settings.musicFolders) {
            var path = Path.of(folder);
            String id = settings.roots.get(folder);
            if (id != null) names.put(id, path.getFileName() == null ? path.toString() : path.getFileName().toString());
        }
        return new Data(1, root, values, List.copyOf(playlists), Map.copyOf(history), Map.copyOf(names));
    }

    public static void write(Path destination, Data data) throws IOException {
        byte[] bytes = ConfigStore.JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("Backup too large");
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target)) throw new IOException("Symbolic backup destination");
        Path temporary = Files.createTempFile(target.getParent(), "darkmusic-backup-", ".tmp");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("setup.json"));
                zip.write(bytes);
                zip.closeEntry();
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Data read(Path source) throws IOException {
        if (Files.size(source) > LIMIT) throw new IOException("Backup too large");
        try (var zip = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8)) {
            var entry = zip.getNextEntry();
            if (entry == null || !entry.getName().equals("setup.json") || entry.isDirectory())
                throw new IOException("Invalid backup contents");
            byte[] bytes = zip.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT || zip.getNextEntry() != null)
                throw new IOException("Invalid backup size or contents");
            try {
                Data data = ConfigStore.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Data.class);
                if (data == null || data.version != 1 || data.root == null || data.root.isBlank() || data.root.length() > 256 || data.root.contains(":") || data.settings == null || data.playlists == null || data.history == null
                    || data.playlists.size() > 1000 || data.history.size() > 100_000)
                    throw new IllegalArgumentException();
                Settings values = ConfigStore.JSON.fromJson(data.settings, Settings.class);
                if (values == null || values.schemaVersion != 1) throw new IllegalArgumentException();
                values.validate();
                if (data.rootNames != null && (data.rootNames.size() > 1000 || data.rootNames.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 256 || e.getValue() == null || e.getValue().length() > 256)))
                    throw new IllegalArgumentException();
                var ids = new HashSet<String>();
                int entries = 0;
                for (var p : data.playlists) {
                    if (!ids.add(p.id()) || (entries += p.entries().size()) > 100_000)
                        throw new IllegalArgumentException();
                }
                com.darkmusic.client.library.ListeningHistory.validate(data.history);
                return data;
            } catch (RuntimeException failure) {
                throw new IOException("Invalid or unsupported DarkMusic backup", failure);
            }
        }
    }

    public static Restore prepare(Data data, Selection selected, Settings current, String root,
                                  List<com.darkmusic.client.library.Track> tracks, List<PlaylistStore.Playlist> existing,
                                  Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history,
                                  Set<PlaylistStore.Entry> retained) {
        var values = selected.settings ? ConfigStore.JSON.fromJson(data.settings, Settings.class)
            : ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(current), Settings.class);
        values.musicDirectory = current.musicDirectory;
        values.musicFolders = current.musicFolders == null ? null : List.copyOf(current.musicFolders);
        values.roots = new LinkedHashMap<>(current.roots);
        values.ytDlpPath = current.ytDlpPath;
        values.ffmpegPath = current.ffmpegPath;
        values.ffprobePath = current.ffprobePath;
        values.javascriptPath = current.javascriptPath;
        values.outputDevice = current.outputDevice;
        Settings importedSettings = ConfigStore.JSON.fromJson(data.settings, Settings.class);
        importedSettings.validate();
        values.shortcuts = selected.shortcuts ? importedSettings.shortcuts : new LinkedHashMap<>(current.shortcuts);
        values.nativeShortcutNames = current.nativeShortcutNames;
        values.validate();
        var remappedRoots = new HashMap<String, String>();
        if (selected.remap) {
            remappedRoots.put(data.root, current.roots.containsValue(data.root) ? data.root : root);
            if (data.rootNames != null && current.musicFolders != null) data.rootNames.forEach((id, name) -> {
                if (current.roots.containsValue(id) || id.equals(data.root)) return;
                var matches = current.musicFolders.stream().filter(p -> {
                    Path path = Path.of(p);
                    return path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase(name);
                }).map(current.roots::get).filter(Objects::nonNull).distinct().toList();
                if (matches.size() == 1) remappedRoots.put(id, matches.getFirst());
            });
        }
        var present = new HashSet<String>();
        tracks.forEach(t -> present.add(t.id()));
        var protectedEntries = new HashSet<>(retained);
        var result = new LinkedHashMap<String, PlaylistStore.Playlist>();
        if (!selected.playlists || !selected.replace) existing.forEach(p -> result.put(p.id(), p));
        int unresolved = 0;
        if (selected.playlists) for (var p : data.playlists) {
            var imported = new LinkedHashSet<PlaylistStore.Entry>();
            for (var e : p.entries()) {
                var mapped = new PlaylistStore.Entry(remappedRoots.getOrDefault(e.rootId(), e.rootId()), e.path());
                if (!imported.add(mapped)) continue;
                if (!present.contains(mapped.key())) {
                    protectedEntries.add(mapped);
                    unresolved++;
                }
            }
            var old = result.get(p.id());
            // In merge mode, identify the same named collection without duplicating its contents.
            if (old == null && !selected.replace && !p.id().equals(PlaylistStore.FAVORITES)) {
                var matches = result.values().stream().filter(x -> !x.id().equals(PlaylistStore.FAVORITES) && x.name().equalsIgnoreCase(p.name())).toList();
                if (matches.size() == 1) old = matches.getFirst();
            }
            if (old != null) {
                var combined = new LinkedHashSet<>(old.entries());
                combined.addAll(imported);
                result.put(old.id(), new PlaylistStore.Playlist(old.id(), old.name(), List.copyOf(combined)));
            } else result.put(p.id(), new PlaylistStore.Playlist(p.id(), p.name(), List.copyOf(imported)));
        }
        var stats = new LinkedHashMap<String, com.darkmusic.client.library.ListeningHistory.Entry>();
        if (!selected.history || !selected.replace) stats.putAll(history);
        if (selected.history) data.history.forEach((key, value) -> {
            int colon = key.indexOf(':');
            String mapped = remappedRoots.getOrDefault(key.substring(0, colon), key.substring(0, colon)) + key.substring(colon);
            stats.merge(mapped, value, (a, b) -> new com.darkmusic.client.library.ListeningHistory.Entry(Math.max(a.plays(), b.plays()), Math.max(a.lastPlayed(), b.lastPlayed()), Math.max(a.seconds(), b.seconds())));
        });
        var referenced = new HashSet<PlaylistStore.Entry>();
        result.values().forEach(p -> referenced.addAll(p.entries()));
        protectedEntries.retainAll(referenced);
        return new Restore(values, List.copyOf(result.values()), Set.copyOf(protectedEntries), Map.copyOf(stats), unresolved, selected);
    }

    /**
     * Serial storage executor only. Keep a durable rollback copy before replacing any configuration.
     */
    public static void install(Path directory, Map<String, String> contents) throws IOException {
        install(directory, contents, ConfigStore::atomicWrite);
    }

    @FunctionalInterface
    interface Writer {
        void write(Path path, String value) throws IOException;
    }

    private static final Set<String> TARGETS = Set.of("config.json", "playlists.json", "playlist-retained.json", "listening-history.json", "options.txt");
    private static final String JOURNAL = "restore-pending.json";

    private record Recovery(int version, String backup, Map<String, Boolean> existed) {
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
        for (var e : contents.entrySet()) {
            Path file = target(directory, e.getKey());
            boolean present = Files.exists(file);
            existed.put(e.getKey(), present);
            if (present) ConfigStore.atomicWrite(rollback.resolve(e.getKey()), Files.readString(file));
        }
        Path journal = directory.resolve(JOURNAL);
        if (Files.isSymbolicLink(journal)) throw new IOException("Symbolic recovery journal");
        ConfigStore.atomicWrite(journal, ConfigStore.JSON.toJson(new Recovery(1, rollback.getFileName().toString(), existed)));
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
            if (recovery == null || recovery.version != 1 || recovery.existed == null || recovery.existed.isEmpty()
                || !TARGETS.containsAll(recovery.existed.keySet()) || recovery.existed.values().stream().anyMatch(Objects::isNull)
                || recovery.backup == null || !UUID.fromString(recovery.backup).toString().equals(recovery.backup))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid restore recovery journal", invalid);
        }
        Path backups = directory.resolve("backups"), originals = backups.resolve(recovery.backup);
        if (Files.isSymbolicLink(backups) || Files.isSymbolicLink(originals))
            throw new IOException("Unsafe recovery directory");
        // Validate and read every original before replacing anything. Re-running recovery is idempotent.
        var before = new LinkedHashMap<Path, String>();
        for (var e : recovery.existed.entrySet()) {
            Path destination = target(directory, e.getKey()), source = originals.resolve(e.getKey());
            if (e.getValue() && (Files.isSymbolicLink(source) || !Files.isRegularFile(source) || Files.size(source) > 32_000_000))
                throw new IOException("Missing or invalid recovery copy");
            before.put(destination, e.getValue() ? Files.readString(source) : null);
        }
        IOException failure = null;
        for (var e : before.entrySet())
            try {
                if (e.getValue() == null) Files.deleteIfExists(e.getKey());
                else ConfigStore.atomicWrite(e.getKey(), e.getValue());
            } catch (IOException restore) {
                if (failure == null) failure = restore;
                else failure.addSuppressed(restore);
            }
        if (failure != null) throw failure;
        Files.delete(journal);
        pruneBackups(directory);
        return true;
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
            org.slf4j.LoggerFactory.getLogger("darkmusic").debug("Deferred old recovery-copy cleanup", failure);
        }
    }

    public static boolean recoveryPending(Path directory) {
        return Files.exists(directory.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS);
    }
}
