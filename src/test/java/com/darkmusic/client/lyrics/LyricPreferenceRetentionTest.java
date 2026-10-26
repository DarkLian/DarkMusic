package com.darkmusic.client.lyrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import com.darkmusic.client.library.Track;

import static org.junit.jupiter.api.Assertions.*;

class LyricPreferenceRetentionTest {
    @TempDir
    Path temp;

    @Test
    void unregisteringAFolderDoesNotEraseItsManualChoices() throws Exception {
        Path file = Files.writeString(temp.resolve("identity.dat"), "Disposable identity fixture");
        var track = new Track("removed-root", "identity.dat", file, "T", "A", "", 2, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
        var provider = new LyricsProvider() {
            public String id() {
                return "offline";
            }

            public List<Candidate> search(Query q, com.darkmusic.client.download.ProcessRunner.Token t) {
                return List.of();
            }
        };
        Path prefs = temp.resolve("prefs");
        var doc = LyricsDocument.of("test", "1", "T", "A", "", 2, "ja", LyricsDocument.Variant.ORIGINAL, false, false, "[00:01]Words");
        try (var service = new LyricsService(prefs, provider, System::nanoTime)) {
            service.lookup.select(track, doc).get();
            service.lookup.offset(track, 750).get();
            service.lookup.prune(List.of(), Set.of("other-root"), Set.of("other-root"));
        }
        try (var service = new LyricsService(prefs, provider, System::nanoTime)) {
            service.lookup.load(track);
            assertEquals(750, service.lookup.choice(track).offset());
            assertNotNull(service.lookup.choice(track).selected());
        }
    }

    @Test
    void inlineSegmentsPreserveTextAndConsecutiveTagsStillRepeatWholeLines() {
        var parsed = Lrc.parse("[00:01.00]Hello [00:02.00]world\n[00:03][00:04]Repeat");
        assertEquals(List.of(new Lrc.Line(1000, "Hello"), new Lrc.Line(2000, "world"), new Lrc.Line(3000, "Repeat"), new Lrc.Line(4000, "Repeat")), parsed.lines());
    }
}
