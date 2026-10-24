package com.darkmusic.client.cleanup;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.download.ProcessRunner;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * One bounded read-only analysis worker; immutable, throttled snapshots for rendering.
 */
public final class DuplicateScanner implements AutoCloseable {
    public enum Stage {IDLE, READING, MATCHING, COMPLETE, CANCELLED, FAILED}

    public record Snapshot(long revision, Stage stage, int done, int total, int unavailable, int audioFailed,
                           boolean limited, boolean cacheFailed, long comparisons, List<DuplicateResult> pairs) {
        public boolean running() {
            return stage == Stage.READING || stage == Stage.MATCHING;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-cleanup").factory());
    private final DuplicateCache cache;
    private final Path scratch;
    private final FingerprintService fingerprints;
    private ProcessRunner.Token token;
    private boolean closed, loaded, active;
    private final Set<String> removed = new HashSet<>();
    private final Set<String> dismissed = new HashSet<>();
    private volatile Snapshot snapshot = new Snapshot(0, Stage.IDLE, 0, 0, 0, 0, false, false, 0, List.of());

    public DuplicateScanner(Path data) {
        this(data, new FingerprintService());
    }

    public DuplicateScanner(Path data, FingerprintService fingerprints) {
        cache = new DuplicateCache(data);
        scratch = data.resolve("cleanup-work");
        this.fingerprints = fingerprints;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean start(List<Track> tracks, DuplicatePolicy policy, boolean audio) {
        if (closed || active) return false;
        active = true;
        removed.clear();
        dismissed.clear();
        token = new ProcessRunner.Token();
        var request = token;
        publish(Stage.READING, 0, tracks.size(), 0, 0, tracks.size() > DuplicatePolicy.MAX_TRACKS, false, 0, List.of());
        worker.execute(() -> scan(tracks, policy, audio, request));
        return true;
    }

    public synchronized void cancel() {
        if (token != null) token.cancel();
    }

    public synchronized void deleted(Set<String> ids) {
        removed.addAll(ids);
        if (!closed) worker.execute(() -> {
            cache.remove(ids);
            try {
                cache.flush();
            } catch (java.io.IOException failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Could not prune duplicate cache", failure);
            }
        });
        var s = snapshot;
        publish(s.stage, s.done, s.total, s.unavailable, s.audioFailed, s.limited, s.cacheFailed, s.comparisons, s.pairs);
    }

    public synchronized void dismiss(String id) {
        dismissed.add(id);
        var s = snapshot;
        publish(s.stage, s.done, s.total, s.unavailable, s.audioFailed, s.limited, s.cacheFailed, s.comparisons, s.pairs);
    }

    public synchronized void prune(List<Track> tracks, Set<String> registered, Set<String> complete) {
        if (closed) return;
        worker.execute(() -> {
            if (!loaded) {
                cache.load();
                loaded = true;
            }
            var present = new HashSet<String>();
            for (Track track : tracks) present.add(track.id());
            cache.prune(present, registered, complete);
            try {
                cache.flush();
            } catch (java.io.IOException failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Could not prune duplicate cache", failure);
            }
        });
    }

    private synchronized void publish(Stage stage, int done, int total, int unavailable, int audioFailed, boolean limited, boolean cacheFailed, long comparisons, List<DuplicateResult> pairs) {
        snapshot = new Snapshot(snapshot.revision + 1, stage, done, total, unavailable, audioFailed, limited, cacheFailed, comparisons,
            removed.isEmpty() && dismissed.isEmpty() ? List.copyOf(pairs) : pairs.stream().filter(r -> !dismissed.contains(r.id()) && !removed.contains(r.a().id()) && !removed.contains(r.b().id())).toList());
    }

    private void scan(List<Track> tracks, DuplicatePolicy policy, boolean audio, ProcessRunner.Token request) {
        int total = Math.min(tracks.size(), DuplicatePolicy.MAX_TRACKS);
        int done = 0, unavailable = 0, audioFailed = 0;
        boolean limited = tracks.size() > DuplicatePolicy.MAX_TRACKS;
        var analyzed = new ArrayList<DuplicateMatcher.Entry>();
        try {
            if (!loaded) {
                cache.load();
                loaded = true;
            }
            var sizes = new HashMap<Long, Integer>();
            for (Track t : tracks) sizes.merge(t.size(), 1, Integer::sum);
            var sources = new HashMap<Path, Map<String, String>>();
            var archives = new HashMap<Path, Map<String, List<String>>>();
            long nextUpdate = 0, nextFlush = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            for (Track t : tracks.stream().limit(DuplicatePolicy.MAX_TRACKS).toList()) {
                request.check();
                try {
                    var before = attributes(t);
                    String stamp = stamp(t, before);
                    var saved = cache.get(t.id(), stamp);
                    String hash = saved == null ? "" : saved.hash();
                    String source = sources.computeIfAbsent(t.file().getParent(), SourceLinks::readDirectory).getOrDefault(t.file().getFileName().toString(), saved == null ? "" : saved.source());
                    if (DuplicateMatcher.sourceId(source).isEmpty()) {
                        for (String archiveKey : archives.computeIfAbsent(t.file().getParent(), LibrarySourceIndex::archive).getOrDefault(t.file().getFileName().toString(), List.of())) {
                            String key = archiveKey.endsWith(":original") ? archiveKey.substring(0, archiveKey.length() - 9) : archiveKey;
                            int split = key.indexOf(':');
                            if (split > 0) {
                                source = "darkmusic-source:" + LibrarySourceIndex.identity(key.substring(0, split), key.substring(split + 1), source);
                                break;
                            }
                        }
                    }
                    int[] fingerprint = saved == null ? new int[0] : saved.values();
                    if (hash.isEmpty() && sizes.get(t.size()) > 1) hash = hash(t.file(), request);
                    request.check();
                    if (!stamp.equals(stamp(t, attributes(t))))
                        throw new java.io.IOException("File changed during analysis");
                    cache.put(t.id(), stamp, hash, source, fingerprint);
                    analyzed.add(new DuplicateMatcher.Entry(t, stamp, hash, source, audio ? fingerprint : new int[0]));
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw cancelled;
                } catch (Exception failure) {
                    unavailable++;
                    org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").debug("Cannot inspect {}", t.file(), failure);
                }
                done++;
                if (System.nanoTime() >= nextUpdate) {
                    publish(Stage.READING, done, total, unavailable, audioFailed, limited, false, 0, List.of());
                    nextUpdate = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                }
                if (System.nanoTime() >= nextFlush) {
                    try {
                        cache.flush();
                    } catch (Exception failure) {
                        org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Duplicate cache checkpoint failed", failure);
                    }
                    nextFlush = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                }
            }
            int finalDone = done, finalUnavailable = unavailable, finalAudio = audioFailed;
            boolean finalLimited = limited;
            publish(Stage.MATCHING, done, total, unavailable, audioFailed, limited, false, 0, List.of());
            DuplicateMatcher.Session incremental = audio ? new DuplicateMatcher.Session(policy, request) : null;
            DuplicateMatcher.Matches result;
            if (incremental != null) {
                for (int i = 0; i < analyzed.size(); i++) {
                    incremental.add(analyzed.get(i));
                    if (i % 1000 == 0)
                        publish(Stage.MATCHING, done, total, unavailable, audioFailed, limited, false, 0, incremental.snapshot().pairs());
                }
                result = incremental.snapshot();
            } else
                result = new DuplicateMatcher().match(analyzed, policy, request, pairs -> publish(Stage.MATCHING, finalDone, total, finalUnavailable, finalAudio, finalLimited, false, 0, pairs));
            if (audio) {
                int processed = 0;
                long nextPartial = System.nanoTime() + TimeUnit.SECONDS.toNanos(60), nextProgress = 0;
                for (int i = 0; i < analyzed.size(); i++) {
                    request.check();
                    var e = analyzed.get(i);
                    processed++;
                    if (System.nanoTime() >= nextProgress) {
                        publish(Stage.READING, processed, analyzed.size(), unavailable, audioFailed, limited || result.limited(), false, result.comparisons(), result.pairs());
                        nextProgress = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                    }
                    if (e.fingerprint().length == 0) try {
                        if (!e.stamp().equals(stamp(e.track(), attributes(e.track()))))
                            throw new java.io.IOException("File changed");
                        int[] fingerprint = fingerprints.extract(e.track().file(), scratch, request);
                        String source = e.source().isEmpty() ? DuplicateMediaInfo.read(e.track(), "").source() : e.source();
                        if (!e.stamp().equals(stamp(e.track(), attributes(e.track()))))
                            throw new java.io.IOException("File changed");
                        analyzed.set(i, new DuplicateMatcher.Entry(e.track(), e.stamp(), e.hash(), source, fingerprint));
                        incremental.update(i, analyzed.get(i));
                        cache.put(e.track().id(), e.stamp(), e.hash(), source, fingerprint);
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    } catch (Exception failure) {
                        audioFailed++;
                        org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").debug("Cannot fingerprint {}", e.track().file(), failure);
                    }
                    if (System.nanoTime() >= nextPartial) {
                        result = incremental.snapshot();
                        nextPartial = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                        try {
                            cache.flush();
                        } catch (Exception ignored) {
                        }
                    }
                }
                result = new DuplicateMatcher().match(analyzed, policy, request, pairs -> {
                });
            }
            publish(Stage.COMPLETE, done, total, unavailable, audioFailed, limited || result.limited(), false, result.comparisons(), result.pairs());
        } catch (java.util.concurrent.CancellationException cancelled) {
            var s = snapshot;
            publish(Stage.CANCELLED, done, total, unavailable, audioFailed, limited || s.limited, false, s.comparisons, s.pairs);
        } catch (Throwable failure) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Duplicate scan failed", failure);
            publish(Stage.FAILED, done, total, unavailable, audioFailed, limited, false, 0, snapshot.pairs);
        } finally {
            try {
                cache.flush();
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-cleanup").warn("Could not persist duplicate analysis", failure);
                var s = snapshot;
                publish(s.stage, s.done, s.total, s.unavailable, s.audioFailed, s.limited, true, s.comparisons, s.pairs);
            }
            synchronized (this) {
                active = false;
            }
        }
    }

    public static BasicFileAttributes attributes(Track track) throws Exception {
        var a = Files.readAttributes(track.file(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.size() != track.size() || a.lastModifiedTime().toMillis() != track.modified())
            throw new java.io.IOException("Changed or unavailable file");
        return a;
    }

    public static String stamp(Track track, BasicFileAttributes a) {
        return digest(track.id() + "\n" + track.file().toAbsolutePath().normalize() + "\n" + a.size() + "\n" + a.lastModifiedTime() + "\n" + Objects.toString(a.fileKey(), ""));
    }

    public static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hash(Path file, ProcessRunner.Token token) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (var input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                token.check();
                hash.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancel();
        worker.shutdown();
    }

    public CompletableFuture<Void> settled() {
        return CompletableFuture.runAsync(() -> {
        }, worker);
    }
}
