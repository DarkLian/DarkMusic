package com.darkmusic.client.lyrics;

import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MultilingualLyricsTest {
    @TempDir
    Path temp;

    @Test
    void availabilityMarksKeepLookupFailuresDistinctFromMissingLyrics() {
        assertEquals("\u2713", LyricsInfo.AVAILABLE.mark());
        assertEquals("\u2713", LyricsInfo.PLAIN.mark());
        assertEquals("?", LyricsInfo.UNKNOWN.mark());
        assertEquals("?", LyricsInfo.CHECKING.mark());
        assertEquals("?", LyricsInfo.LOCAL_MISSING.mark());
        assertEquals(LyricsInfo.State.UNKNOWN, LyricsInfo.LOCAL_MISSING.state());
        assertEquals("lyrics.darkmusic.availability.unavailable", LyricsInfo.LOCAL_MISSING.key());
        assertEquals("lyrics.instrumental", LyricsInfo.INSTRUMENTAL.key());
        assertEquals(LyricsInfo.State.UNAVAILABLE, LyricsInfo.INSTRUMENTAL.state());
        assertEquals("?", LyricsInfo.SEARCHING.mark());
        assertEquals("?", LyricsInfo.lookup(LyricsLookup.Status.AMBIGUOUS).mark());
        assertEquals("\u2717", LyricsInfo.UNAVAILABLE.mark());
        assertEquals("\u2717", LyricsInfo.LOCAL_ERROR.mark());
        for (var status : List.of(LyricsLookup.Status.NETWORK, LyricsLookup.Status.RATE_LIMITED,
            LyricsLookup.Status.PROVIDER, LyricsLookup.Status.SOURCE, LyricsLookup.Status.TOOLS)) {
            var info = LyricsInfo.lookup(status);
            assertEquals("\u2717", info.mark());
            assertEquals(LyricsInfo.State.UNKNOWN, info.state());
            assertEquals("lyrics.status." + status.name().toLowerCase(Locale.ROOT), info.key());
        }
        var absent = LyricsInfo.lookup(LyricsLookup.Status.NOT_FOUND);
        assertEquals("\u2717", absent.mark());
        assertEquals(LyricsInfo.State.UNAVAILABLE, absent.state());
        assertEquals("lyrics.status.not_found", absent.key());
    }

    private static LyricsDocument document(String text) {
        return LyricsDocument.of("lrclib", "42", "Song", "Artist", "Album", 180, "und", LyricsDocument.Variant.UNKNOWN, false, false, text);
    }

    @Test
    void cachedAvailabilitySurvivesDocumentEvictionAndForegroundLyricsStayLoaded() throws Exception {
        var tracks = new ArrayList<Track>();
        var words = document("Cached words");
        Path prefs = temp.resolve("bounded-choices");
        try (var lookup = new LyricsLookup(prefs, provider(q -> List.of()))) {
            for (int i = 0; i < 160; i++) {
                Track track = identity("bounded-" + i);
                tracks.add(track);
                lookup.select(track, words).get(5, TimeUnit.SECONDS);
            }
        }
        try (var lookup = new LyricsLookup(prefs, provider(q -> List.of()))) {
            lookup.retain(tracks.getFirst());
            for (Track track : tracks) lookup.load(track);
            var choices = LyricsLookup.class.getDeclaredField("choices");
            choices.setAccessible(true);
            assertTrue(((Map<?, ?>) choices.get(lookup)).size() <= 128);
            assertEquals(words, lookup.choice(tracks.getFirst()).selected());
            Track evicted = tracks.stream().filter(t -> lookup.choice(t).selected() == null).findFirst().orElseThrow();
            assertEquals(LyricsInfo.PLAIN, lookup.knownInfo(evicted));
            lookup.load(evicted);
            assertEquals(words, lookup.choice(evicted).selected());
            var changed = new Track(evicted.rootId(), evicted.relativePath(), evicted.file(), evicted.title(), evicted.artist(), evicted.album(),
                evicted.duration(), evicted.size(), evicted.modified() + 1, "");
            assertNull(lookup.knownInfo(changed));
        }
    }

    @Test
    void sourceToolFailureDoesNotHideARetryableProviderFailureAndCancelStopsRetry() throws Exception {
        Track track = identity("provider-and-source");
        var now = new java.util.concurrent.atomic.AtomicLong();
        var calls = new AtomicInteger();
        var p = new LyricsProvider() {
            public String id() {
                return "provider-and-source";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) throws Exception {
                calls.incrementAndGet();
                throw new LrclibProvider.Failure(LrclibProvider.Problem.PROVIDER, 503, 90_000);
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("provider-and-source-prefs"), p, now::get)) {
            lookup.configure(true, true);
            lookup.captions((t, languages, generated, token) -> {
                throw new com.darkmusic.client.download.DownloadDiagnostics.Failure(
                    com.darkmusic.client.download.DownloadDiagnostics.Category.TOOL_MISSING,
                    com.darkmusic.client.download.DownloadDiagnostics.Stage.OPTIONAL,
                    com.darkmusic.client.download.DownloadDiagnostics.Tool.YT_DLP, null, "", null);
            });
            lookup.load(track);
            lookup.automatic(track, false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.PROVIDER);
            assertTrue(lookup.retrySeconds(track) >= 90);
            lookup.cancel(track);
            assertEquals(0, lookup.retrySeconds(track));
            now.set(TimeUnit.HOURS.toNanos(1));
            lookup.tickRetries();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void backgroundIgnoresOfflineRootsAndResumesWhenTheyRecover() throws Exception {
        Track track = identity("offline-library");
        var tracks = List.of(track);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var calls = new AtomicInteger();
        try (var service = new LyricsService(temp.resolve("offline-library-prefs"), provider(q -> {
            calls.incrementAndGet();
            return List.of();
        }), now::get)) {
            service.lookup.configure(true, true);
            for (int i = 0; i < 10; i++) {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(tracks, Set.of(), true);
            }
            assertEquals(0, calls.get());
            await(() -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(tracks, Set.of(track.rootId()), true);
                return service.lookup.snapshot(track).status() == LyricsLookup.Status.NOT_FOUND;
            });
            assertEquals(1, calls.get());
        }
    }

    @Test
    void hundredThousandLibraryEntriesDoNotCreateAnUnboundedLookupBacklog() throws Exception {
        Track track = identity("large-library");
        var reads = new AtomicInteger();
        var tracks = new AbstractList<Track>() {
            public int size() {
                return 100_000;
            }

            public Track get(int i) {
                assertEquals(0, i, "The next track must wait for the active request");
                reads.incrementAndGet();
                return track;
            }
        };
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var p = new LyricsProvider() {
            public String id() {
                return "large-library";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) throws Exception {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return List.of();
            }
        };
        try (var service = new LyricsService(temp.resolve("large-library-prefs"), p, now::get)) {
            service.lookup.configure(true, true);
            await(() -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(tracks, true);
                return entered.getCount() == 0;
            });
            for (int i = 0; i < 1000; i++) {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(tracks, true);
            }
            assertEquals(1, reads.get());
            release.countDown();
            await(() -> service.lookup.snapshot(track).status() == LyricsLookup.Status.NOT_FOUND);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedManualSearchRetriesItsEditedQueryWithoutSelectionOrAutomaticApplication() throws Exception {
        Track track = identity("manual-retry");
        var now = new java.util.concurrent.atomic.AtomicLong();
        var calls = new AtomicInteger();
        var p = new LyricsProvider() {
            public String id() {
                return "manual-retry";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                assertEquals("Edited title", query.title());
                assertEquals("Edited artist", query.artist());
                if (calls.incrementAndGet() == 1)
                    throw new LrclibProvider.Failure(LrclibProvider.Problem.PROVIDER, 503, 90_000);
                return List.of(new Candidate(document("Recovered"), true, "lyrics.match.metadata"));
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("manual-retry-prefs"), p, now::get)) {
            lookup.configure(true, true);
            lookup.search(track, new LyricsProvider.Query("Edited title", "Edited artist", "", 180), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.PROVIDER);
            now.set(TimeUnit.SECONDS.toNanos(89));
            lookup.tickRetries();
            assertEquals(1, calls.get());
            now.set(TimeUnit.SECONDS.toNanos(91));
            lookup.automatic(track, false);
            assertEquals(1, calls.get(), "Playback must not replace an edited search with the file's metadata");
            await(() -> {
                lookup.tickRetries();
                return lookup.snapshot(track).status() == LyricsLookup.Status.READY;
            });
            assertEquals(2, calls.get());
            assertNull(lookup.choice(track).selected(), "Manual search results still need Apply");
        }
    }

    @Test
    void backgroundLibraryLookupFindsAllSongsCachesMissesAndRetriesWithoutSelecting() throws Exception {
        var tracks = new ArrayList<Track>();
        for (String title : List.of("Missing", "Failed", "Found")) {
            var t = identity(title);
            tracks.add(new Track(t.rootId(), t.relativePath(), t.file(), title, t.artist(), t.album(), t.duration(), t.size(), t.modified(), ""));
        }
        var now = new java.util.concurrent.atomic.AtomicLong();
        var calls = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        var p = new LyricsProvider() {
            public String id() {
                return "background";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                int count = calls.computeIfAbsent(query.title(), ignored -> new AtomicInteger()).incrementAndGet();
                if (query.title().equals("Missing")) return List.of();
                if (query.title().equals("Failed") && count == 1)
                    throw new LrclibProvider.Failure(LrclibProvider.Problem.NETWORK);
                return List.of(new Candidate(document("[00:01]" + query.title()), true, "lyrics.match.metadata"));
            }
        };
        Path prefs = temp.resolve("background-prefs");
        try (var service = new LyricsService(prefs, p, now::get)) {
            service.lookup.configure(true, true);
            service.background(tracks, false);
            assertTrue(calls.isEmpty());
            await(() -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(new ArrayList<>(tracks), true);
                return service.lookup.choice(tracks.get(1)).selected() != null && service.lookup.choice(tracks.get(2)).selected() != null;
            });
            assertEquals(1, calls.get("Missing").get());
            assertEquals(2, calls.get("Failed").get());
            assertEquals(1, calls.get("Found").get());
            assertEquals(LyricsLookup.Status.NOT_FOUND, service.lookup.snapshot(tracks.getFirst()).status());
            for (int i = 0; i < 100; i++) {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(tracks, true);
            }
            assertEquals(1, calls.get("Missing").get());
        }
        try (var service = new LyricsService(prefs, p, now::get)) {
            service.lookup.configure(true, true);
            await(() -> {
                now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                service.background(tracks, true);
                return service.lookup.snapshot(tracks.getFirst()).status() == LyricsLookup.Status.NOT_FOUND;
            });
            assertEquals(1, calls.get("Missing").get(), "Confirmed misses survive a restart");
        }
    }

    @Test
    void selectedSongAutomaticallyPublishesLyricsWithoutOpeningTheLyricsScreen() throws Exception {
        Track track = identity("selected-song");
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var p = new LyricsProvider() {
            public String id() {
                return "selected-song-test";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                calls.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return List.of(new Candidate(document("[00:01]Words"), true, "lyrics.match.metadata"));
            }
        };
        try (var service = new LyricsService(temp.resolve("selected-prefs"), p)) {
            service.lookup.configure(true, true);
            service.request(track, false);
            await(() -> service.inspected(track));
            assertEquals(0, calls.get());
            service.request(track, true);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(LyricsInfo.SEARCHING, service.info(track));
            for (int i = 0; i < 100; i++) service.request(track, true);
            release.countDown();
            await(() -> service.info(track).equals(LyricsInfo.AVAILABLE));
            assertEquals(1, calls.get());
            assertEquals("Words", service.document(track).timed().lines().getFirst().text());
        } finally {
            release.countDown();
        }
    }

    // A disposable non-audio identity fixture. No decoding or generated audio assets are involved.
    private Track identity(String name) throws Exception {
        Path path = temp.resolve(name + ".dat");
        Files.writeString(path, "disposable identity fixture");
        return new Track("root", path.getFileName().toString(), path, "Song", "Artist", "Album", 180, Files.size(path), Files.getLastModifiedTime(path).toMillis(), "");
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out");
            Thread.sleep(5);
        }
    }

    private static LyricsProvider provider(java.util.function.Function<LyricsProvider.Query, List<LyricsProvider.Candidate>> results) {
        return new LyricsProvider() {
            public String id() {
                return "test";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) {
                token.check();
                return results.apply(query);
            }
        };
    }

    @Test
    void plainLyricsAreAvailableWithoutFabricatedTimestamps() {
        var d = document("歌の言葉\n第二行\nHello");
        assertTrue(d.available());
        assertFalse(d.synchronizedLyrics());
        assertEquals(3, d.plain().size());
        assertEquals(-1, d.timed().current(60));
        assertEquals("und", d.language());
        assertEquals("ja", LyricsDocument.normalizeLanguage("jpn"));
        assertEquals("zh-Hant", LyricsDocument.normalizeLanguage("zh_Hant"));
        assertEquals("und", LyricsDocument.normalizeLanguage("../en"));
    }

    @Test
    void recordingVersionsDurationAndArtistPreventUnsafeAutomaticMatches() {
        assertTrue(LyricsMatcher.reliable(new LyricsProvider.Query("Ｓｏｎｇ (Official Audio)", "artist", "", 181), document("Text")));
        for (var query : List.of(new LyricsProvider.Query("Song live", "Artist", "", 180), new LyricsProvider.Query("Song", "Different", "", 180),
            new LyricsProvider.Query("Song", "", "", 180), new LyricsProvider.Query("Song", "Artist", "", 190)))
            assertFalse(LyricsMatcher.reliable(query, document("Text")));
        assertNotEquals(LyricsMatcher.normalized("Song remix"), LyricsMatcher.normalized("Song"));
    }

    @Test
    void lrclibExactMatchUsesFixedEndpointAndDoesNotFetchArbitraryLyricsUrls() throws Exception {
        var requests = new ArrayList<java.net.URI>();
        try (var p = new LrclibProvider((uri, token) -> {
            requests.add(uri);
            return new LrclibProvider.Reply(200, "{\"id\":42,\"trackName\":\"Song\",\"artistName\":\"Artist\",\"duration\":180,\"syncedLyrics\":\"[00:01]Words\",\"lyricsfile\":\"https://evil.test/token\"}", "");
        })) {
            var result = p.search(new LyricsProvider.Query("Song", "Artist", "Album", 180), new ProcessRunner.Token());
            assertEquals(1, requests.size());
            assertEquals("lrclib.net", requests.getFirst().getHost());
            assertTrue(result.getFirst().reliable());
            assertEquals("[00:01]Words", result.getFirst().document().text());
        }
    }

    @Test
    void lrclibOmitsUnsupportedDurationsInsteadOfFailingTheLookup() throws Exception {
        for (double duration : new double[]{0, .4, 3601, Double.NaN, Double.POSITIVE_INFINITY}) {
            try (var p = new LrclibProvider((uri, token) -> {
                assertFalse(uri.getQuery().contains("duration="));
                return new LrclibProvider.Reply(404, "", "");
            })) {
                assertTrue(p.search(new LyricsProvider.Query("Song", "Artist", "", duration), new ProcessRunner.Token()).isEmpty());
            }
        }
    }

    @Test
    void defaultAutomationKeepsExplicitlyDisabledPreferencesAndCleansOnlyTitleDecorations() {
        var defaults = new com.darkmusic.client.config.Settings();
        assertFalse(defaults.lyricsEnabled);
        assertFalse(defaults.lyricsOnline || defaults.lyricsLibraryLookup);
        assertTrue(defaults.lyricsAutomaticLookup && defaults.lyricsDownloadLookup);
        var old = com.darkmusic.client.config.ConfigStore.JSON.fromJson("{\"lyricsOnline\":false,\"lyricsAutomaticLookup\":false}", com.darkmusic.client.config.Settings.class);
        assertFalse(old.lyricsOnline || old.lyricsAutomaticLookup);
        var track = new Track("root", "file", Path.of("file"), "Artist - Song (Official Lyric Video)", "Artist", "", 180, 0, 0, "");
        assertEquals("Song", LyricsProvider.Query.of(track).title());
        var live = new Track("root", "file", Path.of("file"), "Artist - Song (Live)", "Artist", "", 180, 0, 0, "");
        assertEquals("Song (Live)", LyricsProvider.Query.of(live).title());
    }

    @Test
    void rejectedOrFailedExactEndpointsStillAllowSearchAndCooldownKeepsItsActualCategory() throws Exception {
        for (int status : List.of(400, 500)) {
            var calls = new AtomicInteger();
            try (var p = new LrclibProvider((uri, token) -> calls.incrementAndGet() == 1
                ? new LrclibProvider.Reply(status, "not json", "")
                : new LrclibProvider.Reply(200, "[{\"id\":42,\"trackName\":\"Song\",\"artistName\":\"Artist\",\"duration\":180,\"plainLyrics\":\"Words\"}]", ""))) {
                assertEquals(1, p.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token()).size());
                assertEquals(2, calls.get());
            }
        }
        var calls = new AtomicInteger();
        try (var p = new LrclibProvider((uri, token) -> {
            calls.incrementAndGet();
            return new LrclibProvider.Reply(503, "server busy", "120");
        })) {
            for (int i = 0; i < 2; i++) {
                var failure = assertThrows(LrclibProvider.Failure.class, () -> p.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token()));
                assertEquals(LrclibProvider.Problem.PROVIDER, failure.problem);
                assertEquals(503, failure.status);
                assertTrue(failure.retryAfterMillis > 100_000);
            }
            assertEquals(1, calls.get());
        }
    }

    @Test
    void automaticFailuresRetryAfterBackoffWithoutRepeatedTickRequests() throws Exception {
        Track track = identity("automatic-retry");
        var now = new java.util.concurrent.atomic.AtomicLong();
        var calls = new AtomicInteger();
        var provider = new LyricsProvider() {
            public String id() {
                return "automatic-retry";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                if (calls.incrementAndGet() == 1)
                    throw new LrclibProvider.Failure(LrclibProvider.Problem.RATE_LIMITED, 429, 300_000);
                return List.of(new Candidate(document("Recovered"), true, "lyrics.match.metadata"));
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("retry"), provider, now::get)) {
            lookup.configure(true, true);
            lookup.load(track);
            lookup.automatic(track, false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.RATE_LIMITED);
            now.set(TimeUnit.SECONDS.toNanos(31));
            for (int i = 0; i < 1000; i++) lookup.automatic(track, false);
            assertEquals(1, calls.get());
            now.set(TimeUnit.SECONDS.toNanos(301));
            await(() -> {
                lookup.automatic(track, false);
                return lookup.choice(track).selected() != null;
            });
            assertEquals(2, calls.get());
        }
    }

    @Test
    void automaticRetryUsesIncreasingDelaysAndEquivalentCatalogRecordsCanBeApplied() throws Exception {
        Track failed = identity("limited-retry"), duplicate = identity("equivalent-records");
        var now = new java.util.concurrent.atomic.AtomicLong();
        var calls = new AtomicInteger();
        var failing = new LyricsProvider() {
            public String id() {
                return "limited";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                calls.incrementAndGet();
                throw new LrclibProvider.Failure(LrclibProvider.Problem.NETWORK);
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("limited"), failing, now::get)) {
            lookup.configure(true, true);
            lookup.load(failed);
            for (int i = 1; i <= 3; i++) {
                now.set(TimeUnit.SECONDS.toNanos(i * 1000L));
                int expected = i;
                await(() -> {
                    lookup.automatic(failed, false);
                    return calls.get() == expected && lookup.snapshot(failed).status() == LyricsLookup.Status.NETWORK;
                });
            }
            now.set(TimeUnit.SECONDS.toNanos(3001));
            for (int i = 0; i < 1000; i++) lookup.automatic(failed, false);
            assertEquals(3, calls.get());
            now.set(TimeUnit.SECONDS.toNanos(3271));
            await(() -> {
                lookup.tickRetries();
                return calls.get() == 4 && lookup.snapshot(failed).status() == LyricsLookup.Status.NETWORK;
            });
        }
        var other = LyricsDocument.of("lrclib", "43", "Song", "Artist", "Different Album", 180, "und", LyricsDocument.Variant.UNKNOWN, false, false, "[00:01]Same words");
        try (var lookup = new LyricsLookup(temp.resolve("equivalent"), provider(q -> List.of(
            new LyricsProvider.Candidate(document("[00:01]Same words"), true, "lyrics.match.metadata"),
            new LyricsProvider.Candidate(other, true, "lyrics.match.metadata"))))) {
            lookup.configure(true, true);
            lookup.load(duplicate);
            lookup.automatic(duplicate, false);
            await(() -> lookup.choice(duplicate).selected() != null);
            assertEquals("[00:01]Same words", lookup.choice(duplicate).selected().text());
        }
    }

    @Test
    void manualSearchCacheFeedsAutomaticLookupAfterRestartWithoutAnotherRequest() throws Exception {
        Track track = identity("shared-cache");
        var calls = new AtomicInteger();
        var p = provider(q -> {
            calls.incrementAndGet();
            return List.of(new LyricsProvider.Candidate(document("Cached words"), true, "lyrics.match.metadata"));
        });
        Path prefs = temp.resolve("shared");
        try (var lookup = new LyricsLookup(prefs, p)) {
            lookup.configure(true, true);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.READY);
        }
        try (var lookup = new LyricsLookup(prefs, p)) {
            lookup.configure(true, true);
            lookup.load(track);
            lookup.automatic(track, false);
            await(() -> lookup.choice(track).selected() != null);
            assertEquals("Cached words", lookup.choice(track).selected().text());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void slowSourceCaptionsDoNotBlockOtherProviderLookupsOrOverwriteLocalLyrics() throws Exception {
        Track a = identity("slow-caption"), originalB = identity("provider-continues");
        Track b = new Track(originalB.rootId(), originalB.relativePath(), originalB.file(), "Other Song", originalB.artist(), originalB.album(), originalB.duration(), originalB.size(), originalB.modified(), "");
        com.darkmusic.client.library.SourceLinks.record(a.file(), "https://www.youtube.com/watch?v=abcdefghijk");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var lookup = new LyricsLookup(temp.resolve("separate-workers"), provider(q -> calls.incrementAndGet() == 1 ? List.of()
            : List.of(new LyricsProvider.Candidate(document("Provider words"), true, "lyrics.match.metadata"))))) {
            lookup.configure(true, true);
            lookup.captions((track, preferences, generated, token) -> {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
                token.check();
                return document("Source words");
            });
            lookup.load(a);
            lookup.load(b);
            lookup.automatic(a, false);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            lookup.automatic(b, false);
            await(() -> lookup.choice(b).selected() != null);
            Files.writeString(com.darkmusic.client.download.DownloadLyrics.sidecar(a.file()), "[00:01]User lyrics");
            release.countDown();
            await(() -> lookup.snapshot(a).status() == LyricsLookup.Status.READY);
            assertNull(lookup.choice(a).selected());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void missingExactRecordSearchesAndRetainsAmbiguousPlainAndInstrumentalResults() throws Exception {
        try (var p = new LrclibProvider((uri, token) -> uri.getPath().endsWith("/get") ? new LrclibProvider.Reply(404, "", "") : new LrclibProvider.Reply(200,
            "[{\"id\":1,\"trackName\":\"Song\",\"artistName\":\"Other\",\"duration\":180,\"plainLyrics\":\"Words\"},{\"id\":2,\"trackName\":\"Song\",\"artistName\":\"Artist\",\"duration\":180,\"instrumental\":true}]", ""))) {
            var found = p.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token());
            assertEquals(2, found.size());
            assertTrue(found.stream().anyMatch(c -> c.document().instrumental()));
            assertTrue(found.stream().anyMatch(c -> !c.document().synchronizedLyrics() && c.document().text().equals("Words")));
        }
    }

    @Test
    void providerFailuresAreNotNoLyricsAndCancelledTokensNeverRequest() throws Exception {
        for (int code : List.of(429, 503, 500))
            try (var p = new LrclibProvider((uri, token) -> new LrclibProvider.Reply(code, "", "3"))) {
                var failure = assertThrows(LrclibProvider.Failure.class, () -> p.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token()));
                assertEquals(code == 429 ? LrclibProvider.Problem.RATE_LIMITED : LrclibProvider.Problem.PROVIDER, failure.problem);
            }
        try (var p = new LrclibProvider((uri, token) -> {
            throw new java.net.SocketTimeoutException();
        })) {
            assertEquals(LrclibProvider.Problem.NETWORK, assertThrows(LrclibProvider.Failure.class, () -> p.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token())).problem);
        }
        var cancelled = new ProcessRunner.Token();
        cancelled.cancel();
        try (var p = new LrclibProvider((uri, token) -> {
            fail("Unexpected network request");
            return null;
        })) {
            assertThrows(CancellationException.class, () -> p.search(new LyricsProvider.Query("Song", "Artist", "", 180), cancelled));
        }
    }

    @Test
    void malformedAndOversizedRepliesAreRejected() throws Exception {
        for (String body : List.of("<html>error", "x".repeat(2_000_001)))
            try (var p = new LrclibProvider((uri, token) -> new LrclibProvider.Reply(200, body, ""))) {
                assertEquals(LrclibProvider.Problem.INVALID, assertThrows(LrclibProvider.Failure.class, () -> p.search(new LyricsProvider.Query("Song", "Artist", "", 180), new ProcessRunner.Token())).problem);
            }
    }

    @Test
    void lookupRequiresConsentAndNeverReplacesPinnedLyrics() throws Exception {
        Track track = identity("one");
        var calls = new AtomicInteger();
        try (var lookup = new LyricsLookup(temp.resolve("prefs"), provider(q -> {
            calls.incrementAndGet();
            return List.of(new LyricsProvider.Candidate(document("Online"), true, "lyrics.match.metadata"));
        }))) {
            lookup.search(track, LyricsProvider.Query.of(track), false);
            assertEquals(LyricsLookup.Status.DISABLED, lookup.snapshot(track).status());
            assertEquals(0, calls.get());
            lookup.select(track, document("User choice")).get(5, TimeUnit.SECONDS);
            lookup.configure(true, true);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.READY);
            assertEquals("User choice", lookup.choice(track).selected().text());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void choicesOffsetsAndRenameSurviveRestartAndCacheSurvivesRestart() throws Exception {
        Track before = identity("before"), after = identity("after");
        Path prefs = temp.resolve("prefs");
        var calls = new AtomicInteger();
        LyricsProvider provider = provider(q -> {
            calls.incrementAndGet();
            return List.of(new LyricsProvider.Candidate(document("Words"), true, "lyrics.match.metadata"));
        });
        try (var lookup = new LyricsLookup(prefs, provider)) {
            lookup.configure(true, true);
            lookup.search(before, LyricsProvider.Query.of(before), false);
            await(() -> lookup.snapshot(before).status() == LyricsLookup.Status.READY);
            lookup.select(before, document("Pinned")).get();
            lookup.offset(before, 1200).get();
            lookup.language(before, "ja").get();
            lookup.renamed(before, after);
            await(() -> lookup.choice(after).selected() != null);
        }
        try (var lookup = new LyricsLookup(prefs, provider)) {
            lookup.load(after);
            assertEquals("Pinned", lookup.choice(after).selected().text());
            assertEquals(1200, lookup.choice(after).offset());
            assertEquals("ja", lookup.choice(after).language());
            lookup.configure(true, true);
            lookup.search(before, LyricsProvider.Query.of(before), false);
            await(() -> lookup.snapshot(before).status() == LyricsLookup.Status.READY);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void cancelledLateResultsAndChangedFilesCannotBecomeCurrent() throws Exception {
        Track track = identity("race");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        LyricsProvider slow = new LyricsProvider() {
            public String id() {
                return "slow";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) throws Exception {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
                return List.of(new Candidate(document("Late"), true, "lyrics.match.metadata"));
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("prefs"), slow)) {
            lookup.configure(true, true);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            lookup.cancel(track);
            release.countDown();
            assertEquals(LyricsLookup.Status.CANCELLED, lookup.snapshot(track).status());
            assertNull(lookup.choice(track).selected());
        }
        Files.writeString(track.file(), "changed identity");
        try (var lookup = new LyricsLookup(temp.resolve("other"), provider(q -> {
            fail("Changed files must not be queried");
            return List.of();
        }))) {
            lookup.configure(true, true);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.CANCELLED);
        }
    }

    @Test
    void safeSavePreservesExistingAndReplacementCanRestorePrevious() throws Exception {
        Track track = identity("save");
        Path lrc = com.darkmusic.client.download.DownloadLyrics.sidecar(track.file());
        Files.writeString(lrc, "[00:01]Original");
        byte[] audio = Files.readAllBytes(track.file());
        try (var lookup = new LyricsLookup(temp.resolve("prefs"), provider(q -> List.of()))) {
            assertThrows(ExecutionException.class, () -> lookup.save(track, document("[00:02]New"), false).get());
            assertEquals("[00:01]Original", Files.readString(lrc));
            lookup.save(track, document("[00:02]New"), true).get();
            assertEquals("[00:02]New", Files.readString(lrc));
            lookup.restore(track).get();
            assertEquals("[00:01]Original", Files.readString(lrc));
            assertArrayEquals(audio, Files.readAllBytes(track.file()));
        }
    }

    @Test
    void plainLocalSidecarRemainsAvailableAndOffsetIsAppliedOnceToPlaybackClock() throws Exception {
        Track track = identity("local");
        Path lrc = com.darkmusic.client.download.DownloadLyrics.sidecar(track.file());
        Files.writeString(lrc, "[00:01]First\n[00:03]Second");
        try (var service = new LyricsService(temp.resolve("prefs"))) {
            service.request(track);
            await(() -> !service.get(track).lines().isEmpty());
            service.lookup.offset(track, 1000).get();
            assertEquals(-1, service.get(track).current(1.5));
            assertEquals(0, service.get(track).current(2));
            assertSame(service.get(track), service.get(track));
            service.lookup.offset(track, -500).get();
            assertEquals(0, service.get(track).current(.5));
            service.lookup.select(track, document("Plain words")).get();
            assertTrue(service.document(track).available());
            assertTrue(service.get(track).lines().isEmpty());
        }
    }

    @Test
    void negativeCacheIsReusedButExplicitRefreshRetriesAndClearKeepsChoices() throws Exception {
        Track track = identity("missing");
        var calls = new AtomicInteger();
        try (var lookup = new LyricsLookup(temp.resolve("prefs"), provider(q -> {
            calls.incrementAndGet();
            return List.of();
        }))) {
            lookup.configure(true, true);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.NOT_FOUND);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.NOT_FOUND);
            assertEquals(1, calls.get());
            lookup.search(track, LyricsProvider.Query.of(track), true);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.NOT_FOUND);
            assertEquals(2, calls.get());
            lookup.select(track, document("Pinned")).get();
            lookup.clearCache().get();
            assertEquals("Pinned", lookup.choice(track).selected().text());
        }
    }

    @Test
    void writingSystemsDoNotPretendToIdentifySharedLanguages() {
        assertEquals(LyricsScript.HAN, LyricsScript.detect("音樂世界"));
        assertEquals(LyricsScript.JAPANESE, LyricsScript.detect("音楽の歌"));
        assertEquals(LyricsScript.KOREAN, LyricsScript.detect("노래"));
        assertEquals(LyricsScript.LATIN, LyricsScript.detect("Bonjour hello"));
        assertEquals(LyricsScript.MIXED, LyricsScript.detect("歌の言葉 hello"));
        assertEquals("und", document("音樂世界").language());
    }

    @Test
    void rapidOffsetsRemainLiveAndPersistWhileNetworkLookupIsBlocked() throws Exception {
        Track track = identity("live");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var p = new LyricsProvider() {
            public String id() {
                return "blocked";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
                token.check();
                return List.of();
            }
        };
        Path prefs = temp.resolve("prefs");
        try (var lookup = new LyricsLookup(prefs, p)) {
            lookup.configure(true, true);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> last = null;
            for (int i = 0; i <= 1000; i++) {
                last = lookup.offset(track, i);
                assertEquals(i, lookup.choice(track).offset());
            }
            last.get(2, TimeUnit.SECONDS);
            lookup.select(track, document("Pinned while searching")).get(2, TimeUnit.SECONDS);
            assertEquals("Pinned while searching", lookup.choice(track).selected().text());
            lookup.cancel(track);
            release.countDown();
        }
        try (var lookup = new LyricsLookup(prefs, provider(q -> List.of()))) {
            lookup.load(track);
            assertEquals(1000, lookup.choice(track).offset());
        }
    }

    @Test
    void automaticLookupAppliesOneReliableMatchButHoldsAmbiguousResults() throws Exception {
        Track a = identity("automatic"), b = identity("ambiguous");
        try (var lookup = new LyricsLookup(temp.resolve("prefs"), provider(q -> q.title().equals("Song") ? List.of(new LyricsProvider.Candidate(document("Words"), true, "lyrics.match.metadata")) : List.of()))) {
            lookup.configure(true, true);
            lookup.load(a);
            lookup.automatic(a, false);
            await(() -> lookup.choice(a).selected() != null);
            assertEquals("Words", lookup.choice(a).selected().text());
        }
        try (var lookup = new LyricsLookup(temp.resolve("other"), provider(q -> List.of(new LyricsProvider.Candidate(document("A"), false, "lyrics.match.metadata"), new LyricsProvider.Candidate(document("B"), false, "lyrics.match.metadata"))))) {
            lookup.configure(true, true);
            lookup.load(b);
            lookup.automatic(b, false);
            await(() -> lookup.snapshot(b).status() == LyricsLookup.Status.AMBIGUOUS);
            assertNull(lookup.choice(b).selected());
        }
    }

    @Test
    void transientFailuresAreNotPersistedAsMissingAndOriginalFilesStayUntouched() throws Exception {
        Track track = identity("transient");
        var calls = new AtomicInteger();
        var p = new LyricsProvider() {
            public String id() {
                return "retry";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) throws Exception {
                if (calls.incrementAndGet() == 1) throw new LrclibProvider.Failure(LrclibProvider.Problem.NETWORK);
                return List.of(new Candidate(document("Recovered"), true, "lyrics.match.metadata"));
            }
        };
        byte[] original = Files.readAllBytes(track.file());
        try (var lookup = new LyricsLookup(temp.resolve("prefs"), p)) {
            lookup.configure(true, true);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.NETWORK);
            lookup.search(track, LyricsProvider.Query.of(track), false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.READY);
            assertEquals(2, calls.get());
        }
        assertArrayEquals(original, Files.readAllBytes(track.file()));
    }

    @Test
    void plainImportAndMalformedSavedRecordsDoNotDestroyTheCurrentSelection() throws Exception {
        Track track = identity("import");
        Path prefs = temp.resolve("prefs"), lyrics = temp.resolve("words.txt");
        Files.writeString(lyrics, "歌の言葉\n第二行");
        try (var lookup = new LyricsLookup(prefs, provider(q -> List.of()))) {
            lookup.importFile(track, lyrics).get();
            assertEquals("import", lookup.choice(track).selected().provider());
            assertFalse(lookup.choice(track).selected().synchronizedLyrics());
            assertThrows(ExecutionException.class, () -> lookup.importFile(track, temp.resolve("missing.txt")).get());
            assertEquals("import", lookup.choice(track).selected().provider());
        }
        try (var files = Files.list(prefs.resolve("tracks"))) {
            Files.writeString(files.findFirst().orElseThrow(), "invalid JSON");
        }
        try (var lookup = new LyricsLookup(prefs, provider(q -> List.of()))) {
            lookup.load(track);
            assertNull(lookup.choice(track).selected());
        }
    }

    @Test
    void autogeneratedCaptionsAreExplicitAndRemainLabeled() throws Exception {
        var metadata = com.google.gson.JsonParser.parseString("{\"automatic_captions\":{\"ja\":[{\"kind\":\"asr\"}],\"en\":[{\"is_translated\":true}]}}").getAsJsonObject();
        assertEquals("", com.darkmusic.client.download.LyricsLanguages.select(metadata, List.of("auto")));
        assertEquals("ja", com.darkmusic.client.download.LyricsLanguages.select(metadata, List.of("auto"), true));
        var runner = new ProcessRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, java.time.Duration timeout, int max, java.util.function.Consumer<String> line) throws java.io.IOException {
                assertTrue(args.contains("--skip-download"));
                assertTrue(args.contains("--write-auto-subs"));
                assertTrue(args.contains("--no-write-subs"));
                Files.writeString(dir.resolve("lyrics.ja.lrc"), "[00:01]Caption fixture");
                return new Result(0, "");
            }
        };
        var result = com.darkmusic.client.download.DownloadLyrics.fetch(runner,
            new com.darkmusic.client.download.Toolchain(temp.resolve("yt-dlp"), temp.resolve("ffmpeg"), temp.resolve("ffprobe"), "", ""),
            new com.darkmusic.client.download.DownloadModels.Entry(1, "Song", "https://example.com/song", "test", "song", true), metadata, temp, new ProcessRunner.Token(), List.of("auto"), true);
        var d = com.darkmusic.client.download.DownloadLyrics.document(result);
        assertTrue(d.generated());
        assertEquals("ja", d.language());
        assertEquals("captions", d.provider());
    }

    @Test
    void perTrackLanguageAndGeneratedConsentReachOnlyExplicitCaptionSearch() throws Exception {
        Track track = identity("captions");
        var calls = new AtomicInteger();
        try (var lookup = new LyricsLookup(temp.resolve("prefs"), provider(q -> List.of()))) {
            lookup.configure(true, true);
            lookup.language(track, "jpn").get();
            lookup.captions((song, preferences, generated, token) -> {
                calls.incrementAndGet();
                assertEquals("ja", preferences);
                assertTrue(generated);
                return document("Caption fixture");
            });
            lookup.source(track, "auto, en", true);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.AMBIGUOUS);
            assertEquals(1, calls.get());
            assertNull(lookup.choice(track).selected());
        }
    }

    @Test
    void cancellingACompletedLookupStillPreventsItsQueuedAutomaticSave() throws Exception {
        Track track = identity("queued-save");
        Path prefs = temp.resolve("prefs");
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var lookup = new LyricsLookup(prefs, provider(q -> List.of(new LyricsProvider.Candidate(document("Late save"), true, "lyrics.match.metadata"))))) {
            lookup.configure(true, true);
            lookup.load(track);
            var field = LyricsLookup.class.getDeclaredField("storage");
            field.setAccessible(true);
            ((ExecutorService) field.get(lookup)).execute(() -> {
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            lookup.automatic(track, false);
            await(() -> lookup.snapshot(track).status() == LyricsLookup.Status.READY);
            var keepLocal = lookup.select(track, null);
            release.countDown();
            keepLocal.get(2, TimeUnit.SECONDS);
            assertNull(lookup.choice(track).selected());
        } finally {
            release.countDown();
        }
        try (var lookup = new LyricsLookup(prefs, provider(q -> List.of()))) {
            lookup.load(track);
            assertNull(lookup.choice(track).selected());
        }
    }

    @Test
    void newLocalLyricsOverrideAnAutomaticMatchButNotAnExplicitPin() throws Exception {
        Track track = identity("priority");
        try (var service = new LyricsService(temp.resolve("prefs"), provider(q -> List.of(new LyricsProvider.Candidate(document("Automatic"), true, "lyrics.match.metadata"))))) {
            service.request(track);
            await(() -> service.inspected(track));
            service.lookup.configure(true, true);
            service.lookup.automatic(track, false);
            await(() -> service.lookup.choice(track).selected() != null);
            assertFalse(service.lookup.choice(track).pinned());
            Files.writeString(com.darkmusic.client.download.DownloadLyrics.sidecar(track.file()), "[00:01]Local");
            service.enabled(false);
            service.enabled(true);
            service.request(track);
            await(() -> service.document(track).provider().equals("sidecar"));
            service.lookup.select(track, document("Pinned")).get();
            assertEquals("Pinned", service.document(track).text());
            assertTrue(service.lookup.choice(track).pinned());
        }
    }

}
