package com.darkmusic.client.library;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Device-clock listening totals. Storage never runs on the audio or render thread.
 */
public final class ListeningHistory {
    public record Entry(long plays, long lastPlayed, double seconds) {
    }

    private record Data(int version, Map<String, Entry> entries) {
    }

    public static String json(Map<String, Entry> entries) {
        return ConfigStore.JSON.toJson(new Data(1, entries));
    }

    private final Path file;
    private final Executor io;
    private final Consumer<String> warning;
    private volatile ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private long session = -1, lastFlush, revision;
    private double heard;
    private boolean counted, dirty, ready;
    private boolean suspended;
    private boolean writable = true;
    private long lastWriteWarning;
    private boolean capacityReported;
    private volatile Rankings rankings;
    private Views views;
    private boolean ranking;

    private record Views(List<Track> library, Rankings frozen, long revision, List<Track> recent, List<Track> most) {
    }

    private record Rankings(String root, List<String> recent, List<String> most) {
    }

    /**
     * The newest songs by file date, newest first. Imports and downloads write new files, so they count from when
     * they were added; files copied in by hand keep their own dates.
     */
    public static List<Track> recentlyAdded(List<Track> library, int limit) {
        return library.stream().sorted(Comparator.comparingLong(Track::modified).reversed()
            .thenComparing(Track::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Track::id)).limit(limit).toList();
    }

    /**
     * Songs with no counted plays, retaining the active song until playback leaves it.
     */
    public synchronized List<Track> neverPlayed(List<Track> library) {
        return neverPlayed(library, null);
    }

    public synchronized List<Track> neverPlayed(List<Track> library, Track active) {
        var data = entries;
        return library.stream().filter(t -> {
            var entry = data.get(t.id());
            return active != null && active.id().equals(t.id()) || entry == null || entry.plays() == 0;
        }).toList();
    }

    public void playbackSource(String root, String collection, List<Track> queue, List<Track> library) {
        if (collection == null) {
            if (rankings != null) {
                rankings = null;
                synchronized (this) {
                    revision++;
                }
            }
            return;
        }
        var current = rankings;
        if (current != null && current.root.equals(root)) return;
        List<String> recent = collection(library, false).stream().map(Track::id).toList();
        List<String> most = collection(library, true).stream().map(Track::id).toList();
        var playingOrder = queue.stream().map(Track::id).toList();
        rankings = new Rankings(root, collection.equals("recent") ? playingOrder : recent, collection.equals("most") ? playingOrder : most);
        synchronized (this) {
            revision++;
        }
    }

    /**
     * While a backup restore owns the history file: nothing is counted and nothing is written, and unsaved progress
     * stays unsaved, so a write queued behind the restore cannot replace the restored file.
     */
    public synchronized void suspended(boolean value) {
        suspended = value;
    }

    public ListeningHistory(Path directory, Executor io, Consumer<String> warning) {
        file = directory.resolve("listening-history.json");
        this.io = io;
        this.warning = warning;
    }

    public void load() {
        try {
            Map<String, Entry> loaded = Map.of();
            if (Files.exists(file)) {
                if (Files.size(file) > 32_000_000) throw new IOException("Listening history exceeds 32 MB");
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (json.has("version")) {
                    var data = ConfigStore.JSON.fromJson(json, Data.class);
                    if (data.version != 1) throw new IllegalArgumentException("Unsupported history version");
                    loaded = data.entries;
                } else loaded = ConfigStore.JSON.fromJson(json, new TypeToken<Map<String, Entry>>() {
                }.getType());
            }
            validate(loaded);
            synchronized (this) {
                entries = new ConcurrentHashMap<>(loaded);
                ready = true;
                revision++;
            }
        } catch (Exception e) {
            synchronized (this) {
                ready = true;
                writable = false;
            }
            LoggerFactory.getLogger("darkmusic").warn("Cannot load listening history", e);
            // Preserve unreadable data until an explicit clear or import replaces it.
            warning.accept(Text.get("history.load_failed"));
        }
    }

    public static void validate(Map<String, Entry> values) {
        if (values == null || values.size() > 100_000) throw new IllegalArgumentException("Invalid listening history");
        values.forEach((key, e) -> {
            int colon = key.indexOf(':');
            if (colon <= 0 || colon == key.length() - 1 || e == null || e.plays < 0 || e.lastPlayed < 0 || !Double.isFinite(e.seconds) || e.seconds < 0)
                throw new IllegalArgumentException("Invalid listening entry");
            Track.checkReference(key.substring(0, colon), key.substring(colon + 1));
        });
    }

    public synchronized void heard(Track track, long ticket, double seconds) {
        if (!ready || !writable || suspended || track == null || !Double.isFinite(seconds) || seconds <= 0) return;
        if (session != ticket) {
            session = ticket;
            heard = 0;
            counted = false;
        }
        String key = track.id();
        if (!entries.containsKey(key) && entries.size() >= 100_000) {
            if (!capacityReported)
                LoggerFactory.getLogger("darkmusic").warn("Listening history reached its 100,000-track limit; existing history retained");
            capacityReported = true;
            return;
        }
        var old = entries.getOrDefault(key, new Entry(0, 0, 0));
        heard += seconds;
        double threshold = track.duration() > 0 ? Math.min(30, track.duration() / 2) : 30;
        boolean qualifies = !counted && heard >= threshold;
        counted |= qualifies;
        entries.put(key, new Entry(old.plays + (qualifies && old.plays < Long.MAX_VALUE ? 1 : 0),
            old.lastPlayed == 0 || heard == seconds ? System.currentTimeMillis() : old.lastPlayed, old.seconds + seconds));
        if (heard == seconds || qualifies || (long) (old.seconds / 60) != (long) ((old.seconds + seconds) / 60))
            revision++;
        dirty = true;
    }

    public Map<String, Entry> snapshot() {
        return Map.copyOf(entries);
    }

    /**
     * Retain this history generation; copying its immutable records is deferred to the storage worker.
     */
    public Supplier<Map<String, Entry>> snapshotSource() {
        var source = entries;
        return () -> Map.copyOf(source);
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized boolean ready() {
        return ready;
    }

    public synchronized void replace(Map<String, Entry> values) {
        validate(values);
        entries = new ConcurrentHashMap<>(values);
        ready = writable = true;
        dirty = true;
        revision++;
        session = -1;
        heard = 0;
        counted = false;
        rankings = null;
    }

    public synchronized void rename(Track old, Track replacement) {
        if (old.id().equals(replacement.id())) return;
        var frozen = rankings;
        if (frozen != null) rankings = new Rankings(frozen.root,
            frozen.recent.stream().map(id -> id.equals(old.id()) ? replacement.id() : id).distinct().toList(),
            frozen.most.stream().map(id -> id.equals(old.id()) ? replacement.id() : id).distinct().toList());
        var value = entries.remove(old.id());
        if (value != null) {
            entries.merge(replacement.id(), value, (a, b) -> new Entry(a.plays + b.plays, Math.max(a.lastPlayed, b.lastPlayed), a.seconds + b.seconds));
            dirty = true;
            revision++;
        }
    }

    public synchronized void flush(boolean force) {
        long now = System.nanoTime();
        if (suspended || !dirty || !force && now - lastFlush < 15_000_000_000L) return;
        var snapshot = entries;
        dirty = false;
        lastFlush = now;
        io.execute(() -> {
            try {
                ConfigStore.atomicWrite(file, json(Map.copyOf(snapshot)));
            } catch (Exception e) {
                boolean notify;
                synchronized (this) {
                    dirty = true;
                    long failedAt = System.nanoTime();
                    notify = lastWriteWarning == 0 || failedAt - lastWriteWarning >= TimeUnit.MINUTES.toNanos(5);
                    if (notify) lastWriteWarning = failedAt;
                }
                LoggerFactory.getLogger("darkmusic").warn("Cannot save listening history", e);
                if (notify) warning.accept(Text.get("history.save_failed"));
            }
        });
    }

    public synchronized List<Track> collection(List<Track> library, boolean mostPlayed) {
        var frozen = rankings;
        if (views != null && views.library == library && views.frozen == frozen && views.revision == revision)
            return mostPlayed ? views.most : views.recent;
        if (!ranking) {
            ranking = true;
            long ticket = revision;
            var source = entries;
            try {
                io.execute(() -> {
                    try {
                        List<Track> recent, most;
                        if (frozen != null && !library.isEmpty() && (frozen.root.isEmpty() || library.getFirst().rootId().equals(frozen.root))) {
                            var files = new HashMap<String, Track>();
                            library.forEach(track -> files.put(track.id(), track));
                            recent = frozen.recent.stream().map(files::get).filter(Objects::nonNull).toList();
                            most = frozen.most.stream().map(files::get).filter(Objects::nonNull).toList();
                        } else {
                            var data = Map.copyOf(source);
                            recent = rank(library, data, false);
                            most = rank(library, data, true);
                        }
                        synchronized (ListeningHistory.this) {
                            if (revision == ticket && entries == source && rankings == frozen)
                                views = new Views(library, frozen, ++revision, recent, most);
                            else revision++; // Wake the UI to request the newer generation after a stale job finishes.
                        }
                    } finally {
                        synchronized (ListeningHistory.this) {
                            ranking = false;
                        }
                    }
                });
            } catch (RuntimeException failure) {
                ranking = false;
                throw failure;
            }
        }
        return views != null && views.library == library && views.frozen == frozen ? mostPlayed ? views.most : views.recent : List.of();
    }

    private static List<Track> rank(List<Track> library, Map<String, Entry> values, boolean mostPlayed) {
        return library.stream().filter(t -> values.containsKey(t.id()) && (!mostPlayed || values.get(t.id()).plays > 0))
            .sorted(Comparator.<Track>comparingLong(t -> mostPlayed ? values.get(t.id()).plays : values.get(t.id()).lastPlayed).reversed()
                .thenComparing(Track::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Track::id)).toList();
    }
}
