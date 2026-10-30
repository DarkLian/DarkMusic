package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.tools.ProcessRunner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * Optional background R128 analysis of unmodified files. Persistent keys include path, size and mtime.
 */
public final class LoudnessCache implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("darkmusic-audio");
    private final Map<String, Reading> results = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> storedKeys = new HashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private static final int READINGS = 65536;
    // Inspections that finished without a stored reading; the oldest is forgotten first.
    private final Set<String> attempted = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > READINGS;
        }
    }));
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(2);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue, Thread.ofPlatform().daemon().name("darkmusic-loudness").factory());
    private final Set<Inspection> inspections = ConcurrentHashMap.newKeySet();
    private volatile Batch batch;
    private final Path cache;
    private static final long MAX_CACHE_BYTES = 64_000_000;
    private final Object persistence = new Object();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("darkmusic-loudness-cache").factory());
    private boolean dirty;
    private long revision;
    private volatile long nextFlush;
    private final Object flushLock = new Object();
    private static final Gson STORAGE_JSON = new Gson();
    private boolean capacityReported;
    private boolean preserveOriginal;
    private volatile Lookup recent, previous;
    private volatile boolean closed;
    private CompletableFuture<Void> closing;
    private final CompletableFuture<Void> loaded = new CompletableFuture<>();

    public enum Source {TRACK_TAGS, ALBUM_TAGS, ANALYSIS, LEGACY}

    public enum State {OFF, PENDING, READY, NO_MEASUREMENT, FAILED, NEXT_SESSION}

    public record AnalysisProgress(boolean running, int processed, int total, int available) {
        public static final AnalysisProgress IDLE = new AnalysisProgress(false, 0, 0, 0);
    }

    public synchronized void parallel(boolean enabled) {
        if (closed) return;
        int workers = enabled ? Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 3) : 1;
        if (workers > executor.getMaximumPoolSize()) executor.setMaximumPoolSize(workers);
        executor.setCorePoolSize(workers);
        executor.setMaximumPoolSize(workers);
    }

    public AnalysisProgress analysisProgress() {
        var current = batch;
        return current == null ? AnalysisProgress.IDLE : current.progress;
    }

    /**
     * The immutable library snapshot is traversed only by the background coordinator.
     */
    public synchronized void analyzeLibrary(List<Track> tracks, AudioSettings settings) {
        if (closed || settings.neutral || !settings.normalization || !settings.analyzeLoudness || batch != null && batch.running)
            return;
        parallel(settings.parallelLoudnessAnalysis);
        var next = new Batch(tracks, settings.playbackCopy());
        batch = next;
        Thread.ofVirtual().name("darkmusic-library-loudness").start(next);
    }

    public void cancelAnalysis() {
        var current = batch;
        if (current == null) return;
        current.cancelled = true;
        for (var task : inspections)
            if (task.owner == current) {
                task.token.cancel();
                if (queue.remove(task)) discard(task);
            }
    }

    private final class Batch implements Runnable {
        private final List<Track> tracks;
        private final AudioSettings settings;
        private volatile boolean running = true, cancelled;
        private int processed, available;
        private volatile AnalysisProgress progress;

        private Batch(List<Track> tracks, AudioSettings settings) {
            this.tracks = tracks;
            this.settings = settings;
            progress = new AnalysisProgress(true, 0, tracks.size(), 0);
        }

        @Override
        public void run() {
            try {
                loaded.join();
                var iterator = tracks.iterator();
                var waiting = new ArrayList<Lookup>();
                while (!closed && !cancelled && (iterator.hasNext() || !waiting.isEmpty())) {
                    for (var entries = waiting.iterator(); entries.hasNext(); ) {
                        var lookup = entries.next();
                        boolean ready = results.containsKey(lookup.key);
                        if (ready || attempted.contains(lookup.attempt) || retryAfter.containsKey(lookup.attempt)) {
                            if (ready) available++;
                            processed++;
                            entries.remove();
                        } else if (!pending.contains(lookup.attempt)) request(lookup.track, settings, false, this);
                    }
                    while (!cancelled && iterator.hasNext() && waiting.size() < executor.getCorePoolSize() + 2) {
                        Track track = iterator.next();
                        waiting.add(lookup(track, settings));
                        request(track, settings, false, this);
                    }
                    progress = new AnalysisProgress(true, processed, tracks.size(), available);
                    if (!waiting.isEmpty()) Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                progress = new AnalysisProgress(false, processed, tracks.size(), available);
                running = false;
            }
        }
    }

    /**
     * A measurement is selected once, before this track contributes any audible PCM.
     */
    public final class Session {
        private final Reading reading;
        private final Lookup lookup;

        private Session(Reading reading, Lookup lookup) {
            this.reading = reading;
            this.lookup = lookup;
        }

        public boolean matches(AudioSettings settings) {
            return lookup.mode == settings.gainMode && lookup.analyze == settings.analyzeLoudness;
        }

        public double gain(AudioSettings settings) {
            return reading == null ? 1 : gainFor(reading, settings.targetLufs);
        }

        public Status status(AudioSettings settings, double actualGain) {
            if (settings.neutral || !settings.normalization) return Status.OFF;
            var status = LoudnessCache.this.status(lookup.track, settings, actualGain);
            if (reading == null) return new Status(status.state == State.READY ? State.NEXT_SESSION : status.state,
                null, settings.targetLufs, AudioDynamics.db(actualGain), false, false);
            return readingStatus(reading, settings, actualGain);
        }
    }

    /**
     * Audio/preload worker only: disk-cache loading must finish before the first gain decision.
     */
    public Session session(Track track, AudioSettings settings) {
        loaded.join();
        var lookup = lookup(track, settings);
        Reading reading = results.get(lookup.key);
        request(track, settings, true); // Queue missing metadata/analysis for the next session, without waiting for it.
        return new Session(reading, lookup);
    }

    public record Status(State state, Source source, double target, double appliedDb, boolean peakLimited,
                         boolean albumFallback) {
        public static final Status OFF = new Status(State.OFF, null, -18, 0, false, false);
    }

    public Status status(Track track, AudioSettings settings, double actualGain) {
        if (track == null || settings.neutral || !settings.normalization) return Status.OFF;
        var lookup = lookup(track, settings);
        var reading = results.get(lookup.key);
        State state = reading != null ? State.READY : pending.contains(lookup.attempt) ? State.PENDING
            : retryAfter.containsKey(lookup.attempt) ? State.FAILED : State.NO_MEASUREMENT;
        if (reading != null) return readingStatus(reading, settings, actualGain);
        return new Status(state, null, settings.targetLufs, AudioDynamics.db(actualGain), false, false);
    }

    private static Status readingStatus(Reading reading, AudioSettings settings, double actualGain) {
        Source source = reading.source == null ? Source.LEGACY : reading.source;
        boolean limited = Math.clamp(settings.targetLufs - reading.lufs, -24, 12) > -1 - reading.peakDb;
        return new Status(State.READY, source, settings.targetLufs, AudioDynamics.db(actualGain), limited,
            settings.gainMode == AudioSettings.Normalization.ALBUM && (source == Source.TRACK_TAGS || source == Source.ANALYSIS));
    }

    public LoudnessCache() {
        this(System::nanoTime);
    }

    LoudnessCache(LongSupplier clock) {
        this.clock = clock;
        Path data = ExternalAudio.data();
        cache = data == null ? null : data.resolve("loudness-cache.json");
        executor.execute(() -> {
            try {
                if (cache != null && Files.isRegularFile(cache) && Files.size(cache) <= MAX_CACHE_BYTES) {
                    try (var reader = new JsonReader(Files.newBufferedReader(cache))) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String key = reader.nextName();
                            if (results.size() >= READINGS) {
                                reader.skipValue();
                                continue;
                            }
                            Reading r = ConfigStore.JSON.fromJson(reader, Reading.class);
                            String path = pathOfKey(key);
                            if (r != null && path != null && Double.isFinite(r.lufs) && Double.isFinite(r.peakDb)) {
                                synchronized (persistence) {
                                    results.put(key, r);
                                    storedKeys.computeIfAbsent(path, ignored -> new HashSet<>()).add(key);
                                }
                            }
                        }
                        reader.endObject();
                        if (reader.peek() != JsonToken.END_DOCUMENT)
                            throw new IOException("Trailing loudness cache contents");
                    }
                } else if (cache != null && Files.isRegularFile(cache)) {
                    synchronized (persistence) {
                        preserveOriginal = true;
                    }
                    LOG.warn("Loudness cache {} exceeds the 64 MB read limit; file preserved", cache);
                }
            } catch (Exception e) {
                synchronized (persistence) {
                    results.clear();
                    storedKeys.clear();
                    preserveOriginal = true;
                }
                LOG.warn("Could not load loudness cache {}", cache, e);
            } finally {
                loaded.complete(null);
            }
        });
        writer.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() >= nextFlush) flush();
        }, 1, 1, TimeUnit.SECONDS);
    }

    public double gain(Track track, AudioSettings settings) {
        return request(track, settings, false);
    }

    /**
     * Best effort only: never wait for cache loading, a probe, or analysis on the caller.
     */
    public void prefetch(Track track, AudioSettings settings) {
        if (track != null && !settings.neutral && settings.normalization) request(track, settings, true);
    }

    private double request(Track track, AudioSettings settings, boolean priority) {
        return request(track, settings, priority, null);
    }

    private double request(Track track, AudioSettings settings, boolean priority, Batch owner) {
        if (owner != null && owner.cancelled) return 1;
        if (retryAfter.size() > 4096) {
            long now = clock.getAsLong();
            retryAfter.values().removeIf(at -> at <= now);
            if (retryAfter.size() > 4096) retryAfter.clear();
        }
        Lookup lookup = lookup(track, settings);
        String key = lookup.key;
        Reading r = results.get(key);
        String attempt = lookup.attempt;
        if (priority && pending.contains(attempt)) for (Runnable queued : queue)
            if (queued instanceof Inspection task && task.lookup.attempt.equals(attempt)) {
                promote(task);
                break;
            }
        if (r == null && !closed && !attempted.contains(attempt) && clock.getAsLong() >= retryAfter.getOrDefault(attempt, Long.MIN_VALUE) && pending.add(attempt)) {
            var task = new Inspection(lookup, owner);
            inspections.add(task);
            try {
                try {
                    executor.execute(task);
                } catch (RejectedExecutionException full) {
                    // A newly selected next song is more useful than an older queued inspection.
                    Runnable old = queue.peekLast();
                    if (!priority || executor.isShutdown() || !(old instanceof Inspection displaced) || !queue.remove(old))
                        throw full;
                    discard(displaced);
                    executor.execute(task);
                }
                if (priority) promote(task);
            } catch (RejectedExecutionException ignored) {
                discard(task);
            }
        }
        if (r == null) return 1;
        return gainFor(r, settings.targetLufs);
    }

    private void promote(Inspection task) {
        if (queue.remove(task) && (closed || executor.isShutdown() || !queue.offerFirst(task)))
            discard(task);
    }

    private void discard(Inspection task) {
        pending.remove(task.lookup.attempt);
        inspections.remove(task);
    }

    private final class Inspection implements Runnable {
        private final Lookup lookup;
        private final Batch owner;
        private final ProcessRunner.Token token = new ProcessRunner.Token();

        private Inspection(Lookup lookup, Batch owner) {
            this.lookup = lookup;
            this.owner = owner;
        }

        @Override
        public void run() {
            loaded.join();
            if (owner != null && owner.cancelled) token.cancel();
            try {
                inspect(lookup.track, lookup.key, lookup.analyze, lookup.mode, token);
            } finally {
                inspections.remove(this);
            }
        }
    }

    private static double gainFor(Reading r, double target) {
        double db = Math.clamp(target - r.lufs, -24, 12);
        db = Math.min(db, -1 - r.peakDb);
        return Math.pow(10, db / 20);
    }

    private Lookup lookup(Track track, AudioSettings settings) {
        Lookup found = recent;
        if (found != null && found.track == track && found.mode == settings.gainMode && found.analyze == settings.analyzeLoudness)
            return found;
        found = previous;
        if (found == null || found.track != track || found.mode != settings.gainMode || found.analyze != settings.analyzeLoudness) {
            String key = track.file().toAbsolutePath() + ":" + track.size() + ":" + track.modified() + ":" + settings.gainMode;
            found = new Lookup(track, settings.gainMode, settings.analyzeLoudness, key, key + ":" + settings.analyzeLoudness);
        }
        previous = recent;
        recent = found;
        return found;
    }

    private static String pathOfKey(String key) {
        int mode = key.lastIndexOf(':'), modified = mode < 0 ? -1 : key.lastIndexOf(':', mode - 1);
        int size = modified < 0 ? -1 : key.lastIndexOf(':', modified - 1);
        return size < 0 ? null : key.substring(0, size);
    }

    void remember(Track track, String key, Reading reading) {
        synchronized (persistence) {
            if (closed) return;
            if (results.size() >= READINGS && !results.containsKey(key)) {
                if (!capacityReported)
                    LOG.warn("Loudness cache reached its 65,536-reading limit; existing readings retained");
                capacityReported = true;
                return;
            }
            // Retire changed versions only for this observed file, never missing/offline libraries.
            String path = track.file().toAbsolutePath().toString();
            String prefix = path + ":";
            String version = prefix + track.size() + ":" + track.modified() + ":";
            var keys = storedKeys.computeIfAbsent(path, ignored -> new HashSet<>());
            keys.removeIf(old -> {
                if (old.startsWith(version)) return false;
                results.remove(old);
                return true;
            });
            keys.add(key);
            results.put(key, reading);
            dirty = true;
            revision++;
        }
    }

    private void flush() {
        synchronized (flushLock) {
            Map<String, Reading> snapshot;
            long captured;
            synchronized (persistence) {
                if (!dirty || cache == null || preserveOriginal) return;
                snapshot = Map.copyOf(results);
                captured = revision;
            }
            try {
                String json = STORAGE_JSON.toJson(snapshot);
                if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CACHE_BYTES)
                    throw new IOException("Loudness cache exceeds the 64 MB persistence limit; previous cache preserved");
                ConfigStore.atomicWrite(cache, json);
                nextFlush = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                synchronized (persistence) {
                    if (revision == captured) dirty = false;
                }
            } catch (Exception e) {
                nextFlush = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                LOG.warn("Could not save loudness cache {}; will retry", cache, e);
            }
        }
    }

    private void inspect(Track track, String key, boolean analyze, AudioSettings.Normalization mode, ProcessRunner.Token token) {
        String attempt = key + ":" + analyze;
        boolean completed = false;
        try {
            token.check();
            // Requests can be queued while the initial disk load is still in progress.
            if (results.containsKey(key)) {
                completed = true;
                return;
            }
            var info = ExternalAudio.probe(track.file(), true, token);
            var tags = new JsonObject();
            if (info.has("format") && info.getAsJsonObject("format").has("tags"))
                tags = info.getAsJsonObject("format").getAsJsonObject("tags");
            var streams = info.getAsJsonArray("streams");
            var stream = streams != null && !streams.isEmpty() ? streams.get(0).getAsJsonObject() : new JsonObject();
            if (stream.has("tags"))
                for (var e : stream.getAsJsonObject("tags").entrySet()) tags.add(e.getKey(), e.getValue());
            Map<String, String> values = new HashMap<>();
            for (var e : tags.entrySet()) values.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
            Reading result = tags(values, mode);
            if (result == null && analyze) {
                var r = new ProcessRunner().run(List.of(ExternalAudio.tool(false).toString(), "-nostdin", "-hide_banner", "-threads", "1", "-filter_threads", "1", "-protocol_whitelist", "file,pipe", "-i", track.file().toAbsolutePath().toString(), "-vn", "-af", "loudnorm=I=-18:TP=-1:LRA=11:print_format=json", "-f", "null", "-"), track.file().toAbsolutePath().getParent(), token, Duration.ofMinutes(30), 16_000);
                if (r.exit() != 0) throw new IOException("Loudness analysis failed: " + r.output());
                int at = r.output().lastIndexOf('{'), end = r.output().lastIndexOf('}');
                if (at >= 0 && end > at) {
                    var json = JsonParser.parseString(r.output().substring(at, end + 1)).getAsJsonObject();
                    result = new Reading(json.get("input_i").getAsDouble(), json.get("input_tp").getAsDouble(), Source.ANALYSIS);
                }
            }
            if (result != null && Double.isFinite(result.lufs) && Double.isFinite(result.peakDb) && !closed && !token.cancelled()) {
                remember(track, key, result);
            }
            completed = !token.cancelled();
        } catch (Exception e) {
            LOG.debug("Loudness unavailable for {}", track.file(), e);
        } finally {
            if (completed) {
                if (!results.containsKey(key)) attempted.add(attempt);
                retryAfter.remove(attempt);
            } else if (!closed && !token.cancelled())
                retryAfter.put(attempt, clock.getAsLong() + TimeUnit.SECONDS.toNanos(30));
            pending.remove(attempt);
        }
    }

    @Override
    public void close() {
        try {
            closeAsync().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("Loudness cache final write did not finish", e);
        }
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing;
        closed = true;
        cancelAnalysis();
        inspections.forEach(task -> task.token.cancel());
        executor.shutdownNow();
        inspections.clear();
        pending.clear();
        closing = CompletableFuture.runAsync(this::flush, writer);
        writer.shutdown();
        return closing;
    }

    private record Lookup(Track track, AudioSettings.Normalization mode, boolean analyze, String key, String attempt) {
    }

    static Reading tags(Map<String, String> values, AudioSettings.Normalization mode) {
        String[] prefixes = mode == AudioSettings.Normalization.ALBUM ? new String[]{"replaygain_album_", "replaygain_track_"} : new String[]{"replaygain_track_"};
        for (String prefix : prefixes)
            try {
                double db = Double.parseDouble(values.getOrDefault(prefix + "gain", "").replaceAll("(?i)\\s*dB", "").strip());
                double peak = Double.parseDouble(values.getOrDefault(prefix + "peak", ""));
                if (Double.isFinite(db) && Double.isFinite(peak) && peak > 0 && peak < 100)
                    return new Reading(-18 - db, 20 * Math.log10(peak), prefix.equals("replaygain_album_") ? Source.ALBUM_TAGS : Source.TRACK_TAGS);
            } catch (NumberFormatException ignored) {
            }
        return null;
    }

    record Reading(double lufs, double peakDb, Source source) {
        Reading(double lufs, double peakDb) {
            this(lufs, peakDb, Source.LEGACY);
        }
    }
}
