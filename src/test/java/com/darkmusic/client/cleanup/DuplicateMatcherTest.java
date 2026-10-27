package com.darkmusic.client.cleanup;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateMatcherTest {
    @Test
    void finalIncrementalSnapshotRetainsFingerprintOnlyDiscoveryAndEvidence() {
        var token = new ProcessRunner.Token();
        var session = new DuplicateMatcher.Session(DuplicatePolicy.BALANCED, token);
        var a = entry("first.flac", "Alpha", "One", 180, "", "", null);
        var b = entry("second.mp3", "Unrelated title", "Two", 180, "", "", null);
        session.add(a);
        session.add(b);
        assertTrue(session.snapshot().pairs().isEmpty());
        int[] fingerprint = new Random(19).ints(160).toArray();
        a = entry("first.flac", "Alpha", "One", 180, "", "", fingerprint);
        b = entry("second.mp3", "Unrelated title", "Two", 180, "", "", fingerprint);
        session.update(0, a);
        session.update(1, b);
        var expected = new DuplicateMatcher().match(List.of(a, b), DuplicatePolicy.BALANCED, token, pairs -> {
        });
        assertEquals(expected.pairs(), session.snapshot().pairs());
        assertEquals(1, expected.pairs().size());
    }

    static DuplicateMatcher.Entry entry(String id, String title, String artist, double duration, String hash, String source, int[] print) {
        return new DuplicateMatcher.Entry(new Track("r", id, Path.of(id), title, artist, "", duration, 100, 1, ""), id, hash, source, print);
    }

    @Test
    void independentSignalsAndVersionLabels() {
        var a = entry("a.flac", "Song (Official Audio)", "Artist", 180, "abc", "", null);
        var b = entry("other.mp3", "Entirely different", "Someone else", 99, "abc", "", null);
        assertTrue(DuplicateMatcher.compare(a, b, DuplicatePolicy.STRICT).evidence().contains(DuplicateResult.Evidence.EXACT));
        b = entry("other.mp3", "Ｓｏｎｇ", "Different artist", 183, "", "", null);
        assertNotNull(DuplicateMatcher.compare(a, b, DuplicatePolicy.BALANCED));
        assertNull(DuplicateMatcher.compare(a, b, DuplicatePolicy.STRICT));
        b = entry("live.mp3", "Song Live", "Artist", 183, "", "", null);
        assertEquals(DuplicateResult.Strength.VERSIONS, DuplicateMatcher.compare(a, b, DuplicatePolicy.LOOSE).strength());
        assertNull(DuplicateMatcher.compare(a, entry("unrelated", "Nothing alike", "Artist", 180, "", "", null), DuplicatePolicy.LOOSE));
        assertEquals("作品 現場", DuplicateMatcher.normalize("作品【現場】.FLAC"));
    }

    @Test
    void sourceIdentityAndPairwiseEvidenceAreNotTransitive() {
        String one = "https://www.youtube.com/watch?v=abcDEFG_123&t=3", two = "https://youtu.be/abcDEFG_123?si=x";
        assertEquals(DuplicateMatcher.sourceId(one), DuplicateMatcher.sourceId(two));
        assertEquals(DuplicateMatcher.sourceId(one), DuplicateMatcher.sourceId("https://music.youtube.com/watch?v=abcDEFG_123"));
        assertNotEquals(DuplicateMatcher.sourceId("https://www.bilibili.com/video/BV12345?p=1"), DuplicateMatcher.sourceId("https://www.bilibili.com/video/BV12345?p=2"));
        var a = entry("a", "Alpha", "A", 10, "", one, null);
        var b = entry("b", "Beta", "B", 20, "abc", two, null);
        var c = entry("c", "Gamma", "C", 30, "abc", "", null);
        var matches = new DuplicateMatcher().match(List.of(a, b, c), DuplicatePolicy.STRICT, new ProcessRunner.Token(), v -> {
        });
        assertEquals(2, matches.pairs().size());
        assertNull(DuplicateMatcher.compare(a, c, DuplicatePolicy.LOOSE));
    }

    @Test
    void indexedAudioRetrievesUnrelatedTagsAndOffsetOverlap() {
        var random = new Random(5);
        int[] a = random.ints(160).toArray();
        int[] b = Arrays.copyOfRange(a, 20, 140);
        assertTrue(FingerprintService.compare(a, b, DuplicatePolicy.BALANCED).matched());
        var matches = new DuplicateMatcher().match(List.of(entry("a", "One", "A", 70, "", "", a), entry("b", "Unrelated", "B", 55, "", "", b)), DuplicatePolicy.BALANCED, new ProcessRunner.Token(), v -> {
        });
        assertEquals(1, matches.pairs().size());
        assertTrue(matches.pairs().getFirst().evidence().contains(DuplicateResult.Evidence.OVERLAP));
        assertFalse(FingerprintService.compare(a, random.ints(160).toArray(), DuplicatePolicy.LOOSE).matched());
        assertFalse(FingerprintService.compare(new int[160], new int[160], DuplicatePolicy.LOOSE).matched());
    }

    @Test
    void hundredThousandTracksUseBoundedRetrievalAndCancellation() {
        var entries = new ArrayList<DuplicateMatcher.Entry>();
        for (int i = 0; i < 100_000; i++)
            entries.add(entry("file" + i, "UniqueRecording" + i, "A", 100 + i % 200, "", "", null));
        long start = System.nanoTime();
        var matches = new DuplicateMatcher().match(entries, DuplicatePolicy.BALANCED, new ProcessRunner.Token(), v -> {
        });
        System.out.println("CLEANUP_100K: comparisons=" + matches.comparisons() + ", ms=" + (System.nanoTime() - start) / 1_000_000);
        assertTrue(matches.comparisons() < entries.size() * 16L);
        assertTrue(matches.pairs().isEmpty());
        var cancelled = new ProcessRunner.Token();
        cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> new DuplicateMatcher().match(entries, DuplicatePolicy.LOOSE, cancelled, v -> {
        }));
    }
}
