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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Optional background R128 analysis of unmodified files. Persistent keys include path, size and mtime.
 */
public final class LoudnessCache implements AutoCloseable {
    private final Map<String, Reading> results = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-loudness").factory());
    private final ProcessRunner.Token token = new ProcessRunner.Token();
    private final Path cache;
    private volatile boolean closed;

    public LoudnessCache() {
        this(System::nanoTime);
    }

    LoudnessCache(java.util.function.LongSupplier clock) {
        this.clock = clock;
        Path data = ExternalAudio.data();
        cache = data == null ? null : data.resolve("loudness-cache.json");
        executor.execute(() -> {
            try {
                if (cache != null && Files.isRegularFile(cache) && Files.size(cache) < 4_000_000) {
                    var json = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
                    for (var entry : json.entrySet()) {
                        Reading r = ConfigStore.JSON.fromJson(entry.getValue(), Reading.class);
                        if (r != null && Double.isFinite(r.lufs) && Double.isFinite(r.peakDb))
                            results.put(entry.getKey(), r);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    public double gain(Track track, AudioSettings settings) {
        String key = track.file().toAbsolutePath() + ":" + track.size() + ":" + track.modified() + ":" + settings.gainMode;
        Reading r = results.get(key);
        String attempt = key + ":" + settings.analyzeLoudness;
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
        double db = Math.clamp(settings.targetLufs - r.lufs, -24, 12);
        db = Math.min(db, -1 - r.peakDb);
        return Math.pow(10, db / 20);
    }

    private void inspect(Track track, String key, boolean analyze, AudioSettings.Normalization mode) {
        String attempt = key + ":" + analyze;
        boolean completed = false;
        try {
            var info = ExternalAudio.probe(track.file());
            var tags = new JsonObject();
            if (info.has("format") && info.getAsJsonObject("format").has("tags"))
                tags = info.getAsJsonObject("format").getAsJsonObject("tags");
            var stream = info.getAsJsonArray("streams").get(0).getAsJsonObject();
            if (stream.has("tags"))
                for (var e : stream.getAsJsonObject("tags").entrySet()) tags.add(e.getKey(), e.getValue());
            Map<String, String> values = new HashMap<>();
            for (var e : tags.entrySet()) values.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
            String prefix = mode == AudioSettings.Normalization.ALBUM && values.containsKey("replaygain_album_gain") && values.containsKey("replaygain_album_peak") ? "replaygain_album_" : "replaygain_track_";
            Reading result = null;
            if (values.containsKey(prefix + "gain") && values.containsKey(prefix + "peak")) {
                double db = Double.parseDouble(values.get(prefix + "gain").replaceAll("(?i)\\s*dB", "").strip());
                double peak = Double.parseDouble(values.get(prefix + "peak"));
                if (peak > 0) result = new Reading(-18 - db, 20 * Math.log10(peak));
            }
            if (result == null && analyze) {
                var r = new ProcessRunner().run(List.of(ExternalAudio.tool(false).toString(), "-nostdin", "-hide_banner", "-protocol_whitelist", "file,pipe", "-i", track.file().toAbsolutePath().toString(), "-vn", "-af", "loudnorm=I=-18:TP=-1:LRA=11:print_format=json", "-f", "null", "-"), track.file().toAbsolutePath().getParent(), token, Duration.ofMinutes(30), 16_000, s -> {
                });
                if (r.exit() != 0) throw new java.io.IOException("Loudness analysis failed: " + r.output());
                int at = r.output().lastIndexOf('{'), end = r.output().lastIndexOf('}');
                if (r.exit() == 0 && at >= 0 && end > at) {
                    var json = JsonParser.parseString(r.output().substring(at, end + 1)).getAsJsonObject();
                    result = new Reading(json.get("input_i").getAsDouble(), json.get("input_tp").getAsDouble());
                }
            }
            if (result != null && Double.isFinite(result.lufs) && Double.isFinite(result.peakDb) && !closed) {
                results.put(key, result);
                if (cache != null) ConfigStore.atomicWrite(cache, ConfigStore.JSON.toJson(results));
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
        closed = true;
        token.cancel();
        executor.shutdownNow();
    }

    private record Reading(double lufs, double peakDb) {
    }
}
