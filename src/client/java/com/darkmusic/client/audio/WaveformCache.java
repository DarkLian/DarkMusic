package com.darkmusic.client.audio;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;

import javax.sound.sampled.AudioInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * One optional, coalesced background decoder. No network, DSP or render-thread I/O.
 */
public final class WaveformCache implements AutoCloseable {
    private record Entry(int version, String identity, double duration, float[] levels) {
    }

    private final Path directory;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), r -> {
        var t = new Thread(r, "darkmusic-waveform");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Map<String, TrackWaveform> memory = new LinkedHashMap<>(16, .75f, true);
    private volatile String wanted = "";

    private record Published(String identity, long generation, TrackWaveform waveform) {
    }

    private volatile Published current;
    private volatile AudioInputStream active;
    private volatile boolean closed;
    private volatile long generation;
    private Future<?> task;
    private final WaveformRetries retries = new WaveformRetries();

    public WaveformCache(Path data) {
        directory = data.resolve("waveforms");
    }

    private static String identity(Track track) {
        return track.id() + "|" + track.file().toAbsolutePath() + "|" + track.size() + "|" + track.modified();
    }

    /**
     * Called on the client thread; repeat requests do no filesystem work.
     */
    public void update(Track track, boolean enabled) {
        String key = enabled && track != null ? identity(track) : "";
        if (closed || key.isEmpty() && wanted.isEmpty() || key.equals(wanted) && (task != null && !task.isDone() || current != null && current.generation == generation && current.identity.equals(key) || !retries.ready(key, System.nanoTime())))
            return;
        cancel();
        wanted = key;
        var cached = key.isEmpty() ? null : memory.get(key);
        current = cached == null ? null : new Published(key, generation, cached);
        if (key.isEmpty() || cached != null) return;
        if (!retries.ready(key, System.nanoTime())) return;
        long request = generation;
        task = worker.submit(() -> {
            try {
                Path file = directory.resolve(hash(key) + ".json");
                TrackWaveform result = load(file, key);
                if (result == null) {
                    if (!matches(track)) throw new java.io.IOException("Waveform identity changed");
                    try (var pcm = PcmDecoder.open(track.file())) {
                        active = pcm;
                        result = TrackWaveform.analyze(pcm, () -> closed || request != generation || !key.equals(wanted));
                    } finally {
                        active = null;
                    }
                    if (closed || request != generation || !key.equals(wanted)) return;
                    if (!matches(track)) throw new java.io.IOException("Waveform identity changed");
                    try {
                        store(file, key, result);
                    } catch (Exception failure) {
                        org.slf4j.LoggerFactory.getLogger("darkmusic-waveform").debug("Waveform cache could not be saved", failure);
                    }
                } else if (!matches(track)) throw new java.io.IOException("Waveform identity changed");
                if (!closed && request == generation && key.equals(wanted)) {
                    retries.succeeded(key);
                    current = new Published(key, request, result);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                if (request == generation && key.equals(wanted))
                    retries.failed(key, System.nanoTime(), failure instanceof TrackWaveform.AnalysisLimit || failure instanceof javax.sound.sampled.UnsupportedAudioFileException);
                org.slf4j.LoggerFactory.getLogger("darkmusic-waveform").debug("Waveform unavailable; using normal progress bar", failure);
            }
        });
    }

    public TrackWaveform get(Track track) {
        if (track == null || !identity(track).equals(wanted)) return null;
        var result = current;
        if (result == null || result.generation != generation || !result.identity.equals(wanted)) return null;
        memory.put(wanted, result.waveform);
        while (memory.size() > 16) memory.remove(memory.keySet().iterator().next());
        return result.waveform;
    }

    private static boolean matches(Track track) throws Exception {
        var attrs = Files.readAttributes(track.file(), java.nio.file.attribute.BasicFileAttributes.class);
        return attrs.isRegularFile() && attrs.size() == track.size() && attrs.lastModifiedTime().toMillis() == track.modified();
    }

    private static String hash(String key) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private TrackWaveform load(Path file, String key) {
        try {
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 32768)
                return null;
            var entry = ConfigStore.JSON.fromJson(Files.readString(file), Entry.class);
            return entry != null && entry.version == 2 && key.equals(entry.identity) ? new TrackWaveform(entry.levels, entry.duration) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void store(Path file, String key, TrackWaveform data) throws Exception {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) return;
        float[] values = new float[TrackWaveform.BINS];
        for (int i = 0; i < values.length; i++) values[i] = data.level(i);
        ConfigStore.atomicWrite(file, ConfigStore.JSON.toJson(new Entry(2, key, data.duration(), values)));
        // At most 256 small entries, including stale identities; only touched after analysis.
        try (var files = Files.list(directory)) {
            var entries = files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.json"))
                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
            for (int i = 0; i < entries.size() - 256; i++) Files.deleteIfExists(entries.get(i));
        }
    }

    private void cancel() {
        generation++;
        wanted = "";
        if (task != null) task.cancel(true);
        var stream = active;
        if (stream != null) ExternalAudio.abort(stream);
        worker.getQueue().clear();

    }

    @Override
    public void close() {
        closed = true;
        cancel();
        current = null;
        memory.clear();
        worker.shutdownNow();
    }
}
