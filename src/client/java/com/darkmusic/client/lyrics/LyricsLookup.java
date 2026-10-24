package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * User choices and bounded online work. Rendering never performs I/O.
 */
public final class LyricsLookup implements AutoCloseable {
    public enum Status {IDLE, SEARCHING, READY, NOT_FOUND, AMBIGUOUS, NETWORK, RATE_LIMITED, PROVIDER, SOURCE, TOOLS, INVALID, REJECTED, CANCELLED, DISABLED, SAVED}

    public record Snapshot(Status status, List<LyricsProvider.Candidate> candidates) {
        public Snapshot {
            candidates = List.copyOf(candidates);
        }
    }

    public record Choice(LyricsDocument selected, int offset, String language, boolean pinned) {
    }

    @FunctionalInterface
    public interface Captions {
        LyricsDocument fetch(Track track, String preferences, boolean generated, ProcessRunner.Token token) throws Exception;
    }

    private record Saved(int schema, LyricsDocument selected, int offset, String language, long size, long modified,
                         boolean pinned, boolean reviewRequired, String rootId, String trackId, String encoding) {
    }

    private record Cached(int schema, long expires, List<LyricsProvider.Candidate> candidates) {
    }

    private final Path directory;
    private final LyricsProvider provider;
    private final LinkedBlockingDeque<Runnable> networkQueue = new LinkedBlockingDeque<>(16);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        networkQueue, Thread.ofPlatform().daemon().name("darkmusic-lyric-lookup").factory());
    private final ThreadPoolExecutor storage = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("darkmusic-lyric-storage").factory());
    private final ThreadPoolExecutor captionWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("darkmusic-lyric-captions").factory());
    private final Map<String, Integer> liveOffsets = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> offsetWrites = new HashMap<>();
    private final Map<String, Choice> choices = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Boolean> foreground = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    private record Known(long size, long modified, Status status, LyricsInfo available) {
        boolean matches(Track track) {
            return size == track.size() && modified == track.modified();
        }
    }

    private final Map<String, Known> known = new ConcurrentHashMap<>();
    private static final Map<Status, Snapshot> EMPTY_SNAPSHOTS = java.util.Arrays.stream(Status.values())
        .collect(java.util.stream.Collectors.toUnmodifiableMap(s -> s, s -> new Snapshot(s, List.of())));
    private final Map<String, ProcessRunner.Token> latestOperations = new ConcurrentHashMap<>();
    private final Map<String, String> activeQueries = new ConcurrentHashMap<>();
    private final Map<String, ProcessRunner.Token> pending = new ConcurrentHashMap<>();
    private final Map<String, ProcessRunner.Token> operationOwners = new ConcurrentHashMap<>();
    private final Set<String> loaded = ConcurrentHashMap.newKeySet();
    private final Map<String, Track> loadedTracks = new ConcurrentHashMap<>();
    private final Map<String, LyricsDocument> reviewSelections = new ConcurrentHashMap<>();
    private final Map<String, String> encodings = new ConcurrentHashMap<>();
    private final Object loadLock = new Object();
    private final Map<String, Long> loadRetryAfter = new LinkedHashMap<>();

    private record Attempt(Track track, int count, long retryAt, LyricsProvider.Query query,
                           ProcessRunner.Token owner) {
        Attempt(Track track, int count, long retryAt, LyricsProvider.Query query) {
            this(track, count, retryAt, query, null);
        }
    }

    private final Map<String, Attempt> automatic = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    private volatile long retryNotBefore;
    private volatile long cacheRevision;

    public long requests() {
        return provider.requestCount();
    }

    public long cacheRevision() {
        return cacheRevision;
    }

    private final LinkedHashMap<String, Cached> results = new LinkedHashMap<>(16, .75f, true);
    private long nextCacheTrim, bytesSinceCacheTrim;
    private volatile boolean enabled = true, online, closed;
    private volatile Captions captions;
    private volatile Captions manualCaptions;
    private volatile String captionPreferences = "auto";
    private volatile boolean generatedCaptions;
    private static final Snapshot IDLE = new Snapshot(Status.IDLE, List.of());
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LyricsLookup.class);

    public LyricsLookup(Path directory) {
        this(directory, new LrclibProvider());
    }

    public LyricsLookup(Path directory, LyricsProvider provider) {
        this(directory, provider, System::nanoTime);
    }

    LyricsLookup(Path directory, LyricsProvider provider, java.util.function.LongSupplier clock) {
        this.directory = directory;
        this.provider = provider;
        this.clock = clock;
        worker.prestartCoreThread();
    }

    public void captions(Captions captions) {
        this.captions = captions;
        this.manualCaptions = captions;
    }

    public void captions(Captions automatic, Captions manual) {
        this.captions = automatic;
        this.manualCaptions = manual;
    }

    public void captionPreferences(String preferences, boolean generated) {
        captionPreferences = preferences;
        generatedCaptions = generated;
    }

    public void configure(boolean enabled, boolean online) {
        boolean activated = enabled && online && (!this.enabled || !this.online);
        this.enabled = enabled;
        this.online = online;
        if (!enabled || !online) latestOperations.values().forEach(ProcessRunner.Token::cancel);
        else if (activated) automatic.clear();
    }

    public Choice choice(Track track) {
        Choice choice = track == null ? new Choice(null, 0, "auto", false) : choices.getOrDefault(track.id(), new Choice(null, 0, "auto", false));
        Integer offset = track == null ? null : liveOffsets.get(track.id());
        return offset == null ? choice : new Choice(choice.selected, offset, choice.language, choice.pinned);
    }

    public synchronized void retain(Track track) {
        if (track == null) return;
        foreground.put(track.id(), true);
        while (foreground.size() > 16) foreground.remove(foreground.keySet().iterator().next());
    }

    private synchronized boolean retained(String id) {
        return foreground.containsKey(id);
    }

    private synchronized void trimChoices(String keep) {
        if (directory == null || choices.size() <= 128) return;
        for (String id : choices.keySet()) {
            if (choices.size() <= 128) break;
            if (id.equals(keep) || foreground.containsKey(id) || pending.containsKey(id) || liveOffsets.containsKey(id))
                continue;
            choices.remove(id);
            loaded.remove(id);
            loadedTracks.remove(id);
            reviewSelections.remove(id);
            encodings.remove(id);
        }
    }

    public Snapshot snapshot(Track track) {
        if (track == null) return IDLE;
        var snapshot = snapshots.get(track.id());
        if (snapshot != null) return snapshot;
        Known prior = known.get(track.id());
        return prior != null && prior.matches(track) ? EMPTY_SNAPSHOTS.get(prior.status) : IDLE;
    }

    public LyricsInfo knownInfo(Track track) {
        Known prior = track == null ? null : known.get(track.id());
        return prior != null && prior.matches(track) ? prior.available : null;
    }

    private void publish(Track track, Snapshot snapshot) {
        known.compute(track.id(), (id, prior) -> new Known(track.size(), track.modified(), snapshot.status,
            prior != null && prior.matches(track) ? prior.available : null));
        snapshots.put(track.id(), snapshot);
    }

    private void rememberChoice(Track track, LyricsDocument document) {
        LyricsInfo available = document == null || !document.available() ? null : document.instrumental() ? LyricsInfo.UNAVAILABLE
            : document.synchronizedLyrics() ? LyricsInfo.AVAILABLE : LyricsInfo.PLAIN;
        known.compute(track.id(), (id, prior) -> new Known(track.size(), track.modified(), prior != null && prior.matches(track) ? prior.status : Status.IDLE, available));
    }

    /**
     * Called by local inspection's worker, not from a screen.
     */
    public void load(Track track) {
        synchronized (loadLock) {
            if (closed || clock.getAsLong() < loadRetryAfter.getOrDefault(track.id(), Long.MIN_VALUE)
                || loaded.contains(track.id()) && track.equals(loadedTracks.get(track.id())))
                return;
            loaded.add(track.id());
            loadedTracks.put(track.id(), track);
            if (directory == null) return;
            try {
                Path path = preference(track);
                try {
                    var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isRegularFile()) throw new java.io.IOException("Invalid saved lyric preferences");
                } catch (NoSuchFileException missing) {
                    loadRetryAfter.remove(track.id());
                    return;
                }
                Saved data = read(path, Saved.class);
                if (data == null || data.schema != 1) throw new java.io.IOException("Invalid saved lyric preferences");
                loadRetryAfter.remove(track.id());
                encodings.put(track.id(), validEncoding(data.encoding));
                LyricsDocument document = data.selected == null ? null : validated(data.selected);
                boolean changed = data.reviewRequired || data.size != track.size() || data.modified != track.modified();
                if (changed && document != null) {
                    reviewSelections.put(track.id(), document);
                    publish(track, new Snapshot(Status.AMBIGUOUS, List.of(new LyricsProvider.Candidate(document, false, "lyrics.match.file_changed"))));
                    document = null;
                }
                choices.put(track.id(), new Choice(document, Math.clamp(data.offset, -30000, 30000), language(data.language), data.pinned));
                rememberChoice(track, document);
                trimChoices(track.id());
            } catch (Exception failure) {
                loaded.remove(track.id());
                loadRetryAfter.put(track.id(), clock.getAsLong() + TimeUnit.SECONDS.toNanos(5));
                if (loadRetryAfter.size() > 256) loadRetryAfter.remove(loadRetryAfter.keySet().iterator().next());
                LOG.warn("Could not read saved lyric choice; retry available in five seconds", failure);
            }
        }
    }

    private static LyricsDocument validated(LyricsDocument d) {
        return LyricsDocument.of(d.provider(), d.recordId(), d.title(), d.artist(), d.album(), d.duration(), d.language(), d.variant(), d.generated(), d.instrumental(), d.text());
    }

    private static String language(String value) {
        if (value == null || value.strip().equalsIgnoreCase("auto")) return "auto";
        String result = com.darkmusic.client.download.LyricsLanguages.normalize(value);
        if (result.contains(","))
            throw new IllegalArgumentException(com.darkmusic.client.text.Text.get("lyrics.darkmusic.language_invalid"));
        return LyricsDocument.normalizeLanguage(result);
    }

    public CompletableFuture<Void> select(Track track, LyricsDocument selected) {
        cancel(track);
        return update(track, () -> {
            reviewSelections.remove(track.id());
            return new Choice(selected == null ? null : validated(selected), choice(track).offset, choice(track).language, selected != null);
        });
    }

    public synchronized CompletableFuture<Void> offset(Track track, int offset) {
        liveOffsets.put(track.id(), Math.clamp(offset, -30000, 30000));
        var existing = offsetWrites.get(track.id());
        if (existing != null) return existing;
        var completion = new CompletableFuture<Void>();
        offsetWrites.put(track.id(), completion);
        var task = submit(() -> {
            try {
                load(track);
                for (; ; ) {
                    Choice next = choice(track);
                    if (directory != null)
                        ConfigStore.atomicWrite(preference(track), ConfigStore.JSON.toJson(saved(track, next)));
                    choices.put(track.id(), next);
                    synchronized (this) {
                        if (choice(track).offset == next.offset) {
                            liveOffsets.remove(track.id());
                            offsetWrites.remove(track.id());
                            break;
                        }
                    }
                }
                completion.complete(null);
            } catch (Exception failure) {
                synchronized (this) {
                    offsetWrites.remove(track.id());
                }
                completion.completeExceptionally(failure);
            }
        });
        task.exceptionally(failure -> {
            synchronized (this) {
                offsetWrites.remove(track.id());
            }
            completion.completeExceptionally(failure);
            return null;
        });
        return completion;
    }

    public CompletableFuture<Void> language(Track track, String value) {
        String normalized = language(value);
        return update(track, () -> new Choice(choice(track).selected, choice(track).offset, normalized, choice(track).pinned));
    }

    public String encoding(Track track) {
        return track == null ? "Auto" : encodings.getOrDefault(track.id(), "Auto");
    }

    private static String validEncoding(String value) {
        return List.of("Big5", "GB18030", "windows-31j", "EUC-KR").contains(value) ? value : "Auto";
    }

    public CompletableFuture<Void> encoding(Track track, String value) {
        return update(track, () -> {
            encodings.put(track.id(), validEncoding(value));
            return choice(track);
        });
    }

    private CompletableFuture<Void> update(Track track, Supplier<Choice> change) {
        return submit(() -> {
            load(track);
            LyricsDocument previousReview = reviewSelections.get(track.id());
            String previousEncoding = encoding(track);
            try {
                Choice next = change.get();
                if (directory != null)
                    ConfigStore.atomicWrite(preference(track), ConfigStore.JSON.toJson(saved(track, next)));
                choices.put(track.id(), next);
                rememberChoice(track, next.selected);
            } catch (Exception failure) {
                if (previousReview != null) reviewSelections.put(track.id(), previousReview);
                else reviewSelections.remove(track.id());
                encodings.put(track.id(), previousEncoding);
                throw failure;
            }
        });
    }

    public void renamed(Track before, Track after) {
        cancel(before);
        submit(() -> {
            load(before);
            Choice old = choices.get(before.id());
            if (old == null) return;
            encodings.put(after.id(), encoding(before));
            LyricsDocument review = reviewSelections.get(before.id());
            if (review != null) reviewSelections.put(after.id(), review);
            if (directory != null)
                ConfigStore.atomicWrite(preference(after), ConfigStore.JSON.toJson(saved(after, old)));
            choices.put(after.id(), old);
            rememberChoice(after, old.selected);
            loaded.add(after.id());
            loadedTracks.put(after.id(), after);
            if (!before.id().equals(after.id())) deleted(Set.of(before.id()));
        });
    }

    public void downloaded(Path file, Map<Path, String> roots) {
        downloaded(file, roots, "auto", false);
    }

    public void downloaded(Path file, Map<Path, String> roots, String preferences, boolean generated) {
        if (!enabled || !online || closed) return;
        submitNetwork(() -> {
            Path normalized = file.toAbsolutePath().normalize();
            var root = roots.entrySet().stream().filter(e -> normalized.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().getNameCount())).orElse(null);
            if (root == null || Files.exists(com.darkmusic.client.download.DownloadLyrics.sidecar(normalized))) return;
            var attributes = Files.readAttributes(normalized, java.nio.file.attribute.BasicFileAttributes.class);
            var track = new com.darkmusic.client.library.MetadataReader().read(root.getValue(), root.getKey().relativize(normalized).toString().replace('\\', '/'), normalized, attributes.size(), attributes.lastModifiedTime().toMillis());
            // Existing embedded text remains authoritative even if it has no timing.
            try {
                var tags = org.jaudiotagger.audio.AudioFileIO.read(normalized.toFile()).getTag();
                if (tags != null && !tags.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS).isBlank()) return;
            } catch (Exception ignored) {
            }
            load(track);
            if (choice(track).selected != null) return;
            automatic.put(track.id(), new Attempt(track, 1, Long.MAX_VALUE, null));
            start(track, token -> automaticDiscovery(track, preferences, generated, token), true, true);
        }).exceptionally(failure -> {
            LOG.debug("Optional post-download lookup could not be queued", failure);
            return null;
        });
    }

    public void automatic(Track track, boolean localAvailable) {
        automatic(track, localAvailable, null);
    }

    void automatic(Track track, boolean localAvailable, ProcessRunner.Token owner) {
        if (owner != null && owner.cancelled()) return;
        if (!enabled || !online || track == null || localAvailable || choice(track).selected != null || reviewSelections.containsKey(track.id()) || !loaded.contains(track.id()))
            return;
        if (pending.containsKey(track.id())) return;
        if (clock.getAsLong() < retryNotBefore) return;
        Attempt previous = automatic.get(track.id());
        if (owner == null && previous != null) owner = previous.owner;
        if (owner != null && owner.cancelled()) return;
        if (previous != null && previous.track.equals(track) && previous.query != null) return;
        if (previous != null && previous.track.equals(track) && clock.getAsLong() < previous.retryAt) return;
        int count = previous != null && previous.track.equals(track) ? previous.count + 1 : 1;
        automatic.put(track.id(), new Attempt(track, count, Long.MAX_VALUE, null, owner));
        if (automatic.size() > 512)
            automatic.entrySet().stream().filter(e -> !e.getKey().equals(track.id()) && e.getValue().retryAt == Long.MAX_VALUE && !pending.containsKey(e.getKey()))
                .map(Map.Entry::getKey).findFirst().ifPresent(automatic::remove);
        start(track, token -> automaticDiscovery(track, captionPreferences, generatedCaptions, token), true, true, worker, owner, null);
    }

    /**
     * Client ticks drive retry deadlines independently of screen selection.
     */
    public void tickRetries() {
        if (!enabled || !online || closed || !pending.isEmpty()) return;
        long now = clock.getAsLong();
        if (now < retryNotBefore) return;
        for (var attempt : automatic.values())
            if (attempt.retryAt != Long.MAX_VALUE && now >= attempt.retryAt) {
                if (attempt.owner != null && attempt.owner.cancelled()) {
                    automatic.remove(attempt.track.id(), attempt);
                    continue;
                }
                if (attempt.query == null) automatic(attempt.track, false, attempt.owner);
                else {
                    automatic.put(attempt.track.id(), new Attempt(attempt.track, attempt.count + 1, Long.MAX_VALUE, attempt.query));
                    start(attempt.track, token -> discover(attempt.track, attempt.query, true, false, token), true, false);
                }
                return;
            }
    }

    public boolean busy() {
        return !pending.isEmpty() || clock.getAsLong() < retryNotBefore;
    }

    public long cooldownSeconds() {
        long remaining = retryNotBefore - clock.getAsLong();
        return remaining <= 0 ? 0 : TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1;
    }

    synchronized void cancelOwner(ProcessRunner.Token owner) {
        if (owner == null) return;
        owner.cancel();
        automatic.entrySet().removeIf(e -> e.getValue().owner == owner);
        for (var entry : operationOwners.entrySet()) {
            if (entry.getValue() != owner || !operationOwners.remove(entry.getKey(), owner)) continue;
            var token = latestOperations.remove(entry.getKey());
            if (token != null) token.cancel();
            if (token != null) pending.remove(entry.getKey(), token);
            var track = loadedTracks.get(entry.getKey());
            if (track != null) publish(track, new Snapshot(Status.CANCELLED, List.of()));
        }
    }

    boolean ownerOutstanding(ProcessRunner.Token owner) {
        return owner != null && !owner.cancelled() && automatic.values().stream()
            .anyMatch(a -> a.owner == owner && (a.retryAt != Long.MAX_VALUE || pending.containsKey(a.track.id())));
    }

    long ownerRetrySeconds(ProcessRunner.Token owner) {
        return automatic.values().stream().filter(a -> a.owner == owner).mapToLong(a -> retrySeconds(a.track))
            .filter(delay -> delay > 0).min().orElse(0);
    }

    public long retrySeconds(Track track) {
        Attempt attempt = track == null ? null : automatic.get(track.id());
        if (!enabled || !online || attempt == null || attempt.retryAt == Long.MAX_VALUE) return 0;
        long delay = Math.max(attempt.retryAt, retryNotBefore) - clock.getAsLong();
        return delay <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toSeconds(delay) + 1);
    }

    private List<LyricsProvider.Candidate> automaticDiscovery(Track track, String preferences, boolean generated, ProcessRunner.Token token) throws Exception {
        List<LyricsProvider.Candidate> found;
        LrclibProvider.Failure providerFailure = null;
        try {
            found = discover(track, LyricsProvider.Query.of(track), false, true, token);
        } catch (LrclibProvider.Failure failure) {
            if (captions == null) throw failure;
            providerFailure = failure;
            found = List.of();
        }
        token.check();
        if (reliableMatch(found) != null || captions == null) return found;
        var originalFailure = providerFailure;
        var alternatives = found;
        // Caption tools can be slow. They never occupy a provider request slot or a download worker.
        start(track, captionToken -> {
            try {
                var result = captionCandidates(track, preferences, generated, captionToken, false);
                if (result.isEmpty() && originalFailure != null) throw originalFailure;
                var combined = new ArrayList<LyricsProvider.Candidate>(result);
                combined.addAll(result.isEmpty() ? alternatives : alternatives.stream()
                    .map(c -> new LyricsProvider.Candidate(c.document(), false, c.evidence())).toList());
                return List.copyOf(combined);
            } catch (Exception failure) {
                captionToken.check();
                if (originalFailure != null) throw originalFailure;
                if (!alternatives.isEmpty()) return alternatives;
                throw failure;
            }
        }, true, true, captionWorker, operationOwners.get(track.id()), token);
        return found;
    }

    public void search(Track track, LyricsProvider.Query query, boolean refresh) {
        search(track, query, refresh, false);
    }

    private void search(Track track, LyricsProvider.Query query, boolean refresh, boolean applyReliable) {
        if (track == null) return;
        String queryKey = hash(signature(track, query) + (applyReliable ? "a" : "m"));
        if (pending.containsKey(track.id()) && queryKey.equals(activeQueries.get(track.id()))) return;
        activeQueries.put(track.id(), queryKey);
        automatic.put(track.id(), new Attempt(track, 1, Long.MAX_VALUE, query));
        start(track, token -> discover(track, query, refresh, applyReliable, token), true, applyReliable);
    }

    private List<LyricsProvider.Candidate> discover(Track track, LyricsProvider.Query query, boolean refresh, boolean automatic, ProcessRunner.Token token) throws Exception {
        String key = hash(signature(track, query) + (automatic ? "a" : "m"));
        Cached cached = refresh ? null : cached(key);
        // An explicit version search is a superset of an automatic lookup of the same metadata.
        if (cached == null && !refresh && automatic) cached = cached(hash(signature(track, query) + "m"));
        List<LyricsProvider.Candidate> found = cached == null ? (automatic ? provider.search(query, token) : provider.alternatives(query, token)) : cached.candidates;
        token.check();
        if (cached == null) cache(key, found);
        return found;
    }

    public void source(Track track, String preferences, boolean generated) {
        start(track, token -> captionCandidates(track, preferences, generated, token, true).stream()
            .map(c -> new LyricsProvider.Candidate(c.document(), false, c.evidence())).toList(), true, false, captionWorker);
    }

    private List<LyricsProvider.Candidate> captionCandidates(Track track, String preferences, boolean generated, ProcessRunner.Token token, boolean refresh) throws Exception {
        if (captions == null) return List.of();
        String wanted = choice(track).language.equals("auto") ? preferences : choice(track).language;
        String source = refresh ? com.darkmusic.client.library.SourceLinks.resolve(track.file())
            : CaptionUrls.automatic(com.darkmusic.client.library.SourceLinks.read(track.file()));
        String key = hash("captions:1:" + track.id() + ":" + track.size() + ":" + track.modified() + ":" + source + ":" + wanted + ":" + generated);
        Cached prior = refresh ? null : cached(key);
        if (prior != null) return prior.candidates;
        var document = (refresh ? manualCaptions : captions).fetch(track, wanted, generated, token);
        token.check();
        var result = document == null || !document.available() ? List.<LyricsProvider.Candidate>of()
            : List.of(new LyricsProvider.Candidate(document, true, "lyrics.match.source"));
        cache(key, result);
        return result;
    }

    @FunctionalInterface
    private interface Search {
        List<LyricsProvider.Candidate> run(ProcessRunner.Token token) throws Exception;
    }

    private void start(Track track, Search search, boolean requiresOnline, boolean applyReliable) {
        start(track, search, requiresOnline, applyReliable, worker);
    }

    private void start(Track track, Search search, boolean requiresOnline, boolean applyReliable, ThreadPoolExecutor executor) {
        start(track, search, requiresOnline, applyReliable, executor, null, null);
    }

    private synchronized void start(Track track, Search search, boolean requiresOnline, boolean applyReliable, ThreadPoolExecutor executor,
                                    ProcessRunner.Token owner, ProcessRunner.Token parent) {
        if (track == null || closed) return;
        if (owner != null && owner.cancelled() || parent != null && (parent.cancelled() || pending.get(track.id()) != parent))
            return;
        if (!enabled || requiresOnline && !online) {
            publish(track, new Snapshot(Status.DISABLED, List.of()));
            return;
        }
        cancel(track);
        var token = new ProcessRunner.Token(owner);
        if (owner == null) operationOwners.remove(track.id());
        else operationOwners.put(track.id(), owner);
        if (snapshots.size() >= 64)
            snapshots.keySet().stream().filter(id -> !pending.containsKey(id) && !retained(id)).findFirst().ifPresent(snapshots::remove);
        latestOperations.put(track.id(), token);
        pending.put(track.id(), token);
        publish(track, new Snapshot(Status.SEARCHING, List.of()));
        try {
            Runnable task = () -> {
                try {
                    load(track);
                    if (!sameFile(track)) throw new CancellationException();
                    List<LyricsProvider.Candidate> found = search.run(token);
                    token.check();
                    if (!sameFile(track)) throw new CancellationException();
                    boolean sidecarExists = Files.exists(com.darkmusic.client.download.DownloadLyrics.sidecar(track.file()));
                    synchronized (this) {
                        if (pending.get(track.id()) != token || token.cancelled() || closed || !enabled || !online)
                            return;
                        var reliable = reliableMatch(found);
                        publish(track, new Snapshot(found.isEmpty() ? Status.NOT_FOUND : reliable != null ? Status.READY : Status.AMBIGUOUS, found));
                        // Automatic playback lookup starts only after local inspection found no lyrics.
                        if (applyReliable && reliable != null && choice(track).selected == null
                            && !sidecarExists) {
                            applyAutomatic(track, reliable, token);
                        }
                    }
                } catch (Exception failure) {
                    Status status = failureStatus(failure);
                    if (pending.get(track.id()) == token) {
                        retryAutomatic(track, status, failure);
                        publish(track, new Snapshot(status, List.of()));
                    }
                    if (status != Status.CANCELLED) LOG.debug("Lyric retrieval failed ({})", status, failure);
                } finally {
                    pending.remove(track.id(), token);
                }
            };
            if (executor == worker && !applyReliable) {
                if (!networkQueue.offerFirst(task)) throw new RejectedExecutionException();
            } else executor.execute(task);
        } catch (RejectedExecutionException full) {
            pending.remove(track.id(), token);
            publish(track, new Snapshot(Status.PROVIDER, List.of()));
            retryAutomatic(track, Status.PROVIDER, full);
        }
    }

    private void retryAutomatic(Track track, Status status, Exception failure) {
        if (status != Status.NETWORK && status != Status.RATE_LIMITED && status != Status.PROVIDER) return;
        long requested = failure instanceof LrclibProvider.Failure f ? f.retryAfterMillis : 0;
        // Only server-requested waits (or an unqualified 429) pause other songs.
        // A track's increasing retry delay must not become a provider-wide cooldown.
        long providerWait = Math.clamp(requested > 0 ? requested : status == Status.RATE_LIMITED ? 60_000 : 0,
            0, TimeUnit.HOURS.toMillis(24));
        automatic.computeIfPresent(track.id(), (id, attempt) -> {
            if (!attempt.track.equals(track)) return attempt;
            long count = Math.min(attempt.count, 8);
            long delay = Math.max(providerWait, Math.min(TimeUnit.MINUTES.toMillis(30), TimeUnit.SECONDS.toMillis(30L * count * count)));
            long retryAt;
            try {
                retryAt = Math.addExact(clock.getAsLong(), TimeUnit.MILLISECONDS.toNanos(delay));
            } catch (ArithmeticException excessiveWait) {
                retryAt = Long.MAX_VALUE;
            }
            if (providerWait > 0)
                retryNotBefore = Math.max(retryNotBefore, clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(providerWait));
            return new Attempt(track, attempt.count, retryAt, attempt.query, attempt.owner);
        });
    }

    private static Status failureStatus(Exception failure) {
        if (failure instanceof CancellationException || failure instanceof InterruptedException)
            return Status.CANCELLED;
        if (failure instanceof LrclibProvider.Failure provider) return Status.valueOf(provider.problem.name());
        if (failure instanceof java.net.UnknownHostException || failure instanceof java.net.ConnectException || failure instanceof java.net.SocketTimeoutException)
            return Status.NETWORK;
        if (failure instanceof com.darkmusic.client.download.DownloadDiagnostics.Failure) {
            var category = com.darkmusic.client.download.DownloadDiagnostics.classify(failure,
                com.darkmusic.client.download.DownloadDiagnostics.Stage.OPTIONAL, "", 0, 1, "").category();
            return switch (category) {
                case RATE_LIMITED -> Status.RATE_LIMITED;
                case NETWORK_FAILURE, NETWORK_TIMEOUT -> Status.NETWORK;
                case TOOL_MISSING, TOOL_INCOMPATIBLE -> Status.TOOLS;
                default -> Status.SOURCE;
            };
        }
        return Status.PROVIDER;
    }

    /**
     * Prefer the provider's first reliable version; weaker matches still require review.
     */
    private static LyricsDocument reliableMatch(List<LyricsProvider.Candidate> found) {
        for (var candidate : found) {
            if (candidate.reliable() && candidate.document().available()) return candidate.document();
        }
        return null;
    }

    public void deleted(Set<String> ids) {
        for (String id : ids) {
            ProcessRunner.Token token = latestOperations.remove(id);
            if (token != null) token.cancel();
            pending.remove(id);
            operationOwners.remove(id);
            choices.remove(id);
            snapshots.remove(id);
            known.remove(id);
            loaded.remove(id);
            loadedTracks.remove(id);
            reviewSelections.remove(id);
            encodings.remove(id);
            automatic.remove(id);
            activeQueries.remove(id);
            liveOffsets.remove(id);
            if (directory != null)
                submit(() -> Files.deleteIfExists(directory.resolve("tracks").resolve(hash(id) + ".json")));
        }
    }

    private void applyAutomatic(Track track, LyricsDocument document, ProcessRunner.Token token) {
        submit(() -> {
            token.check();
            if (!enabled || !online || closed || !sameFile(track) || choice(track).selected != null
                || Files.exists(com.darkmusic.client.download.DownloadLyrics.sidecar(track.file()))) return;
            Choice previous = choice(track);
            Choice next = new Choice(document, previous.offset, previous.language, false);
            if (directory != null)
                ConfigStore.atomicWrite(preference(track), ConfigStore.JSON.toJson(saved(track, next)));
            if (token.cancelled() || !enabled || !online || closed || !sameFile(track)) {
                if (directory != null)
                    ConfigStore.atomicWrite(preference(track), ConfigStore.JSON.toJson(saved(track, previous)));
                return;
            }
            choices.put(track.id(), next);
            trimChoices(track.id());
            rememberChoice(track, document);
        }).exceptionally(failure -> {
            if (!token.cancelled()) LOG.warn("Could not apply automatic lyric result", failure);
            return null;
        });
    }

    public void cancel(Track track) {
        if (track == null) return;
        automatic.computeIfPresent(track.id(), (id, attempt) -> new Attempt(attempt.track, attempt.count, Long.MAX_VALUE, attempt.query, attempt.owner));
        ProcessRunner.Token token = latestOperations.remove(track.id());
        if (token != null) token.cancel();
        if (pending.remove(track.id()) != null)
            publish(track, new Snapshot(Status.CANCELLED, List.of()));
    }

    public CompletableFuture<Void> importFile(Track track, Path path) {
        cancel(track);
        return submit(() -> {
            load(track);
            if (!path.getFileName().toString().matches("(?i).+\\.(lrc|txt)") || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1_000_000)
                throw new java.io.IOException("Invalid lyric file");
            String text = LyricsService.decode(Files.readAllBytes(path), encoding(track));
            var document = LyricsDocument.of("import", "", track.title(), track.artist(), track.album(), track.duration(), "und", LyricsDocument.Variant.UNKNOWN, false, false, text);
            if (!document.available()) throw new java.io.IOException("Empty lyric file");
            Choice next = new Choice(document, choice(track).offset, choice(track).language, true);
            LyricsDocument review = reviewSelections.remove(track.id());
            try {
                if (directory != null)
                    ConfigStore.atomicWrite(preference(track), ConfigStore.JSON.toJson(saved(track, next)));
            } catch (Exception failure) {
                if (review != null) reviewSelections.put(track.id(), review);
                throw failure;
            }
            choices.put(track.id(), next);
            rememberChoice(track, document);
        });
    }

    public CompletableFuture<Void> save(Track track, LyricsDocument document, boolean replace) {
        return submit(() -> {
            if (!sameFile(track) || !document.available())
                throw new java.io.IOException(com.darkmusic.client.text.Text.get("lyrics.file_changed"));
            LyricsFiles.save(track.file(), document, replace);
            publish(track, new Snapshot(Status.SAVED, snapshot(track).candidates));
        });
    }

    public CompletableFuture<Void> restore(Track track) {
        return submit(() -> {
            if (!sameFile(track))
                throw new java.io.IOException(com.darkmusic.client.text.Text.get("lyrics.file_changed"));
            LyricsFiles.restore(track.file());
        });
    }

    public CompletableFuture<Void> clearCache() {
        latestOperations.values().forEach(ProcessRunner.Token::cancel);
        return submitNetwork(() -> {
            synchronized (results) {
                results.clear();
                automatic.clear();
                cacheRevision++;
                if (directory != null) for (String name : List.of("cache", "misses")) {
                    if (!Files.isDirectory(directory.resolve(name), LinkOption.NOFOLLOW_LINKS)) continue;
                    try (var files = Files.list(directory.resolve(name))) {
                        for (Path p : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList())
                            Files.deleteIfExists(p);
                    }
                }
            }
        });
    }

    private Cached cached(String key) {
        synchronized (results) {
            Cached value = results.get(key);
            if (value == null && directory != null) try {
                Path miss = directory.resolve("misses").resolve(key + ".json");
                value = read(Files.isRegularFile(miss, LinkOption.NOFOLLOW_LINKS) ? miss : directory.resolve("cache").resolve(key + ".json"), Cached.class);
            } catch (Exception ignored) {
            }
            if (value == null || value.schema != 1 || value.expires < System.currentTimeMillis() || value.candidates == null || value.candidates.size() > 20)
                return null;
            try {
                var checked = value.candidates.stream().map(c -> new LyricsProvider.Candidate(validated(c.document()), c.reliable(), c.evidence())).toList();
                value = new Cached(1, value.expires, checked);
                results.put(key, value);
                trim();
                return value;
            } catch (Exception invalid) {
                return null;
            }
        }
    }

    private void cache(String key, List<LyricsProvider.Candidate> candidates) {
        synchronized (results) {
            var value = new Cached(1, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(candidates.isEmpty() ? 14 : 90), candidates);
            results.put(key, value);
            trim();
            if (directory != null) try {
                Path folder = directory.resolve(candidates.isEmpty() ? "misses" : "cache");
                String json = ConfigStore.JSON.toJson(value);
                ConfigStore.atomicWrite(folder.resolve(key + ".json"), json);
                Files.deleteIfExists(directory.resolve(candidates.isEmpty() ? "cache" : "misses").resolve(key + ".json"));
                bytesSinceCacheTrim += json.getBytes(StandardCharsets.UTF_8).length;
                // A library sweep must not enumerate the cache directory after every song.
                if (System.nanoTime() < nextCacheTrim && bytesSinceCacheTrim < 4L * 1024 * 1024) return;
                nextCacheTrim = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
                bytesSinceCacheTrim = 0;
                for (String name : List.of("cache", "misses")) {
                    Path retained = directory.resolve(name);
                    if (!Files.isDirectory(retained, LinkOption.NOFOLLOW_LINKS)) continue;
                    long limit = (name.equals("misses") ? 16L : 64L) * 1024 * 1024;
                    try (var files = Files.list(retained)) {
                        var all = files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
                        long bytes = 0;
                        for (Path file : all) bytes += Files.size(file);
                        for (int i = 0; i < all.size() && (all.size() - i > 256_000 || bytes > limit); i++) {
                            bytes -= Files.size(all.get(i));
                            Files.deleteIfExists(all.get(i));
                        }
                    }
                }
            } catch (Exception failure) {
                LOG.warn("Could not save lyric lookup cache", failure);
            }
        }
    }

    private void trim() {
        while (results.size() > 128) results.remove(results.keySet().iterator().next());
    }

    private static boolean sameFile(Track track) {
        try {
            var a = Files.readAttributes(track.file(), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isRegularFile() && a.size() == track.size() && a.lastModifiedTime().toMillis() == track.modified();
        } catch (Exception e) {
            return false;
        }
    }

    private Path preference(Track track) {
        return directory.resolve("tracks").resolve(hash(track.id()) + ".json");
    }

    public void prune(List<Track> tracks, Set<String> registeredRoots, Set<String> completeRoots) {
        prune(tracks, registeredRoots, completeRoots, Map.of());
    }

    public void prune(List<Track> tracks, Set<String> registeredRoots, Set<String> completeRoots, Map<String, List<String>> unreadableScopes) {
        if (directory == null) return;
        submit(() -> {
            Path folder = directory.resolve("tracks");
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
            var present = new HashSet<String>();
            for (Track track : tracks) present.add(hash(track.id()) + ".json");
            try (var files = Files.list(folder)) {
                for (Path path : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) {
                    if (present.contains(path.getFileName().toString()) || Files.isSymbolicLink(path)) continue;
                    Saved saved;
                    try {
                        saved = read(path, Saved.class);
                    } catch (Exception invalid) {
                        continue;
                    }
                    // Legacy records can be pruned only when every registered root was checked successfully.
                    if (saved != null && protectedScope(saved.rootId, saved.trackId, unreadableScopes)) continue;
                    if (saved != null && (saved.rootId == null ? completeRoots.containsAll(registeredRoots)
                        : !registeredRoots.contains(saved.rootId) || completeRoots.contains(saved.rootId)))
                        Files.deleteIfExists(path);
                }
            }
        });
    }

    private static boolean protectedScope(String root, String id, Map<String, List<String>> scopes) {
        if (root == null) return !scopes.isEmpty();
        var unreadable = scopes.getOrDefault(root, List.of());
        if (unreadable.isEmpty()) return false;
        if (id == null || !id.startsWith(root + ":")) return true;
        String relative = id.substring(root.length() + 1).replace('\\', '/');
        return unreadable.stream().anyMatch(scope -> scope.isEmpty() || relative.equals(scope) || relative.startsWith(scope + "/"));
    }

    private Saved saved(Track track, Choice choice) {
        LyricsDocument review = reviewSelections.get(track.id());
        return new Saved(1, review == null ? choice.selected : review, choice.offset, choice.language,
            track.size(), track.modified(), choice.pinned, review != null, track.rootId(), track.id(), encoding(track));
    }

    private String signature(Track track, LyricsProvider.Query query) {
        return hash(provider.id() + ":1:" + ConfigStore.JSON.toJson(query));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> T read(Path path, Class<T> type) throws Exception {
        if (Files.size(path) > 8_000_000) throw new java.io.IOException("Lyric record too large");
        return ConfigStore.JSON.fromJson(Files.readString(path), type);
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }

    private CompletableFuture<Void> submit(Operation action) {
        var done = new CompletableFuture<Void>();
        try {
            storage.execute(() -> {
                try {
                    action.run();
                    done.complete(null);
                } catch (Exception e) {
                    done.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            done.completeExceptionally(e);
        }
        return done;
    }

    private CompletableFuture<Void> submitNetwork(Operation action) {
        var done = new CompletableFuture<Void>();
        try {
            worker.execute(() -> {
                try {
                    action.run();
                    done.complete(null);
                } catch (Exception e) {
                    done.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            done.completeExceptionally(e);
        }
        return done;
    }

    @Override
    public void close() {
        closed = true;
        latestOperations.values().forEach(ProcessRunner.Token::cancel);
        worker.shutdownNow();
        captionWorker.shutdownNow();
        storage.shutdown();
        try {
            if (!storage.awaitTermination(3, TimeUnit.SECONDS)) storage.shutdownNow();
        } catch (InterruptedException interrupted) {
            storage.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (provider instanceof AutoCloseable closeable) try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
