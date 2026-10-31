package com.darkmusic.client.lyrics;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.tools.ProcessRunner;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * When lyric lookups may run again: each song's attempts and next retry, and the provider-wide wait a server asked
 * for. A song's growing retry delay never becomes a provider-wide cooldown. Thread-safe; read by the client tick and
 * written by the lyric workers.
 */
final class LyricRetries {
    /**
     * A song's lookup: how many times it ran, when it may run again (Long.MAX_VALUE while running or held), the
     * player's query for a manual search, and the library run that owns it.
     */
    record Attempt(Track track, int count, long retryAt, LyricsProvider.Query query, ProcessRunner.Token owner) {
        Attempt(Track track, int count, long retryAt, LyricsProvider.Query query) {
            this(track, count, retryAt, query, null);
        }
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    // Raised by lyric workers on rate limits; only ever moves later.
    private final AtomicLong notBefore = new AtomicLong();

    LyricRetries(LongSupplier clock) {
        this.clock = clock;
    }

    Attempt get(String id) {
        return attempts.get(id);
    }

    void put(String id, Attempt attempt) {
        attempts.put(id, attempt);
    }

    void remove(String id) {
        attempts.remove(id);
    }

    void remove(String id, Attempt attempt) {
        attempts.remove(id, attempt);
    }

    void clear() {
        attempts.clear();
    }

    int size() {
        return attempts.size();
    }

    Collection<Attempt> all() {
        return attempts.values();
    }

    /**
     * Keeps at most 512 songs: drops the first idle one other than keep that is not running.
     */
    void trim(String keep, Map<String, ProcessRunner.Token> running) {
        if (attempts.size() > 512)
            attempts.entrySet().stream().filter(e -> !e.getKey().equals(keep) && e.getValue().retryAt() == Long.MAX_VALUE && !running.containsKey(e.getKey()))
                .map(Map.Entry::getKey).findFirst().ifPresent(attempts::remove);
    }

    /**
     * A cancelled lookup does not retry until it is started again.
     */
    void hold(String id) {
        attempts.computeIfPresent(id, (key, attempt) -> new Attempt(attempt.track(), attempt.count(), Long.MAX_VALUE, attempt.query(), attempt.owner()));
    }

    void forgetOwner(ProcessRunner.Token owner) {
        attempts.entrySet().removeIf(e -> e.getValue().owner() == owner);
    }

    /**
     * Whether a server-requested wait still applies to every song.
     */
    boolean waiting() {
        return waiting(clock.getAsLong());
    }

    boolean waiting(long now) {
        return now < notBefore.get();
    }

    long cooldownSeconds() {
        long remaining = notBefore.get() - clock.getAsLong();
        return remaining <= 0 ? 0 : TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1;
    }

    /**
     * Seconds until this song's automatic lookup retries, or 0 when it is not waiting.
     */
    long retrySeconds(Track track) {
        Attempt attempt = track == null ? null : attempts.get(track.id());
        if (attempt == null || attempt.retryAt() == Long.MAX_VALUE) return 0;
        long delay = Math.max(attempt.retryAt(), notBefore.get()) - clock.getAsLong();
        return delay <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toSeconds(delay) + 1);
    }

    boolean ownerOutstanding(ProcessRunner.Token owner, Map<String, ProcessRunner.Token> running) {
        return owner != null && !owner.cancelled() && attempts.values().stream()
            .anyMatch(a -> a.owner() == owner && (a.retryAt() != Long.MAX_VALUE || running.containsKey(a.track().id())));
    }

    /**
     * Schedules the song's next attempt after a network, rate-limit or provider failure: 30 s times the attempt count
     * squared, up to 30 minutes, or longer if the server asked. Only a server-requested wait (or an unqualified 429)
     * pauses other songs.
     */
    void failed(Track track, LyricsLookup.Status status, Exception failure) {
        if (status != LyricsLookup.Status.NETWORK && status != LyricsLookup.Status.RATE_LIMITED && status != LyricsLookup.Status.PROVIDER)
            return;
        long requested = failure instanceof LrclibProvider.Failure f ? f.retryAfterMillis : 0;
        // Only server-requested waits (or an unqualified 429) pause other songs.
        // A track's increasing retry delay must not become a provider-wide cooldown.
        long providerWait = Math.clamp(requested > 0 ? requested : status == LyricsLookup.Status.RATE_LIMITED ? 60_000 : 0,
            0, TimeUnit.HOURS.toMillis(24));
        attempts.computeIfPresent(track.id(), (id, attempt) -> {
            if (!attempt.track().equals(track)) return attempt;
            long count = Math.min(attempt.count(), 8);
            long delay = Math.max(providerWait, Math.min(TimeUnit.MINUTES.toMillis(30), TimeUnit.SECONDS.toMillis(30L * count * count)));
            long retryAt;
            try {
                retryAt = Math.addExact(clock.getAsLong(), TimeUnit.MILLISECONDS.toNanos(delay));
            } catch (ArithmeticException excessiveWait) {
                retryAt = Long.MAX_VALUE;
            }
            if (providerWait > 0)
                notBefore.accumulateAndGet(clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(providerWait), Math::max);
            return new Attempt(track, attempt.count(), retryAt, attempt.query(), attempt.owner());
        });
    }
}
