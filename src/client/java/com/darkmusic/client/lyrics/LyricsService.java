package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.LyricsFiles;
import com.darkmusic.client.library.OggArtwork;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.AudioFormats;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ProcessRunner;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.tag.FieldKey;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounded, deduplicated local inspection. Rendering reads immutable track-keyed snapshots only.
 */
public final class LyricsService implements AutoCloseable {
    private static final Lrc EMPTY = new Lrc(List.of());
    public final LyricsLookup lookup;
    private final ConcurrentHashMap<String, Shifted> shifted = new ConcurrentHashMap<>();
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
    private final LongSupplier clock;
    private List<Track> backgroundTracks = List.of();
    private int backgroundCursor;
    private Track backgroundTrack;
    private long backgroundAt;
    private boolean backgroundActive;
    private long backgroundCacheRevision = -1;
    private final Map<String, Track> backgroundVisited = new HashMap<>();
    private Set<String> backgroundRoots;
    private boolean libraryRun;
    private ProcessRunner.Token libraryOwner;
    private long libraryRequestStart;

    public void startLibraryLookup() {
        cancelLibraryLookup();
        libraryRun = true;
        libraryOwner = new ProcessRunner.Token();
        backgroundActive = false;
        backgroundTracks = List.of();
        backgroundVisited.clear();
        backgroundAt = 0;
        libraryRequestStart = lookup.requests();
    }

    public void cancelLibraryLookup() {
        libraryRun = false;
        backgroundActive = false;
        lookup.cancelOwner(libraryOwner);
        libraryOwner = null;
    }

    public boolean libraryLookupRunning() {
        return libraryRun;
    }

    public int libraryChecked() {
        return backgroundVisited.size();
    }

    public int libraryTotal() {
        return backgroundTracks.size();
    }

    public long libraryRequests() {
        return lookup.requests() - libraryRequestStart;
    }

    public long libraryRetrySeconds() {
        return lookup.ownerRetrySeconds(libraryOwner);
    }

    public LyricsService() {
        this(null);
    }

    public LyricsService(Path directory) {
        this(directory, new LrclibProvider());
    }

    public LyricsService(Path directory, LyricsProvider provider) {
        this(directory, provider, System::nanoTime);
    }

    LyricsService(Path directory, LyricsProvider provider, LongSupplier clock) {
        this.clock = clock;
        lookup = new LyricsLookup(directory, provider, clock);
        io.prestartCoreThread();
    }

    /**
     * Walk the immutable library snapshot incrementally, without queuing the entire library.
     */
    public void background(List<Track> tracks, boolean active) {
        background(tracks, null, active);
    }

    public void background(List<Track> tracks, Set<String> healthyRoots, boolean active) {
        lookup.tickRetries();
        if (!enabled || closed || !active) {
            backgroundActive = false;
            return;
        }
        long now = clock.getAsLong();
        if (!backgroundActive || backgroundCacheRevision != lookup.cacheRevision()) {
            backgroundActive = true;
            backgroundCacheRevision = lookup.cacheRevision();
            backgroundTracks = tracks;
            backgroundRoots = healthyRoots;
            backgroundCursor = 0;
            backgroundTrack = null;
            backgroundVisited.clear();
        } else if (backgroundTrack == null && backgroundCursor >= backgroundTracks.size()
            && (backgroundTracks != tracks || !Objects.equals(backgroundRoots, healthyRoots))) {
            // Finish the current pass before adopting a rescan, so early songs cannot starve later ones.
            backgroundTracks = tracks;
            backgroundRoots = healthyRoots;
            backgroundCursor = 0;
        }
        // Initialize progress even while a previous lookup or provider cooldown is still active.
        if (now < backgroundAt || lookup.busy()) return;
        backgroundAt = now + TimeUnit.SECONDS.toNanos(1);
        if (backgroundTrack == null) {
            for (int skipped = 0; skipped < 1024 && backgroundCursor < backgroundTracks.size(); skipped++) {
                Track candidate = backgroundTracks.get(backgroundCursor++);
                if (!candidate.equals(backgroundVisited.get(candidate.id()))) {
                    backgroundTrack = candidate;
                    break;
                }
            }
            if (backgroundTrack == null) {
                if (backgroundCursor >= backgroundTracks.size() && !lookup.ownerOutstanding(libraryOwner))
                    libraryRun = false;
                return;
            }
        }
        if (healthyRoots != null && !healthyRoots.contains(backgroundTrack.rootId())) {
            backgroundTrack = null;
            return;
        }
        inspect(backgroundTrack);
        if (!inspected(backgroundTrack)) return;
        lookup.automatic(backgroundTrack, document(backgroundTrack).available(), libraryRun ? libraryOwner : null);
        backgroundVisited.put(backgroundTrack.id(), backgroundTrack);
        backgroundTrack = null;
    }

    public synchronized void enabled(boolean value) {
        if (enabled == value || closed) return;
        enabled = value;
        if (!value) cancelLibraryLookup();
        generation++;
        pending.clear();
        queue.clear();
        published = Map.of();
    }

    public void request(Track track) {
        lookup.retain(track);
        enqueue(track, true);
    }

    /**
     * Used for the playing or deliberately selected song, never for every visible library row.
     */
    public void request(Track track, boolean automatic) {
        request(track);
        if (automatic && enabled && inspected(track)) lookup.automatic(track, document(track).available());
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
                lookup.load(track);
                LyricsFiles.recover(track.file());
                Path sidecar = LyricsFiles.sidecar(track.file());
                Fingerprint fingerprint = new Fingerprint(stamp(track.file()), stamp(sidecar), stamp(sidecar.resolveSibling(sidecar.getFileName() + ".darkmusic-backup")), stamp(sidecar.resolveSibling(sidecar.getFileName() + ".darkmusic.json")), lookup.encoding(track));
                if (previous != null && fingerprint.equals(previous.fingerprint))
                    result = new Cached(fingerprint, previous.document, previous.info, System.nanoTime());
                else result = read(track.file(), sidecar, fingerprint);
            } catch (Exception failure) {
                LoggerFactory.getLogger("darkmusic-lyrics").debug("Lyric inspection failed for {}", track.file(), failure);
                result = new Cached(null, LyricsDocument.EMPTY, LyricsInfo.LOCAL_ERROR, System.nanoTime());
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
        String language = "und";
        if (fingerprint.sidecar.size >= 0) {
            if (fingerprint.sidecar.size > 1_000_000)
                return new Cached(fingerprint, LyricsDocument.EMPTY, LyricsInfo.LOCAL_ERROR, System.nanoTime());
            text = decode(readLocal(sidecar, 1_000_000), fingerprint.encoding);
        } else {
            if (fingerprint.audio.size < 0)
                return new Cached(fingerprint, LyricsDocument.EMPTY, LyricsInfo.LOCAL_ERROR, System.nanoTime());
            var container = AudioFormats.detect(audio);
            if (container == AudioFormats.Container.OPUS || container == AudioFormats.Container.VORBIS) {
                var tags = OggArtwork.tags(audio);
                text = tags.getOrDefault("lyrics", tags.getOrDefault("unsyncedlyrics", ""));
                language = tags.getOrDefault("language", "und");
            } else if (container == AudioFormats.Container.AAC || container == AudioFormats.Container.WEBM) {
                try {
                    var info = ExternalAudio.probe(audio);
                    var tags = new HashMap<String, String>();
                    if (info.has("format") && info.getAsJsonObject("format").has("tags"))
                        info.getAsJsonObject("format").getAsJsonObject("tags").entrySet().forEach(e -> tags.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString()));
                    if (info.has("streams")) for (var stream : info.getAsJsonArray("streams"))
                        if (stream.getAsJsonObject().has("tags"))
                            stream.getAsJsonObject().getAsJsonObject("tags").entrySet().forEach(e -> tags.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString()));
                    text = tags.getOrDefault("lyrics", tags.getOrDefault("unsyncedlyrics", ""));
                    language = tags.getOrDefault("language", "und");
                } catch (Exception unavailable) {
                    return new Cached(fingerprint, LyricsDocument.EMPTY, LyricsInfo.LOCAL_ERROR, System.nanoTime());
                }
            } else {
                try {
                    var tag = AudioFileIO.read(audio.toFile()).getTag();
                    text = tag == null ? "" : tag.getFirst(FieldKey.LYRICS);
                    language = tag == null ? "und" : tag.getFirst(FieldKey.LANGUAGE);
                } catch (CannotReadException unsupported) {
                    if (unsupported.getMessage() == null || !unsupported.getMessage().contains("No Reader associated"))
                        throw unsupported;
                    return new Cached(fingerprint, LyricsDocument.EMPTY, LyricsInfo.LOCAL_MISSING, System.nanoTime());
                }
            }
        }
        if (text.length() > 1_000_000)
            return new Cached(fingerprint, LyricsDocument.EMPTY, LyricsInfo.LOCAL_ERROR, System.nanoTime());
        text = LyricsDocument.localText(text);
        var document = LyricsDocument.of(fingerprint.sidecar.size >= 0 ? "sidecar" : "embedded", "", "", "", "", 0, language, LyricsDocument.Variant.UNKNOWN, false, false, text);
        Path provenance = sidecar.resolveSibling(sidecar.getFileName() + ".darkmusic.json");
        if (fingerprint.sidecar.size >= 0 && Files.isRegularFile(provenance, LinkOption.NOFOLLOW_LINKS) && Files.size(provenance) <= 4_000_000)
            try {
                var saved = ConfigStore.JSON.fromJson(new String(readLocal(provenance, 4_000_000), StandardCharsets.UTF_8), LyricsDocument.class);
                if (saved != null && text.equals(saved.text()))
                    document = LyricsDocument.of(saved.provider(), saved.recordId(), saved.title(), saved.artist(), saved.album(), saved.duration(), saved.language(), saved.variant(), saved.generated(), saved.instrumental(), text);
            } catch (RuntimeException ignored) {
            }
        LyricsInfo info = document.available() ? LyricsInfo.AVAILABLE : LyricsInfo.LOCAL_MISSING;
        return new Cached(fingerprint, document, info, System.nanoTime());
    }

    static String decode(byte[] bytes) throws CharacterCodingException {
        int skip = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf)
            skip = 3;
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        }
        return charset.newDecoder().decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
    }

    static String decode(byte[] bytes, String encoding) throws CharacterCodingException {
        if (encoding == null || encoding.equals("Auto")) return decode(bytes);
        return Charset.forName(encoding).newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    public LyricsDocument document(Track track) {
        if (!enabled || track == null) return LyricsDocument.EMPTY;
        var choice = lookup.choice(track);
        if (choice.selected() != null && choice.pinned()) return choice.selected();
        Cached value = published.get(track.file().toString());
        if (value != null && value.document.available()) return value.document;
        if (choice.selected() != null) return choice.selected();
        return value == null ? LyricsDocument.EMPTY : value.document;
    }

    public Lrc get(Track track) {
        var document = document(track);
        if (track == null || !enabled) return EMPTY;
        int offset = lookup.choice(track).offset();
        if (offset == 0) return document.timed();
        String key = track.id();
        var prior = shifted.get(key);
        if (prior != null && prior.document == document && prior.offset == offset) return prior.data;
        var data = new Lrc(document.timed().lines().stream().map(l -> new Lrc.Line(l.millis() + offset, l.text())).toList());
        if (shifted.size() >= MAX_CACHED) shifted.clear();
        shifted.put(key, new Shifted(document, offset, data));
        return data;
    }

    public LyricsInfo info(Track track) {
        var document = document(track);
        if (document.instrumental()) return LyricsInfo.INSTRUMENTAL;
        if (document.available()) return document.synchronizedLyrics() ? LyricsInfo.AVAILABLE : LyricsInfo.PLAIN;
        var known = lookup.knownInfo(track);
        if (enabled && known != null) return known;
        if (enabled) {
            var online = LyricsInfo.lookup(lookup.snapshot(track).status());
            if (online != null) return online;
        }
        Cached value = enabled && track != null ? published.get(track.file().toString()) : null;
        if (value == null && enabled && checking(track)) return LyricsInfo.CHECKING;
        return value == null ? LyricsInfo.UNKNOWN : value.info;
    }

    public String status(Track track) {
        String text = Text.get(info(track).key());
        long retry = lookup.retrySeconds(track);
        return retry == 0 ? text : text + " | " + Text.get("lyrics.retry_in", retry);
    }

    private synchronized boolean checking(Track track) {
        return track != null && pending.containsKey(track.file().toString());
    }

    public boolean sidecarExists(Track track) {
        var cached = track == null ? null : published.get(track.file().toString());
        return cached != null && cached.fingerprint != null && cached.fingerprint.sidecar.size >= 0;
    }

    public boolean backupExists(Track track) {
        var cached = track == null ? null : published.get(track.file().toString());
        return cached != null && cached.fingerprint != null && cached.fingerprint.backup.size >= 0;
    }

    public boolean inspected(Track track) {
        return track != null && published.containsKey(track.file().toString());
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
        lookup.close();
        shifted.clear();
    }

    static byte[] readLocal(Path file, int limit) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Oversized lyric file");
            return bytes;
        }
    }

    private static Stamp stamp(Path file) throws IOException {
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) throw new IOException("Symbolic lyric path");
            return new Stamp(attributes.size(), attributes.lastModifiedTime());
        } catch (NoSuchFileException missing) {
            return new Stamp(-1, null);
        }
    }

    private record Stamp(long size, FileTime modified) {
    }

    private record Fingerprint(Stamp audio, Stamp sidecar, Stamp backup, Stamp provenance, String encoding) {
    }

    private record Shifted(LyricsDocument document, int offset, Lrc data) {
    }

    private record Cached(Fingerprint fingerprint, LyricsDocument document, LyricsInfo info, long checked) {
    }

    /**
     * For tests: the worker that reads and writes lyric files.
     */
    ThreadPoolExecutor io() {
        return io;
    }
}
