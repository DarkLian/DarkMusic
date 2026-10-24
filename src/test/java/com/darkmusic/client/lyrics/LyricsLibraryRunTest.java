package com.darkmusic.client.lyrics;

import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LyricsLibraryRunTest {
    @TempDir
    Path temp;

    private Track track(String name) throws Exception {
        Path file = Files.writeString(temp.resolve(name), "Disposable identity, not audio");
        return new Track("root", name, file, name, "Artist", "", 180, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
    }

    @Test
    void startingDuringProviderCooldownStillInitializesProgress() throws Exception {
        Track a = track("a.dat"), b = track("b.dat");
        var now = new AtomicLong();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "cooldown";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                if (calls.incrementAndGet() == 1)
                    throw new LrclibProvider.Failure(LrclibProvider.Problem.RATE_LIMITED, 429, 60_000);
                return List.of();
            }
        };
        try (var service = new LyricsService(temp.resolve("prefs"), provider, now::get)) {
            service.lookup.configure(true, true);
            service.lookup.load(a);
            service.lookup.automatic(a, false);
            Await.until(Duration.ofSeconds(5), () -> service.lookup.snapshot(a).status() == LyricsLookup.Status.RATE_LIMITED, "Rate limit recorded");
            service.startLibraryLookup();
            service.background(List.of(a, b), Set.of("root"), true);
            assertEquals(2, service.libraryTotal(), "Starting a run must not look frozen at 0/0 during cooldown");
            assertTrue(service.lookup.cooldownSeconds() > 0);
            assertTrue(service.libraryLookupRunning());
            now.addAndGet(TimeUnit.SECONDS.toNanos(61));
            Await.until(Duration.ofSeconds(5), () -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(List.of(a, b), Set.of("root"), service.libraryLookupRunning());
                return !service.libraryLookupRunning();
            }, "Library lookup resumes after cooldown");
            assertEquals(2, service.libraryChecked());
            assertEquals(3, calls.get(), "Only the failed request is retried");
            service.startLibraryLookup();
            assertEquals(0, service.libraryChecked(), "A new run starts with fresh counters");
            service.cancelLibraryLookup();
            assertFalse(service.libraryLookupRunning());
        }
    }

    @Test
    void explicitRunCompletesAndAppliesFirstReliableVersionWithoutOpeningLyrics() throws Exception {
        Track a = track("first.dat"), b = track("second.dat"), c = track("weak.dat");
        var tracks = List.of(a, b, c);
        var now = new AtomicLong();
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "library";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) {
                if (query.title().equals(b.title())) return List.of();
                boolean reliable = !query.title().equals(c.title());
                return List.of(new Candidate(document("first", query), reliable, "lyrics.match.metadata"),
                    new Candidate(document("alternate", query), reliable, "lyrics.match.metadata"));
            }
        };
        Path prefs = temp.resolve("library-prefs");
        try (var service = new LyricsService(prefs, provider, now::get)) {
            service.lookup.configure(true, true);
            service.startLibraryLookup();
            Await.until(Duration.ofSeconds(5), () -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(tracks, Set.of("root"), service.libraryLookupRunning());
                return !service.libraryLookupRunning() && service.lookup.choice(a).selected() != null;
            }, "Explicit library run completes");
            assertEquals(3, service.libraryChecked());
            assertEquals("[00:01]first", service.document(a).text());
            assertEquals(2, service.lookup.snapshot(a).candidates().size(), "Alternate versions remain selectable");
            assertEquals(LyricsLookup.Status.NOT_FOUND, service.lookup.snapshot(b).status());
            assertEquals(LyricsLookup.Status.AMBIGUOUS, service.lookup.snapshot(c).status());
            assertNull(service.lookup.choice(c).selected(), "A weak first result must not be applied");
            service.enabled(false);
            assertFalse(service.libraryLookupRunning());
        }
        try (var restarted = new LyricsLookup(prefs, provider)) {
            restarted.load(a);
            assertEquals("[00:01]first", restarted.choice(a).selected().text());
        }
    }

    private static LyricsDocument document(String words, LyricsProvider.Query query) {
        return LyricsDocument.of("library", words, query.title(), query.artist(), "", query.duration(), "und",
            LyricsDocument.Variant.ORIGINAL, false, false, "[00:01]" + words);
    }

    @Test
    void repeatedSongNetworkFailuresDoNotFreezeLaterLibrarySongs() throws Exception {
        Track bad = track("bad.dat"), good = track("good.dat");
        var now = new AtomicLong();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "per-song-retry";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                if (query.title().equals(bad.title())) {
                    failures.incrementAndGet();
                    throw new LrclibProvider.Failure(LrclibProvider.Problem.NETWORK);
                }
                return List.of(new Candidate(document("found", query), true, "lyrics.match.metadata"));
            }
        };
        try (var service = new LyricsService(temp.resolve("per-song"), provider, now::get)) {
            service.lookup.configure(true, true);
            service.lookup.load(bad);
            service.lookup.automatic(bad, false);
            Await.until(Duration.ofSeconds(5), () -> service.lookup.retrySeconds(bad) > 0, "First retry scheduled");
            now.set(TimeUnit.SECONDS.toNanos(31));
            service.lookup.tickRetries();
            Await.until(Duration.ofSeconds(5), () -> failures.get() == 2 && service.lookup.retrySeconds(bad) > 100, "Second per-song retry scheduled");
            assertEquals(0, service.lookup.cooldownSeconds(), "A song's 120-second retry is not a provider-wide cooldown");
            service.startLibraryLookup();
            var tracks = List.of(bad, good);
            now.set(TimeUnit.SECONDS.toNanos(40));
            service.background(tracks, Set.of("root"), true);
            Await.until(Duration.ofSeconds(5), () -> service.inspected(bad), "Inspect failed song locally");
            now.set(TimeUnit.SECONDS.toNanos(41));
            service.background(tracks, Set.of("root"), true);
            now.set(TimeUnit.SECONDS.toNanos(42));
            service.background(tracks, Set.of("root"), true);
            Await.until(Duration.ofSeconds(5), () -> service.inspected(good), "Inspect later song locally");
            now.set(TimeUnit.SECONDS.toNanos(43));
            service.background(tracks, Set.of("root"), true);
            Await.until(Duration.ofSeconds(5), () -> service.lookup.choice(good).selected() != null,
                "Later song is checked without waiting for the failed song");
            assertEquals(2, failures.get());
        }
    }

    @Test
    void shortServerWaitIsNotInflatedToTheSongsRetryBackoff() throws Exception {
        Track bad = track("limited.dat"), good = track("later.dat");
        var now = new AtomicLong();
        var goodCalls = new java.util.concurrent.atomic.AtomicInteger();
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "short-server-wait";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                if (query.title().equals(bad.title()))
                    throw new LrclibProvider.Failure(LrclibProvider.Problem.RATE_LIMITED, 429, 1_000);
                goodCalls.incrementAndGet();
                return List.of();
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("short-wait"), provider, now::get)) {
            lookup.configure(true, true);
            lookup.load(bad);
            lookup.load(good);
            lookup.automatic(bad, false);
            Await.until(Duration.ofSeconds(5), () -> lookup.snapshot(bad).status() == LyricsLookup.Status.RATE_LIMITED, "Server wait recorded");
            assertEquals(1, lookup.cooldownSeconds());
            assertTrue(lookup.retrySeconds(bad) >= 30, "Song-specific retry backoff remains separate");
            lookup.automatic(good, false);
            assertEquals(0, goodCalls.get(), "New automatic requests respect the actual server wait");
            now.set(TimeUnit.SECONDS.toNanos(2));
            lookup.automatic(good, false);
            Await.until(Duration.ofSeconds(5), () -> goodCalls.get() == 1, "Next song proceeds when the server wait expires");
            assertEquals(0, lookup.cooldownSeconds());
        }
    }

    @Test
    void server503WithoutRetryAfterDoesNotInventAMinuteOfRateLimiting() throws Exception {
        try (var provider = new LrclibProvider((uri, token) -> new LrclibProvider.Reply(503, "", null))) {
            var failure = assertThrows(LrclibProvider.Failure.class,
                () -> provider.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token()));
            assertEquals(LrclibProvider.Problem.PROVIDER, failure.problem);
            assertEquals(0, failure.retryAfterMillis);
        }
    }
}
