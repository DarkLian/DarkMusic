package com.darkmusic.client.lyrics;

import com.darkmusic.client.library.Track;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * File reads are serialized off the render thread; publication is immutable and track-keyed.
 */
public final class LyricsService implements AutoCloseable {
    private static final Lrc EMPTY = new Lrc(List.of());
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-lyrics").factory());
    private volatile Snapshot snapshot = new Snapshot("", new Lrc(List.of()));
    private String requested = "";
    private long next;
    private volatile boolean enabled = true;
    private long generation;
    private final java.util.LinkedHashMap<String, Cached> cache = new java.util.LinkedHashMap<>(8, .75f, true);

    public synchronized void enabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        requested = "";
        next = 0;
        snapshot = new Snapshot("", EMPTY);
        generation++;
    }

    public synchronized void request(Track track) {
        if (!enabled || track == null) return;
        String key = track.file().toString();
        long now = System.nanoTime();
        if (key.equals(requested) && now < next) return;
        requested = key;
        next = now + TimeUnit.SECONDS.toNanos(2);
        long ticket = ++generation;
        try {
            io.execute(() -> load(track, key, ticket));
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void load(Track track, String key, long ticket) {
        if (!enabled) return;
        Lrc data = new Lrc(List.of());
        try {
            String name = track.file().getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path file = track.file().resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".lrc");
            var fingerprint = new Fingerprint(stamp(track.file()), stamp(file));
            var previous = cache.get(key);
            if (previous != null && previous.fingerprint.equals(fingerprint)) data = previous.data;
            else {
                if (fingerprint.sidecar.size >= 0 && fingerprint.sidecar.size <= 1_000_000)
                    data = Lrc.parse(Files.readString(file));
                else if (fingerprint.audio.size >= 0) {
                    try {
                        var tag = org.jaudiotagger.audio.AudioFileIO.read(track.file().toFile()).getTag();
                        if (tag != null) data = Lrc.parse(tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS));
                    } catch (org.jaudiotagger.audio.exceptions.CannotReadException |
                             org.jaudiotagger.audio.exceptions.InvalidAudioFrameException ignored) {
                        // Unsupported embedded tags do not require reopening an unchanged file.
                    }
                }
                cache.put(key, new Cached(fingerprint, data));
                while (cache.size() > 8) cache.remove(cache.keySet().iterator().next());
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-lyrics").debug("No timestamped lyrics for {}", track.file(), e);
        }
        synchronized (this) {
            if (enabled && ticket == generation && requested.equals(key)) snapshot = new Snapshot(key, data);
        }
    }

    public Lrc get(Track track) {
        var s = snapshot;
        return enabled && track != null && s.path.equals(track.file().toString()) ? s.data : EMPTY;
    }

    @Override
    public void close() {
        io.shutdownNow();
    }

    private record Snapshot(String path, Lrc data) {
    }

    private static Stamp stamp(Path file) throws java.io.IOException {
        try {
            var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            return new Stamp(attributes.size(), attributes.lastModifiedTime());
        } catch (java.nio.file.NoSuchFileException missing) {
            return new Stamp(-1, null);
        }
    }

    private record Stamp(long size, java.nio.file.attribute.FileTime modified) {
    }

    private record Fingerprint(Stamp audio, Stamp sidecar) {
    }

    private record Cached(Fingerprint fingerprint, Lrc data) {
    }
}
