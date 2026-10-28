package com.darkmusic.client.playlist;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Instance-local playlists. References are matched against the scanned library, never opened as paths.
 */
public final class PlaylistStore {
    public static final String FAVORITES = "darkmusic-favorites";
    private final Path file;
    private final ExecutorService io;
    private final Consumer<String> warning;
    private List<Playlist> playlists = List.of();
    private boolean preserveInvalid;
    private long revision;
    private String pending;
    private boolean writing;
    private Set<Entry> favoriteEntries = Set.of();
    private Set<Entry> retainedImports = Set.of();
    private boolean retainedHealthy = true;
    private List<Track> indexedLibrary;
    private Map<String, Track> libraryIndex = Map.of();

    /**
     * Library snapshots are immutable; rebuild once when the snapshot list changes.
     */
    public synchronized Map<String, Track> index(List<Track> library) {
        if (indexedLibrary != library) {
            var next = new HashMap<String, Track>();
            library.forEach(track -> next.put(track.id(), track));
            libraryIndex = Collections.unmodifiableMap(next);
            indexedLibrary = library;
        }
        return libraryIndex;
    }

    public Set<Entry> retainedImports() {
        return retainedImports;
    }

    public void restore(List<Playlist> values, Set<Entry> retained) {
        retainedHealthy = true;
        retainedImports = Set.copyOf(retained);
        update(values);
    }

    /**
     * Publish the snapshot already committed by the backup transaction, without serializing it again on the UI thread.
     */
    public void restoredFromDisk(List<Playlist> values, Set<Entry> retained) {
        playlists = List.copyOf(values);
        retainedImports = Set.copyOf(retained);
        retainedHealthy = true;
        refreshFavorites();
        revision++;
    }

    public void acknowledgeTracks(List<Track> tracks) {
        if (retainedImports.isEmpty()) return;
        var remaining = new HashSet<>(retainedImports);
        tracks.forEach(t -> remaining.remove(Entry.of(t)));
        if (!remaining.equals(retainedImports)) {
            retainedImports = Set.copyOf(remaining);
            String json = ConfigStore.JSON.toJson(retainedImports);
            io.execute(() -> {
                try {
                    ConfigStore.atomicWrite(file.resolveSibling("playlist-retained.json"), json);
                } catch (Exception e) {
                    LoggerFactory.getLogger("darkmusic").warn("Cannot save imported playlist references", e);
                }
            });
        }
    }

    public PlaylistStore(Path directory, ExecutorService io, Consumer<String> warning) {
        file = directory.resolve("playlists.json");
        this.io = io;
        this.warning = warning;
    }

    public List<Playlist> read() {
        try {
            Path retained = file.resolveSibling("playlist-retained.json");
            if (Files.exists(retained)) {
                var values = ConfigStore.JSON.fromJson(Files.readString(retained), Entry[].class);
                retainedImports = Set.copyOf(Arrays.asList(values));
            }
        } catch (Exception e) {
            retainedHealthy = false;
            LoggerFactory.getLogger("darkmusic").warn("Cannot load imported playlist references", e);
        }
        try {
            if (Files.exists(file)) try (var reader = Files.newBufferedReader(file)) {
                var json = JsonParser.parseReader(reader);
                if (!json.isJsonArray()) throw new IllegalArgumentException("Missing playlist array");
                var result = new ArrayList<Playlist>();
                var ids = new HashSet<String>();
                int skipped = 0;
                for (var value : json.getAsJsonArray()) {
                    try {
                        var object = value.getAsJsonObject();
                        String id = object.get("id").getAsString(), name = object.get("name").getAsString();
                        var entries = new ArrayList<Entry>();
                        var known = new HashSet<Entry>();
                        for (var entry : object.getAsJsonArray("entries")) {
                            try {
                                Entry parsed = ConfigStore.JSON.fromJson(entry, Entry.class);
                                if (parsed == null) throw new IllegalArgumentException("Missing entry");
                                if (known.add(parsed)) entries.add(parsed);
                                else skipped++;
                            } catch (RuntimeException e) {
                                skipped++;
                            }
                        }
                        Playlist parsed = new Playlist(id, name, entries);
                        if (ids.add(id)) result.add(parsed);
                        else skipped++;
                    } catch (RuntimeException e) {
                        skipped++;
                    }
                }
                if (skipped > 0) {
                    preserveInvalid = true;
                    warning.accept(Text.get("playlist.status.recovered", skipped));
                }
                return List.copyOf(result);
            }
        } catch (Exception e) {
            preserveInvalid = true;
            warning.accept(Text.failure(e, "playlist.status.load_failed"));
        }
        return List.of();
    }

    public void loaded(List<Playlist> loaded) {
        playlists = List.copyOf(loaded);
        revision++;
        if (get(FAVORITES) == null) {
            var next = new ArrayList<>(playlists);
            next.addFirst(new Playlist(FAVORITES, "Favorite Music", List.of()));
            update(next);
        }
        refreshFavorites();
    }

    public String displayName(Playlist playlist) {
        return playlist.id.equals(FAVORITES) ? Text.get("library.darkmusic.favorites") : playlist.name;
    }

    public boolean favorite(Track track) {
        return track != null && !favoriteEntries.isEmpty() && favoriteEntries.contains(Entry.of(track));
    }

    private void refreshFavorites() {
        var favorites = get(FAVORITES);
        favoriteEntries = favorites == null ? Set.of() : Set.copyOf(favorites.entries);
    }

    public void toggleFavorite(Track track) {
        if (track == null) return;
        if (favorite(track)) removeAll(FAVORITES, Set.of(track.id()));
        else add(FAVORITES, track);
    }

    public void replaceTrack(Track before, Track after) {
        Entry old = Entry.of(before), replacement = Entry.of(after);
        if (retainedImports.contains(old)) {
            var retained = new HashSet<>(retainedImports);
            retained.remove(old);
            retained.add(replacement);
            retainedImports = Set.copyOf(retained);
        }
        var next = playlists.stream().map(p -> new Playlist(p.id, p.name,
            p.entries.stream().map(e -> e.equals(old) ? replacement : e).distinct().toList())).toList();
        if (!next.equals(playlists)) update(next);
    }

    public List<Playlist> all() {
        return playlists;
    }

    public long revision() {
        return revision;
    }

    /**
     * Run off-thread, after a complete scan. Unknown/inaccessible files are retained.
     */
    public static Set<Entry> missing(Path root, String rootId, List<Playlist> playlists, List<Track> tracks) {
        if (!Files.isDirectory(root) || !Files.isReadable(root)) return Set.of();
        var present = new HashSet<Entry>();
        tracks.forEach(t -> present.add(Entry.of(t)));
        var missing = new HashSet<Entry>();
        Path base = root.toAbsolutePath().normalize();
        for (var playlist : playlists)
            for (var entry : playlist.entries) {
                if (!entry.rootId.equals(rootId) || present.contains(entry)) continue;
                Path file = base.resolve(entry.path).normalize();
                if (file.startsWith(base) && Files.notExists(file)) missing.add(entry);
            }
        return Files.isDirectory(root) && Files.isReadable(root) ? Set.copyOf(missing) : Set.of();
    }

    public void removeMissing(Set<Entry> missing) {
        if (missing.isEmpty() || !retainedHealthy) return;
        var next = playlists.stream().map(p -> new Playlist(p.id, p.name,
            p.entries.stream().filter(e -> !missing.contains(e) || retainedImports.contains(e)).toList())).toList();
        if (!next.equals(playlists)) update(next);
    }

    public Playlist get(String id) {
        return playlists.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
    }

    public String create(String name) {
        String id = UUID.randomUUID().toString();
        var next = new ArrayList<>(playlists);
        next.add(new Playlist(id, name.strip(), List.of()));
        update(next);
        return id;
    }

    public void rename(String id, String name) {
        if (FAVORITES.equals(id)) return;
        var p = get(id);
        if (p != null) replace(new Playlist(id, name.strip(), p.entries));
    }

    public void delete(String id) {
        if (FAVORITES.equals(id)) return;
        update(playlists.stream().filter(p -> !p.id.equals(id)).toList());
    }

    public void add(String id, Track track) {
        var p = get(id);
        if (p == null) return;
        var entry = Entry.of(track);
        if (p.entries.contains(entry)) return;
        var entries = new ArrayList<>(p.entries);
        entries.add(entry);
        replace(new Playlist(id, p.name, entries));
    }

    public void addAll(String id, List<Track> tracks) {
        var p = get(id);
        if (p == null) return;
        var entries = new ArrayList<>(p.entries);
        var known = new HashSet<>(entries);
        for (var track : tracks) {
            var entry = Entry.of(track);
            if (known.add(entry)) entries.add(entry);
        }
        if (!entries.equals(p.entries)) replace(new Playlist(id, p.name, entries));
    }

    public List<Track> availableToAdd(String id, List<Track> library) {
        var p = get(id);
        if (p == null) return List.of();
        var existing = new HashSet<>(p.entries.stream().map(Entry::key).toList());
        return library.stream().filter(t -> !existing.contains(t.id())).toList();
    }

    public void removeAll(String id, Set<String> keys) {
        var p = get(id);
        if (p != null)
            replace(new Playlist(id, p.name, p.entries.stream().filter(e -> !keys.contains(e.key())).toList()));
    }

    /**
     * Explicitly deleted files are removed even from retained backup/import references.
     */
    public void removeTracks(Set<String> keys) {
        if (keys.isEmpty()) return;
        var next = playlists.stream().map(p -> new Playlist(p.id, p.name,
            p.entries.stream().filter(e -> !keys.contains(e.key())).toList())).toList();
        if (!next.equals(playlists)) update(next);
    }

    public void moveBlock(String id, int start, int count, int insertion) {
        var p = get(id);
        if (p == null) return;
        var order = new PlaylistDrag(start, count, insertion).order(p.entries.size());
        var entries = order.stream().map(p.entries::get).toList();
        if (!entries.equals(p.entries)) replace(new Playlist(id, p.name, entries));
    }

    public List<Track> resolve(String id, List<Track> library) {
        var p = get(id);
        if (p == null) return List.of();
        var index = index(library);
        return p.entries.stream().map(e -> index.get(e.key())).filter(Objects::nonNull).toList();
    }

    private void replace(Playlist replacement) {
        update(playlists.stream().map(p -> p.id.equals(replacement.id) ? replacement : p).toList());
    }

    private void update(List<Playlist> next) {
        playlists = List.copyOf(next);
        var referenced = new HashSet<Entry>();
        next.forEach(p -> referenced.addAll(p.entries));
        var retained = new HashSet<>(retainedImports);
        retained.retainAll(referenced);
        retainedImports = Set.copyOf(retained);
        String retainedJson = ConfigStore.JSON.toJson(retainedImports);
        if (retainedHealthy) io.execute(() -> {
            try {
                ConfigStore.atomicWrite(file.resolveSibling("playlist-retained.json"), retainedJson);
            } catch (Exception e) {
                LoggerFactory.getLogger("darkmusic").warn("Cannot save imported playlist references", e);
            }
        });
        refreshFavorites();
        revision++;
        String json = ConfigStore.JSON.toJson(playlists);
        synchronized (this) {
            pending = json;
            if (writing) return;
            writing = true;
            try {
                io.execute(this::drainWrites);
            } catch (RuntimeException failure) {
                writing = false;
                throw failure;
            }
        }
    }

    private void drainWrites() {
        while (true) {
            String json;
            synchronized (this) {
                json = pending;
                pending = null;
                if (json == null) {
                    writing = false;
                    return;
                }
            }
            try {
                if (preserveInvalid && Files.exists(file)) {
                    Files.copy(file, file.resolveSibling("playlists.invalid-" + System.currentTimeMillis() + ".json"));
                    preserveInvalid = false;
                }
                ConfigStore.atomicWrite(file, json);
            } catch (Exception e) {
                warning.accept(Text.failure(e, "playlist.status.save_failed"));
            }
        }
    }

    public record Entry(String rootId, String path) {
        public Entry {
            Track.checkReference(rootId, path);
        }

        public static Entry of(Track track) {
            return new Entry(track.rootId(), track.relativePath());
        }

        public String key() {
            return rootId + ":" + path;
        }
    }

    public record Playlist(String id, String name, List<Entry> entries) {
        public Playlist {
            if (id == null || id.isBlank() || name == null || name.isBlank() || name.length() > 100)
                throw new IllegalArgumentException("Invalid playlist");
            entries = List.copyOf(entries);
        }
    }
}
