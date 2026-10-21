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
                       Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history) {
    }

    public record Selection(boolean settings, boolean playlists, boolean shortcuts, boolean history, boolean replace,
                            boolean remap) {
    }

    public record Restore(Settings settings, List<PlaylistStore.Playlist> playlists, Set<PlaylistStore.Entry> retained,
                          Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history, int unresolved,
                          Selection selection) {
    }

    private static final int LIMIT = 8 * 1024 * 1024;

    private BackupArchive() {
    }

    public static Data snapshot(Settings settings, String root, List<PlaylistStore.Playlist> playlists,
                                Map<String, com.darkmusic.client.library.ListeningHistory.Entry> history) {
        JsonObject values = ConfigStore.JSON.toJsonTree(settings).getAsJsonObject();
        for (String name : List.of("musicDirectory", "roots", "ytDlpPath", "ffmpegPath", "ffprobePath", "javascriptPath", "outputDevice"))
            values.remove(name);
        return new Data(1, root, values, List.copyOf(playlists), Map.copyOf(history));
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
        var present = new HashSet<String>();
        tracks.forEach(t -> present.add(t.id()));
        var protectedEntries = new HashSet<>(retained);
        var result = new LinkedHashMap<String, PlaylistStore.Playlist>();
        if (!selected.playlists || !selected.replace) existing.forEach(p -> result.put(p.id(), p));
        int unresolved = 0;
        if (selected.playlists) for (var p : data.playlists) {
            var imported = new ArrayList<PlaylistStore.Entry>();
            for (var e : p.entries()) {
                var mapped = new PlaylistStore.Entry(selected.remap && e.rootId().equals(data.root) ? root : e.rootId(), e.path());
                if (!present.contains(mapped.key())) {
                    protectedEntries.add(mapped);
                    unresolved++;
                }
                if (!imported.contains(mapped)) imported.add(mapped);
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
            } else result.put(p.id(), new PlaylistStore.Playlist(p.id(), p.name(), imported));
        }
        var stats = new LinkedHashMap<String, com.darkmusic.client.library.ListeningHistory.Entry>();
        if (!selected.history || !selected.replace) stats.putAll(history);
        if (selected.history) data.history.forEach((key, value) -> {
            String mapped = selected.remap && key.startsWith(data.root + ":") ? root + key.substring(data.root.length()) : key;
            stats.merge(mapped, value, (a, b) -> new com.darkmusic.client.library.ListeningHistory.Entry(Math.max(a.plays(), b.plays()), Math.max(a.lastPlayed(), b.lastPlayed()), Math.max(a.seconds(), b.seconds())));
        });
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

    static void install(Path directory, Map<String, String> contents, Writer writer) throws IOException {
        Set<String> allowed = Set.of("config.json", "playlists.json", "playlist-retained.json", "listening-history.json");
        if (!allowed.containsAll(contents.keySet())) throw new IOException("Invalid restore target");
        Path rollback = directory.resolve("backups").resolve(UUID.randomUUID().toString());
        if (Files.isSymbolicLink(directory.resolve("backups"))) throw new IOException("Symbolic backup directory");
        Files.createDirectories(rollback);
        var old = new LinkedHashMap<Path, String>();
        for (var e : contents.entrySet()) {
            Path file = directory.resolve(e.getKey());
            if (Files.isSymbolicLink(file)) throw new IOException("Symbolic restore target");
            String before = Files.exists(file) ? Files.readString(file) : null;
            old.put(file, before);
            if (before != null) ConfigStore.atomicWrite(rollback.resolve(e.getKey()), before);
        }
        try {
            for (var e : contents.entrySet()) writer.write(directory.resolve(e.getKey()), e.getValue());
        } catch (IOException failure) {
            for (var e : old.entrySet())
                try {
                    if (e.getValue() == null) Files.deleteIfExists(e.getKey());
                    else ConfigStore.atomicWrite(e.getKey(), e.getValue());
                } catch (IOException restore) {
                    failure.addSuppressed(restore);
                }
            throw failure;
        }
    }
}
