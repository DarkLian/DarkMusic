package com.darkmusic.client.lyrics;

import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LyricsOwnershipTest {
    @TempDir
    Path temp;

    private Track track() throws Exception {
        Path p = Files.writeString(temp.resolve("identity.dat"), "Disposable lyric identity");
        return new Track("r", "identity.dat", p, "Song", "Artist", "", 180, Files.size(p), Files.getLastModifiedTime(p).toMillis(), "");
    }

    private LyricsDocument document() {
        return LyricsDocument.of("fixture", "1", "Song", "Artist", "", 180, "ja", LyricsDocument.Variant.ORIGINAL, false, false, "[00:01]Words");
    }

    private LyricsProvider provider(Function<LyricsProvider.Query, List<LyricsProvider.Candidate>> search) {
        return new LyricsProvider() {
            public String id() {
                return "owner-test";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) {
                return search.apply(q);
            }
        };
    }

    @Test
    void libraryCancelReachesBlockedCaptionChildButPreservesLaterManualSearch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var now = new AtomicLong();
        Track t = track();
        var provider = provider(q -> q.title().equals("Manual") ? List.of(new LyricsProvider.Candidate(document(), true, "lyrics.match.metadata")) : List.of());
        try (var service = new LyricsService(temp.resolve("prefs"), provider, now::get)) {
            service.lookup.configure(true, true);
            var child = new AtomicReference<ProcessRunner.Token>();
            service.lookup.captions((track, languages, generated, token) -> {
                child.set(token);
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return document();
                } finally {
                    finished.countDown();
                }
            });
            service.startLibraryLookup();
            Await.until(Duration.ofSeconds(5), () -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(List.of(t), true);
                return entered.getCount() == 0;
            }, "Caption child starts");
            service.cancelLibraryLookup();
            assertTrue(child.get().cancelled(), "The run owner cancels the replacement child token");
            service.lookup.search(t, new LyricsProvider.Query("Manual", "Artist", "", 180), true);
            Await.until(Duration.ofSeconds(5), () -> service.lookup.snapshot(t).status() == LyricsLookup.Status.READY, "Unrelated manual search completes");
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            service.lookup.offset(t, 0).get(5, TimeUnit.SECONDS);
            assertNull(service.lookup.choice(t).selected());
            assertEquals(LyricsLookup.Status.READY, service.lookup.snapshot(t).status());
        } finally {
            release.countDown();
        }
    }

    @Test
    void libraryCancelReachesActiveRetryAndStopsFurtherRetries() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var now = new AtomicLong();
        var retry = new AtomicReference<ProcessRunner.Token>();
        Track t = track();
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "retry-owner";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) throws Exception {
                if (calls.incrementAndGet() == 1) throw new LrclibProvider.Failure(LrclibProvider.Problem.NETWORK);
                retry.set(token);
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return List.of(new Candidate(document(), true, "lyrics.match.metadata"));
                } finally {
                    finished.countDown();
                }
            }
        };
        try (var service = new LyricsService(temp.resolve("retry"), provider, now::get)) {
            service.lookup.configure(true, true);
            service.startLibraryLookup();
            Await.until(Duration.ofSeconds(5), () -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(List.of(t), true);
                return service.lookup.retrySeconds(t) > 0;
            }, "First failure");
            now.addAndGet(TimeUnit.SECONDS.toNanos(31));
            service.lookup.tickRetries();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.cancelLibraryLookup();
            assertTrue(retry.get().cancelled());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            service.lookup.offset(t, 0).get(5, TimeUnit.SECONDS);
            now.addAndGet(TimeUnit.HOURS.toNanos(2));
            service.lookup.tickRetries();
            assertEquals(2, calls.get());
            assertNull(service.lookup.choice(t).selected());
        } finally {
            release.countDown();
        }
    }

    @Test
    void actualHttpAdapterPreservesMissingRetryAfter() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var provider = new LrclibProvider()) {
            var uri = new URI("http", null, server.getAddress().getAddress().getHostAddress(), server.getAddress().getPort(), "/", null, null);
            var reply = provider.http(uri, new ProcessRunner.Token());
            assertEquals(503, reply.status());
            assertNull(reply.retryAfter());
            try (var classifier = new LrclibProvider((url, token) -> reply)) {
                var failure = assertThrows(LrclibProvider.Failure.class, () -> classifier.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token()));
                assertEquals(0, failure.retryAfterMillis);
            }
        } finally {
            server.stop(0);
        }
    }
}
