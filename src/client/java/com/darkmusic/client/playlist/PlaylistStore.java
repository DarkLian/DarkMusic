package com.darkmusic.client.playlist;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.Track;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Instance-local playlists. References are matched against the scanned library, never opened as paths.
 */
public final class PlaylistStore {
    private final Path file;
    private final ExecutorService io;
    private final Consumer<String> warning;
    private List<Playlist> playlists = List.of();
    private boolean preserveInvalid;
    private long revision;
    private String pending;
    private boolean writing;

    public PlaylistStore(Path directory, ExecutorService io, Consumer<String> warning) {
        file = directory.resolve("playlists.json");
        this.io = io;
        this.warning = warning;
    }

    public List<Playlist> read() {
        try {
            if (Files.exists(file)) try (var reader = Files.newBufferedReader(file)) {
                var json = com.google.gson.JsonParser.parseReader(reader);
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
                    warning.accept("Recovered playlists; skipped " + skipped + " invalid or duplicate records. Original preserved before saving.");
                }
                return List.copyOf(result);
            }
        } catch (Exception e) {
            preserveInvalid = true;
            warning.accept("Playlists could not be loaded; original preserved: " + e.getMessage());
        }
        return List.of();
    }

    public void loaded(List<Playlist> loaded) {
        playlists = List.copyOf(loaded);
        revision++;
    }

    public List<Playlist> all() {
        return playlists;
    }

    public long revision() {
        return revision;
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
        var p = get(id);
        if (p != null) replace(new Playlist(id, name.strip(), p.entries));
    }

    public void delete(String id) {
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

    public void moveAll(String id, Set<String> keys, int direction) {
        var p = get(id);
        if (p == null || Math.abs(direction) != 1) return;
        var entries = new ArrayList<>(p.entries);
        if (direction < 0) {
            for (int i = 1; i < entries.size(); i++)
                if (keys.contains(entries.get(i).key()) && !keys.contains(entries.get(i - 1).key()))
                    Collections.swap(entries, i, i - 1);
        } else {
            for (int i = entries.size() - 2; i >= 0; i--)
                if (keys.contains(entries.get(i).key()) && !keys.contains(entries.get(i + 1).key()))
                    Collections.swap(entries, i, i + 1);
        }
        if (!entries.equals(p.entries)) replace(new Playlist(id, p.name, entries));
    }

    public void remove(String id, int index) {
        var p = get(id);
        if (p == null || index < 0 || index >= p.entries.size()) return;
        var entries = new ArrayList<>(p.entries);
        entries.remove(index);
        replace(new Playlist(id, p.name, entries));
    }

    public void move(String id, int index, int delta) {
        var p = get(id);
        if (p == null || index < 0 || index >= p.entries.size() || index + delta < 0 || index + delta >= p.entries.size())
            return;
        var entries = new ArrayList<>(p.entries);
        Collections.swap(entries, index, index + delta);
        replace(new Playlist(id, p.name, entries));
    }

    public void moveBlock(String id, int start, int count, int insertion) {
        var p = get(id);
        if (p == null) return;
        var order = new com.darkmusic.client.gui.PlaylistDrag(start, count, insertion).order(p.entries.size());
        var entries = order.stream().map(p.entries::get).toList();
        if (!entries.equals(p.entries)) replace(new Playlist(id, p.name, entries));
    }

    public List<Track> resolve(String id, List<Track> library) {
        var p = get(id);
        if (p == null) return List.of();
        Map<String, Track> index = new HashMap<>();
        library.forEach(t -> index.put(t.id(), t));
        return p.entries.stream().map(e -> index.get(e.key())).filter(Objects::nonNull).toList();
    }

    private void replace(Playlist replacement) {
        update(playlists.stream().map(p -> p.id.equals(replacement.id) ? replacement : p).toList());
    }

    private void update(List<Playlist> next) {
        playlists = List.copyOf(next);
        revision++;
        String json = ConfigStore.JSON.toJson(playlists);
        synchronized (this) {
            pending = json;
            if (writing) return;
            writing = true;
            io.execute(this::drainWrites);
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
                warning.accept("Could not save playlists: " + e.getMessage());
            }
        }
    }

    public record Entry(String rootId, String path) {
        public Entry {
            if (rootId == null || rootId.isBlank() || path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/")).contains(".."))
                throw new IllegalArgumentException("Invalid playlist file reference");
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
