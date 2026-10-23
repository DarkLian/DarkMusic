package com.darkmusic.client.cleanup;

import com.darkmusic.client.download.*;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LibrarySourceIndexTest {
    @TempDir
    Path temp;

    @Test
    void refreshAndRemovalDoNotKeepADeletedOrUnregisteredSource() throws Exception {
        Path root = temp.resolve("r");
        var a = file(root, "before.txt");
        var entry = new DownloadModels.Entry(1, "Title", "https://youtu.be/abcDEFG_123", "Youtube", "abcDEFG_123", true);
        Files.writeString(root.resolve(".darkmusic-sources.json"), "{\"before.txt\":\"https://youtu.be/abcDEFG_123\"}");
        try (var index = new LibrarySourceIndex(temp.resolve("data"))) {
            index.update(List.of(a), Map.of(root, a.rootId()), true);
            index.settled().get(10, TimeUnit.SECONDS);
            assertEquals(1, index.verified(entry).size());
            Files.move(a.file(), root.resolve("after.txt"));
            var b = new Track(a.rootId(), "after.txt", root.resolve("after.txt"), a.title(), a.artist(), a.album(), a.duration(), a.size(), a.modified(), "");
            Files.writeString(root.resolve(".darkmusic-sources.json"), "{\"after.txt\":\"https://www.youtube.com/watch?v=abcDEFG_123\"}");
            index.update(List.of(b), Map.of(root, b.rootId()), true);
            index.settled().get(10, TimeUnit.SECONDS);
            assertEquals(b.id(), index.verified(entry).getFirst().track().id());
            index.update(List.of(), Map.of(), true);
            assertTrue(index.verified(entry).isEmpty());
            index.settled().get(10, TimeUnit.SECONDS);
            assertTrue(index.lookup(entry).confirmed().isEmpty());
        }
    }

    Track file(Path root, String name) throws Exception {
        Files.createDirectories(root);
        Path p = Files.writeString(root.resolve(name), "Disposable provenance fixture");
        return new Track(root.toString(), name, p, "A song", "", "", 180, Files.size(p), Files.getLastModifiedTime(p).toMillis(), "");
    }

    @Test
    void confirmsAcrossRootsRenamesDeletedArchivesAndOfflineFilesWithoutFilenameMatching() throws Exception {
        var root = temp.resolve("one");
        var other = temp.resolve("two");
        var a = file(root, "renamed.txt");
        var b = file(other, "other.txt");
        Files.writeString(root.resolve(".darkmusic-downloads.json"), "{\"Youtube:abcDEFG_123:original\":\"renamed.txt\",\"Youtube:deleted_123:original\":\"missing.txt\"}");
        Files.writeString(other.resolve(".darkmusic-sources.json"), "{\"other.txt\":\"https://youtu.be/abcDEFG_123\"}");
        var entry = new DownloadModels.Entry(1, "Different title", "https://www.youtube.com/watch?v=abcDEFG_123", "Youtube", "abcDEFG_123", true);
        try (var index = new LibrarySourceIndex(temp.resolve("data"))) {
            index.update(List.of(a, b), Map.of(root, root.toString(), other, other.toString()), true);
            index.settled().get(10, TimeUnit.SECONDS);
            assertEquals(2, index.verified(entry).size());
            assertTrue(index.lookup(entry).complete());
            assertTrue(index.verified(entry).stream().anyMatch(e -> e.sameFormat(DownloadEncoding.Format.ORIGINAL)));
            assertFalse(index.verified(entry).stream().anyMatch(e -> e.sameFormat(DownloadEncoding.Format.MP3)));
            Files.delete(a.file());
            assertEquals(1, index.verified(entry).size());
            index.deleted(Set.of(a.id()));
            assertEquals(1, index.lookup(entry).confirmed().size());
            Files.delete(b.file());
            assertTrue(index.verified(entry).isEmpty());
            index.update(List.of(a, b), Map.of(root, root.toString(), other, other.toString()), false);
            index.settled().get(10, TimeUnit.SECONDS);
            assertFalse(index.lookup(entry).complete());
            assertTrue(index.lookup(entry).confirmed().isEmpty());
        }
    }

    @Test
    void formatAwarenessAndFuzzyEvidenceRemainDifferentFromSourceIdentity() throws Exception {
        var a = file(temp.resolve("r"), "one.txt");
        var existing = new LibrarySourceIndex.Existing(a, "mp3", Set.of(DownloadEncoding.Format.MP3), "s");
        assertTrue(existing.sameFormat(DownloadEncoding.Format.MP3));
        assertFalse(existing.sameFormat(DownloadEncoding.Format.ORIGINAL));
        try (var index = new LibrarySourceIndex(temp.resolve("data"))) {
            index.evidence(List.of(new DuplicateResult("p", a, a, "s", "s", Set.of(DuplicateResult.Evidence.FILENAME), DuplicateResult.Strength.POSSIBLE)));
            var e = new DownloadModels.Entry(1, a.title(), "https://youtu.be/another_123", "Youtube", "another_123", true);
            assertTrue(index.lookup(e).confirmed().isEmpty());
            assertFalse(index.lookup(e).possible().isEmpty());
            assertTrue(index.verified(e).isEmpty());
        }
    }
}
