package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.download.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LyricsSafetyTest {
    @Test
    void songLanguageTagsBreakAutoTiesWithoutReplacingExplicitPreferences() {
        assertEquals("ja, auto", com.darkmusic.client.download.LyricsLanguages.preferSongLanguage("auto", "jpn"));
        assertEquals("en, zh-Hant", com.darkmusic.client.download.LyricsLanguages.preferSongLanguage("en, zh-Hant", "ja"));
        assertEquals("auto", com.darkmusic.client.download.LyricsLanguages.preferSongLanguage("auto", "und"));
    }

    @TempDir
    Path temp;

    private Track identity(String name) throws Exception {
        Path path = Files.writeString(temp.resolve(name), "Disposable lyric identity");
        return new Track("root", name, path, name, "Artist", "", 180, Files.size(path), Files.getLastModifiedTime(path).toMillis(), "");
    }

    @Test
    void explicitLegacyEncodingAndCompactLegacyDocumentsRemainReadable() throws Exception {
        String words = "[00:01.00]繁體中文歌詞";
        byte[] big5 = words.getBytes(java.nio.charset.Charset.forName("Big5"));
        assertEquals(words, LyricsService.decode(big5, "Big5"));
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> LyricsService.decode(big5));
        var d = document(words);
        String legacy = new com.google.gson.Gson().toJson(d);
        assertTrue(legacy.contains("\"timed\""));
        assertEquals(d, ConfigStore.JSON.fromJson(legacy, LyricsDocument.class));
    }

    @Test
    void ambiguousProviderResultsStillTryCaptionsAndKeepManualAlternatives() throws Exception {
        Track track = identity("ambiguous.dat");
        var weak = document("Uncertain provider version");
        var caption = LyricsDocument.of("captions", "video", "Title", "Artist", "", 180, "ja", LyricsDocument.Variant.ORIGINAL, false, false, "[00:01]Caption text");
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "test";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) {
                return List.of(new Candidate(weak, false, "lyrics.match.metadata"));
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("fallback"), provider)) {
            lookup.configure(true, true);
            lookup.captions((t, p, g, token) -> caption);
            lookup.load(track);
            lookup.automatic(track, false);
            com.darkmusic.client.testing.Await.until(java.time.Duration.ofSeconds(5), () -> caption.equals(lookup.choice(track).selected()), "Caption fallback applies a reliable result");
            assertEquals(2, lookup.snapshot(track).candidates().size());
            assertTrue(lookup.snapshot(track).candidates().stream().anyMatch(c -> c.document().equals(weak) && !c.reliable()));
        }
    }

    @Test
    void emptyProviderResultIsNotFinalWhileCaptionFallbackIsRunning() throws Exception {
        Track track = identity("pending-caption.dat");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "empty-provider";
            }

            public List<Candidate> search(Query query, ProcessRunner.Token token) {
                return List.of();
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("pending-caption"), provider)) {
            lookup.configure(true, true);
            lookup.captions((t, p, g, token) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Caption test timed out");
                return LyricsDocument.EMPTY;
            });
            lookup.load(track);
            lookup.automatic(track, false);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(LyricsLookup.Status.SEARCHING, lookup.snapshot(track).status());
                assertEquals(LyricsInfo.State.UNKNOWN, LyricsInfo.lookup(lookup.snapshot(track).status()).state());
            } finally {
                release.countDown();
            }
            com.darkmusic.client.testing.Await.until(java.time.Duration.ofSeconds(5),
                () -> lookup.snapshot(track).status() == LyricsLookup.Status.NOT_FOUND, "Final miss follows completed caption fallback");
            assertEquals(LyricsInfo.State.UNAVAILABLE, LyricsInfo.lookup(lookup.snapshot(track).status()).state());
            assertNull(lookup.choice(track).selected());
        } finally {
            release.countDown();
        }
    }

    @Test
    void interactiveSearchRunsBeforeQueuedBackgroundLookups() throws Exception {
        var first = identity("first.dat");
        var second = identity("second.dat");
        var manual = identity("manual.dat");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "test";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) throws Exception {
                order.add(q.title());
                if (q.title().equals(first.title())) {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return List.of();
            }
        };
        try (var lookup = new LyricsLookup(temp.resolve("priority"), provider)) {
            lookup.configure(true, true);
            for (var track : List.of(first, second, manual)) lookup.load(track);
            lookup.automatic(first, false);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lookup.automatic(second, false);
            lookup.search(manual, LyricsProvider.Query.of(manual), true);
            release.countDown();
            com.darkmusic.client.testing.Await.until(java.time.Duration.ofSeconds(5), () -> order.size() == 3, "Requests complete");
            assertEquals(List.of(first.title(), manual.title(), second.title()), List.copyOf(order));
        } finally {
            release.countDown();
        }
    }

    private LyricsDocument document(String text) {
        return LyricsDocument.of("test", "1", "Title", "Artist", "", 1, "und", LyricsDocument.Variant.UNKNOWN, false, false, text);
    }

    @Test
    void upgradeRequiresConsentAndDoesNotEnableLibrarySweep() {
        var s = ConfigStore.JSON.fromJson("{\"lyricsEnabled\":true,\"lyricsOnline\":true,\"lyricsLibraryLookup\":true}", Settings.class);
        s.validate();
        assertTrue(s.lyricsEnabled);
        assertFalse(s.lyricsOnline);
        assertFalse(s.lyricsLibraryLookup);
        s.lyricsConsentVersion = 1;
        s.lyricsOnline = true;
        s.validate();
        assertTrue(s.lyricsOnline);
        assertFalse(s.lyricsLibraryLookup);
    }

    @Test
    void compactDocumentsRebuildTimingAndStripGameFormatting() {
        var d = document("[00:01.00]Hello \u00a7cworld");
        String json = ConfigStore.JSON.toJson(d);
        assertFalse(json.contains("\"timed\""));
        assertFalse(json.contains("\"plain\""));
        assertFalse(json.contains("\"script\""));
        assertEquals(d, ConfigStore.JSON.fromJson(json, LyricsDocument.class));
        assertEquals("Hello world", d.timed().lines().getFirst().text());
        assertThrows(IllegalArgumentException.class, () -> document("binary\u0000data"));
    }

    @Test
    void firstBackupSurvivesRepeatedReplacementsAndRestoreClearsWrongAttribution() throws Exception {
        Path audio = temp.resolve("identity.dat");
        var p = LyricsFiles.companions(audio);
        Files.writeString(p.getFirst(), "original");
        LyricsFiles.save(audio, document("first"), true);
        LyricsFiles.save(audio, document("second"), true);
        assertEquals("original", Files.readString(p.get(2)));
        LyricsFiles.restore(audio);
        assertEquals("original", Files.readString(p.getFirst()));
        assertFalse(Files.exists(p.get(1)));
        assertEquals("original", Files.readString(p.get(2)));
    }

    @Test
    void rollbackAndRestartRecoveryPreserveOriginalBytes() throws Exception {
        Path audio = temp.resolve("identity.dat");
        var p = LyricsFiles.companions(audio);
        Files.writeString(p.getFirst(), "original");
        var desired = new LinkedHashMap<Path, byte[]>();
        desired.put(p.getFirst(), "new".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        desired.put(p.get(1), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> LyricsFiles.transaction(audio, desired, () -> {
            throw new IllegalStateException("failure");
        }));
        assertEquals("original", Files.readString(p.getFirst()));
        assertFalse(Files.exists(p.get(1)));
        assertThrows(AssertionError.class, () -> LyricsFiles.transaction(audio, desired, () -> {
            throw new AssertionError("simulated interruption");
        }));
        LyricsFiles.recover(audio);
        assertEquals("original", Files.readString(p.getFirst()));
        assertFalse(Files.exists(p.get(1)));
    }

    @Test
    void collisionsAndUnexpectedRecoveryEditsAreNeverOverwritten() throws Exception {
        Path audio = temp.resolve("identity.dat");
        var p = LyricsFiles.companions(audio);
        Files.writeString(p.getFirst(), "original");
        assertThrows(FileAlreadyExistsException.class, () -> LyricsFiles.save(audio, document("new"), false));
        var desired = new LinkedHashMap<Path, byte[]>();
        desired.put(p.getFirst(), "new".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(AssertionError.class, () -> LyricsFiles.transaction(audio, desired, () -> {
            throw new AssertionError();
        }));
        Files.writeString(p.getFirst(), "external edit");
        assertThrows(java.io.IOException.class, () -> LyricsFiles.recover(audio));
        assertEquals("external edit", Files.readString(p.getFirst()));
    }

    @Test
    void staleProvenanceCannotAttachToAReplacementEvenWithSameSizeAndTime() throws Exception {
        Path audio = temp.resolve("identity.dat");
        Files.writeString(audio, "original");
        var time = Files.getLastModifiedTime(audio);
        SourceLinks.record(audio, "https://www.youtube.com/watch?v=abcdefghijk", "Youtube:abcdefghijk");
        assertTrue(SourceLinks.matchesArchive(audio, "Youtube:abcdefghijk"));
        Files.writeString(audio, "replaced");
        Files.setLastModifiedTime(audio, time);
        assertEquals("", SourceLinks.read(audio));
        assertFalse(SourceLinks.matchesArchive(audio, "Youtube:abcdefghijk"));
    }

    @Test
    void sourceRenameRemovesOldMappingOnlyAfterSuccessfulMove() throws Exception {
        Path old = temp.resolve("old.dat"), next = temp.resolve("new.dat");
        Files.writeString(old, "identity");
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        SourceLinks.record(old, url, "Youtube:abcdefghijk");
        String verified = SourceLinks.read(old);
        Files.move(old, next);
        SourceLinks.renamed(old, next, verified);
        Files.writeString(old, "unrelated");
        assertEquals("", SourceLinks.read(old));
        assertEquals(url, SourceLinks.read(next));
        assertTrue(SourceLinks.matchesArchive(next, "Youtube:abcdefghijk"));
    }

    @Test
    void automaticCaptionUrlsRejectUntrustedSitesAndPrivateNetworks() throws Exception {
        for (String url : List.of("http://localhost/a", "http://192.168.1.1/a", "https://evil.example/a", "https://youtube.com.evil.example/watch?v=abcdefghijk", "https://www.youtube.com:8443/watch?v=abcdefghijk", "https://user:pass@youtube.com/watch?v=abcdefghijk"))
            assertEquals("", CaptionUrls.automatic(url));
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", CaptionUrls.automatic("https://youtu.be/abcdefghijk?token=secret"));
        for (String ip : List.of("127.0.0.1", "192.168.1.1", "10.0.0.1", "169.254.1.1", "100.64.0.1", "::1", "fc00::1", "fe80::1"))
            assertFalse(CaptionUrls.publicAddress(InetAddress.getByName(ip)), ip);
        assertTrue(CaptionUrls.publicAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void excessiveRetryAfterIsBounded() throws Exception {
        try (var provider = new LrclibProvider((uri, token) -> new LrclibProvider.Reply(429, "", "99999999999"))) {
            var failure = assertThrows(LrclibProvider.Failure.class, () -> provider.search(new LyricsProvider.Query("Song", "Artist", "", 1), new ProcessRunner.Token()));
            assertEquals(TimeUnit.HOURS.toMillis(24), failure.retryAfterMillis);
        }
    }

    @Test
    void externalRetaggingRetainsPreferencesAndOffersSelectionForReview() throws Exception {
        Path file = temp.resolve("identity.dat");
        Files.writeString(file, "before");
        Track before = new Track("root", "identity.dat", file, "Song", "Artist", "", 1, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
        Path settings = temp.resolve("prefs");
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "test";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token t) {
                return List.of();
            }
        };
        try (var lookup = new LyricsLookup(settings, provider)) {
            lookup.select(before, document("words")).get(5, TimeUnit.SECONDS);
            lookup.offset(before, 500).get(5, TimeUnit.SECONDS);
            lookup.language(before, "ja").get(5, TimeUnit.SECONDS);
        }
        Files.writeString(file, "retagged");
        Track after = new Track("root", "identity.dat", file, "Song", "Artist", "", 1, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
        try (var lookup = new LyricsLookup(settings, provider)) {
            lookup.load(after);
            assertEquals(500, lookup.choice(after).offset());
            assertEquals("ja", lookup.choice(after).language());
            assertNull(lookup.choice(after).selected());
            assertEquals(LyricsLookup.Status.AMBIGUOUS, lookup.snapshot(after).status());
            assertEquals("words", lookup.snapshot(after).candidates().getFirst().document().text());
        }
    }

    @Test
    void failedSavedChoiceReadRetriesWithoutRestartWithBoundedBackoff() throws Exception {
        Track track = identity("read-retry.dat");
        Path settings = temp.resolve("read-retry");
        LyricsProvider provider = new LyricsProvider() {
            public String id() {
                return "test";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token token) {
                return List.of();
            }
        };
        var selected = document("Saved choice");
        try (var lookup = new LyricsLookup(settings, provider)) {
            lookup.select(track, selected).get(5, TimeUnit.SECONDS);
            lookup.offset(track, 650).get(5, TimeUnit.SECONDS);
        }
        Path path;
        try (var files = Files.list(settings.resolve("tracks"))) {
            path = files.findFirst().orElseThrow();
        }
        String saved = Files.readString(path);
        Files.writeString(path, "{invalid");
        var now = new java.util.concurrent.atomic.AtomicLong();
        try (var lookup = new LyricsLookup(settings, provider, now::get)) {
            lookup.load(track);
            assertNull(lookup.choice(track).selected());
            Files.writeString(path, saved);
            lookup.load(track);
            assertNull(lookup.choice(track).selected(), "Avoid repeatedly reading a failing file every tick");
            now.set(TimeUnit.SECONDS.toNanos(5));
            lookup.load(track);
            assertEquals(selected, lookup.choice(track).selected());
            assertEquals(650, lookup.choice(track).offset());
        }
    }

}
