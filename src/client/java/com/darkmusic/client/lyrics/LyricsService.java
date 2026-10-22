package com.darkmusic.client.lyrics;

import com.darkmusic.client.library.Track;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, deduplicated local inspection. Rendering reads immutable track-keyed snapshots only.
 */
public final class LyricsService implements AutoCloseable {
    private static final Lrc EMPTY = new Lrc(List.of());
    private static final int MAX_CACHED = 64, MAX_PENDING = 8;
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(MAX_PENDING);
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue,
        Thread.ofPlatform().daemon().name("darkmusic-lyrics").factory());
    private final Map<String, Job> pending = new LinkedHashMap<>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private volatile Map<String, Cached> published = Map.of();
    private volatile boolean enabled = true;
    private boolean closed;
    private long generation;

    public LyricsService() {
        io.prestartCoreThread();
    }

    public synchronized void enabled(boolean value) {
        if (enabled == value || closed) return;
        enabled = value;
        generation++;
        pending.clear();
        queue.clear();
        published = Map.of();
    }

    public void request(Track track) {
        enqueue(track, true);
    }

    public void inspect(Track track) {
        enqueue(track, false);
    }

    private synchronized void enqueue(Track track, boolean priority) {
        if (!enabled || closed || track == null) return;
        String key = track.file().toString();
        Cached known = published.get(key);
        long now = System.nanoTime();
        if (known != null && now - known.checked < TimeUnit.SECONDS.toNanos(priority ? 2 : 5)) return;
        Job existing = pending.get(key);
        if (existing != null) {
            if (priority && queue.remove(existing)) {
                existing.priority = true;
                queue.offerFirst(existing);
            }
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            if (!priority) return;
            Job victim = pending.values().stream().filter(j -> !j.priority && queue.contains(j)).findFirst().orElse(null);
            if (victim == null || !queue.remove(victim)) return;
            pending.remove(victim.key);
        }
        Job job = new Job(track, key, generation, priority);
        pending.put(key, job);
        if (!(priority ? queue.offerFirst(job) : queue.offerLast(job))) pending.remove(key, job);
    }

    private final class Job implements Runnable {
        final Track track;
        final String key;
        final long ticket;
        boolean priority;

        Job(Track track, String key, long ticket, boolean priority) {
            this.track = track;
            this.key = key;
            this.ticket = ticket;
            this.priority = priority;
        }

        @Override
        public void run() {
            Cached previous;
            synchronized (LyricsService.this) {
                if (!enabled || closed || ticket != generation) return;
                previous = cache.get(key);
            }
            Cached result;
            try {
                Path sidecar = com.darkmusic.client.download.DownloadLyrics.sidecar(track.file());
                Fingerprint fingerprint = new Fingerprint(stamp(track.file()), stamp(sidecar));
                if (previous != null && fingerprint.equals(previous.fingerprint))
                    result = new Cached(fingerprint, previous.data, previous.info, System.nanoTime());
                else result = read(track.file(), sidecar, fingerprint);
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-lyrics").debug("Lyric inspection failed for {}", track.file(), failure);
                result = new Cached(null, EMPTY, LyricsInfo.UNKNOWN, System.nanoTime());
            }
            synchronized (LyricsService.this) {
                pending.remove(key, this);
                if (!enabled || closed || ticket != generation) return;
                cache.put(key, result);
                while (cache.size() > MAX_CACHED) cache.remove(cache.keySet().iterator().next());
                // Revalidate retained fingerprints after re-enabling; never republish unchecked old entries.
                var next = new LinkedHashMap<>(published);
                next.put(key, result);
                next.keySet().retainAll(cache.keySet());
                published = Map.copyOf(next);
            }
        }
    }

    private static Cached read(Path audio, Path sidecar, Fingerprint fingerprint) throws Exception {
        String text;
        if (fingerprint.sidecar.size >= 0) {
            if (fingerprint.sidecar.size > 1_000_000)
                return new Cached(fingerprint, EMPTY, LyricsInfo.UNKNOWN, System.nanoTime());
            text = decode(Files.readAllBytes(sidecar));
        } else {
            if (fingerprint.audio.size < 0)
                return new Cached(fingerprint, EMPTY, LyricsInfo.UNKNOWN, System.nanoTime());
            var tag = org.jaudiotagger.audio.AudioFileIO.read(audio.toFile()).getTag();
            text = tag == null ? "" : tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS);
        }
        if (text.length() > 1_000_000) return new Cached(fingerprint, EMPTY, LyricsInfo.UNKNOWN, System.nanoTime());
        Lrc data = Lrc.parse(text);
        LyricsInfo info = data.lines().stream().anyMatch(line -> !line.text().isBlank()) ? LyricsInfo.AVAILABLE : text.isBlank() ? LyricsInfo.UNAVAILABLE : LyricsInfo.UNKNOWN;
        return new Cached(fingerprint, data, info, System.nanoTime());
    }

    static String decode(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        int skip = 0;
        java.nio.charset.Charset charset = java.nio.charset.StandardCharsets.UTF_8;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf)
            skip = 3;
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = java.nio.charset.StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = java.nio.charset.StandardCharsets.UTF_16BE;
            skip = 2;
        }
        return charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
    }

    public Lrc get(Track track) {
        Cached value = enabled && track != null ? published.get(track.file().toString()) : null;
        return value == null ? EMPTY : value.data;
    }

    public LyricsInfo info(Track track) {
        Cached value = enabled && track != null ? published.get(track.file().toString()) : null;
        return value == null ? LyricsInfo.UNKNOWN : value.info;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public int cachedCount() {
        return published.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        enabled = false;
        generation++;
        pending.clear();
        queue.clear();
        cache.clear();
        published = Map.of();
        io.shutdownNow();
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

    private record Cached(Fingerprint fingerprint, Lrc data, LyricsInfo info, long checked) {
    }
}
