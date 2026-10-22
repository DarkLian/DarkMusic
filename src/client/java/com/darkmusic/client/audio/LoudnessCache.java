package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Optional background R128 analysis of unmodified files. Persistent keys include path, size and mtime.
 */
public final class LoudnessCache implements AutoCloseable {
    private final Map<String, Reading> results = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> storedKeys = new HashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("darkmusic-loudness").factory());
    private final ProcessRunner.Token token = new ProcessRunner.Token();
    private final Path cache;
    private static final long MAX_CACHE_BYTES = 64_000_000;
    private final Object persistence = new Object();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("darkmusic-loudness-cache").factory());
    private boolean dirty;
    private boolean preserveOriginal;
    private Lookup recent, previous;
    private volatile boolean closed;
    private CompletableFuture<Void> closing;
    private final CompletableFuture<Void> loaded = new CompletableFuture<>();

    public enum Source {TRACK_TAGS, ALBUM_TAGS, ANALYSIS, LEGACY}

    public enum State {OFF, PENDING, READY, NO_MEASUREMENT, FAILED, NEXT_SESSION}

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
        gain(track, settings); // Queue missing metadata/analysis for the next session, without waiting for it.
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
        boolean limited = reading != null && Math.clamp(settings.targetLufs - reading.lufs, -24, 12) > -1 - reading.peakDb;
        return new Status(State.READY, source, settings.targetLufs, AudioDynamics.db(actualGain), limited,
            settings.gainMode == AudioSettings.Normalization.ALBUM && (source == Source.TRACK_TAGS || source == Source.ANALYSIS));
    }

    public LoudnessCache() {
        this(System::nanoTime);
    }

    LoudnessCache(java.util.function.LongSupplier clock) {
        this.clock = clock;
        Path data = ExternalAudio.data();
        cache = data == null ? null : data.resolve("loudness-cache.json");
        executor.execute(() -> {
            try {
                if (cache != null && Files.isRegularFile(cache) && Files.size(cache) <= MAX_CACHE_BYTES) {
                    com.google.gson.JsonObject json;
                    try (var reader = Files.newBufferedReader(cache)) {
                        json = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                    synchronized (persistence) {
                        for (var entry : json.entrySet()) {
                            if (results.size() >= 65536) break;
                            Reading r = ConfigStore.JSON.fromJson(entry.getValue(), Reading.class);
                            String path = pathOfKey(entry.getKey());
                            if (r != null && path != null && Double.isFinite(r.lufs) && Double.isFinite(r.peakDb)) {
                                results.put(entry.getKey(), r);
                                storedKeys.computeIfAbsent(path, ignored -> new HashSet<>()).add(entry.getKey());
                            }
                        }
                    }
                } else if (cache != null && Files.isRegularFile(cache)) {
                    synchronized (persistence) {
                        preserveOriginal = true;
                    }
                    org.slf4j.LoggerFactory.getLogger("darkmusic-audio").warn("Loudness cache {} exceeds the 64 MB read limit; file preserved", cache);
                }
            } catch (Exception e) {
                synchronized (persistence) {
                    preserveOriginal = true;
                }
                org.slf4j.LoggerFactory.getLogger("darkmusic-audio").warn("Could not load loudness cache {}", cache, e);
            } finally {
                loaded.complete(null);
            }
        });
        writer.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.SECONDS);
    }

    public double gain(Track track, AudioSettings settings) {
        if (attempted.size() > 4096) attempted.clear();
        if (retryAfter.size() > 4096) retryAfter.clear();
        Lookup lookup = lookup(track, settings);
        String key = lookup.key;
        Reading r = results.get(key);
        String attempt = lookup.attempt;
        if (r == null && !closed && !attempted.contains(attempt) && clock.getAsLong() >= retryAfter.getOrDefault(attempt, Long.MIN_VALUE) && pending.add(attempt)) {
            boolean analyze = settings.analyzeLoudness;
            var mode = settings.gainMode;
            try {
                executor.execute(() -> inspect(track, key, analyze, mode));
            } catch (RejectedExecutionException ignored) {
                pending.remove(attempt);
            }
        }
        if (r == null) return 1;
        return gainFor(r, settings.targetLufs);
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
            if (results.size() >= 65536 && !results.containsKey(key)) return;
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
        }
    }

    private void flush() {
        synchronized (persistence) {
            if (!dirty || cache == null || preserveOriginal) return;
            try {
                String json = ConfigStore.JSON.toJson(results);
                if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CACHE_BYTES)
                    throw new java.io.IOException("Loudness cache exceeds the 64 MB persistence limit; previous cache preserved");
                ConfigStore.atomicWrite(cache, json);
                dirty = false;
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-audio").warn("Could not save loudness cache {}; will retry", cache, e);
            }
        }
    }

    private void inspect(Track track, String key, boolean analyze, AudioSettings.Normalization mode) {
        String attempt = key + ":" + analyze;
        boolean completed = false;
        try {
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
                var r = new ProcessRunner().run(List.of(ExternalAudio.tool(false).toString(), "-nostdin", "-hide_banner", "-protocol_whitelist", "file,pipe", "-i", track.file().toAbsolutePath().toString(), "-vn", "-af", "loudnorm=I=-18:TP=-1:LRA=11:print_format=json", "-f", "null", "-"), track.file().toAbsolutePath().getParent(), token, Duration.ofMinutes(30), 16_000, s -> {
                });
                if (r.exit() != 0) throw new java.io.IOException("Loudness analysis failed: " + r.output());
                int at = r.output().lastIndexOf('{'), end = r.output().lastIndexOf('}');
                if (r.exit() == 0 && at >= 0 && end > at) {
                    var json = JsonParser.parseString(r.output().substring(at, end + 1)).getAsJsonObject();
                    result = new Reading(json.get("input_i").getAsDouble(), json.get("input_tp").getAsDouble(), Source.ANALYSIS);
                }
            }
            if (result != null && Double.isFinite(result.lufs) && Double.isFinite(result.peakDb) && !closed) {
                remember(track, key, result);
            }
            completed = true;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-audio").debug("Loudness unavailable for {}", track.file(), e);
        } finally {
            if (completed) {
                attempted.add(attempt);
                retryAfter.remove(attempt);
            } else if (!closed)
                retryAfter.put(attempt, clock.getAsLong() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
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
            org.slf4j.LoggerFactory.getLogger("darkmusic-audio").warn("Loudness cache final write did not finish", e);
        }
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing;
        closed = true;
        token.cancel();
        executor.shutdownNow();
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
