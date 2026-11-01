package com.darkmusic.client.library;

import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.lyrics.LyricWriteGate;
import com.darkmusic.client.text.Text;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Disposable documents exercise filesystem policy independently of audio decoding or native Trash.
 */
class DeletionCompanionTest {
    @TempDir
    Path temp;

    @Test
    void simulatedNullFileKeysRequireMatchingContentsEvenWithPreservedSizeAndTime() throws Exception {
        var t = track("null-key.dat");
        var before = Files.readAttributes(t.file(), BasicFileAttributes.class);
        var noKey = new BasicFileAttributes() {
            public FileTime lastModifiedTime() {
                return before.lastModifiedTime();
            }

            public FileTime lastAccessTime() {
                return before.lastAccessTime();
            }

            public FileTime creationTime() {
                return before.creationTime();
            }

            public boolean isRegularFile() {
                return true;
            }

            public boolean isDirectory() {
                return false;
            }

            public boolean isSymbolicLink() {
                return false;
            }

            public boolean isOther() {
                return false;
            }

            public long size() {
                return before.size();
            }

            public Object fileKey() {
                return null;
            }
        };
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(t.file())));
        var item = new AudioDeletion.Item(t, t.file(), null, hash);
        assertTrue(AudioDeletion.sameIdentity(item, noKey));
        Files.writeString(t.file(), "Changed identity!! ");
        Files.setLastModifiedTime(t.file(), before.lastModifiedTime());
        assertEquals(before.size(), Files.size(t.file()));
        assertFalse(AudioDeletion.sameIdentity(item, noKey));
        assertFalse(AudioDeletion.sameIdentity(new AudioDeletion.Item(t, t.file(), null), noKey));
    }

    private Track track(String name) throws Exception {
        Path p = Files.writeString(temp.resolve(name), "Disposable identity");
        return new Track("root", name, p, name, "", "", 0, Files.size(p), Files.getLastModifiedTime(p).toMillis(), "");
    }

    private AudioDeletion service(boolean falseAfterMove) {
        return new AudioDeletion(new AudioDeletion.Trash() {
            public boolean supported() {
                return true;
            }

            public boolean move(AudioDeletion.Item item) {
                try {
                    Files.move(item.file(), item.file().resolveSibling("trashed-" + item.file().getFileName()));
                    return !falseAfterMove;
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            }
        }, p -> true);
    }

    @Test
    void companionsAreKeptUnlessExplicitlyChosenAndFalseTrashResultIsRechecked() throws Exception {
        for (boolean chosen : List.of(false, true)) {
            var t = track(chosen ? "chosen.dat" : "kept.dat");
            var paths = LyricsFiles.companions(t.file());
            for (Path p : paths) Files.writeString(p, "User-owned lyric text");
            var service = service(false);
            var plan = service.prepare(Map.of(temp, "root"), List.of(t));
            assertEquals(3, plan.companions().size());
            var result = service.delete(plan.trashCompanions(chosen), AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
            });
            assertEquals(List.of(t), result.removed());
            assertTrue(result.companionFailures().isEmpty());
            for (Path p : paths) assertEquals(!chosen, Files.exists(p));
        }
        var t = track("false-result.dat");
        var service = service(true);
        var result = service.delete(service.prepare(Map.of(temp, "root"), List.of(t)), AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
        });
        assertEquals(List.of(t), result.removed());
        assertTrue(result.failed().isEmpty());
    }

    @Test
    void changedCompanionRefusesAudioDeletionBeforeAnyTrashMove() throws Exception {
        var t = track("changed.dat");
        Path lrc = LyricsFiles.companions(t.file()).getFirst();
        Files.writeString(lrc, "before");
        var service = service(false);
        var plan = service.prepare(Map.of(temp, "root"), List.of(t));
        Files.writeString(lrc, "external changes after confirmation");
        var result = service.delete(plan.trashCompanions(true), AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
        });
        assertTrue(result.removed().isEmpty());
        assertEquals(Text.get("library.darkmusic.file_busy"), result.failed().getFirst().message());
        assertTrue(Files.exists(t.file()));
        assertEquals("external changes after confirmation", Files.readString(lrc));
    }

    @Test
    void sharedLyricsAreNotOfferedForDeletionAndNewSiblingsAreRechecked() throws Exception {
        var mp3 = track("shared.mp3");
        Path lyric = LyricsFiles.companions(mp3.file()).getFirst();
        Files.writeString(lyric, "Shared user lyrics");
        Path sibling = Files.writeString(temp.resolve("shared.flac"), "Disposable sibling");
        var deletion = service(false);
        assertTrue(deletion.prepare(Map.of(temp, "root"), List.of(mp3)).companions().isEmpty());
        Files.delete(sibling);
        var plan = deletion.prepare(Map.of(temp, "root"), List.of(mp3)).trashCompanions(true);
        assertEquals(1, plan.companions().size());
        Files.writeString(sibling, "New sibling after confirmation");
        var result = deletion.delete(plan, AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
        });
        assertTrue(result.removed().isEmpty());
        assertEquals(Text.get("library.darkmusic.file_busy"), result.failed().getFirst().message());
        assertTrue(Files.exists(mp3.file()));
        assertEquals("Shared user lyrics", Files.readString(lyric));
        assertTrue(Files.exists(sibling));
    }

    @Test
    void sameSizeTimestampCompanionReplacementRetainsReviewedHash() throws Exception {
        var t = track("stable-companion.dat");
        Path lyric = LyricsFiles.companions(t.file()).getFirst();
        Files.writeString(lyric, "before");
        var service = service(false);
        var plan = service.prepare(Map.of(temp, "root"), List.of(t)).trashCompanions(true);
        assertEquals(SourceLinks.contentHash(lyric), plan.companions().getFirst().hash());
        var modified = Files.getLastModifiedTime(lyric);
        Files.writeString(lyric, "after!");
        Files.setLastModifiedTime(lyric, modified);
        var result = service.delete(plan, AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
        });
        assertTrue(result.removed().isEmpty());
        assertEquals(Text.get("library.darkmusic.file_busy"), result.failed().getFirst().message());
        assertTrue(Files.exists(t.file()));
        assertEquals("after!", Files.readString(lyric));
    }

    @Test
    void queuedSaveRequiresFreshCompanionConfirmationThenDeletesWithoutOrphans() throws Exception {
        var t = track("queued-save.dat");
        var deletion = service(false);
        var plan = deletion.prepare(Map.of(temp, "root"), List.of(t)).trashCompanions(true);
        assertTrue(plan.companions().isEmpty());
        try (var lookup = new LyricsLookup(temp.resolve("config")); var gate = new LyricWriteGate(lookup)) {
            var document = LyricsDocument.of("test", "", "", "", "", 1, "und",
                LyricsDocument.Variant.UNKNOWN, false, false, "[00:00.00]Queued lyric text");
            var saved = lookup.save(t, document, false);
            var settled = lookup.writesSettled();
            assertFalse(settled.isDone());
            gate.release();
            saved.get(5, TimeUnit.SECONDS);
            settled.get(5, TimeUnit.SECONDS);
            var stale = deletion.delete(plan, AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
            });
            assertTrue(stale.removed().isEmpty());
            assertEquals(Text.get("library.darkmusic.file_busy"), stale.failed().getFirst().message());
            assertTrue(Files.exists(t.file()));
            assertTrue(Files.exists(LyricsFiles.sidecar(t.file())));
            var confirmed = deletion.prepare(Map.of(temp, "root"), List.of(t)).trashCompanions(true);
            var removed = deletion.delete(confirmed, AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
            });
            assertEquals(List.of(t), removed.removed());
            assertTrue(removed.companionFailures().isEmpty());
            assertFalse(Files.exists(t.file()));
            for (Path path : LyricsFiles.companions(t.file())) assertFalse(Files.exists(path));
        }
    }

    @Test
    void missingConfirmedCompanionRefusesDeletion() throws Exception {
        var t = track("missing.dat");
        Path lrc = Files.writeString(LyricsFiles.sidecar(t.file()), "confirmed lyrics");
        var deletion = service(false);
        var plan = deletion.prepare(Map.of(temp, "root"), List.of(t)).trashCompanions(true);
        Files.delete(lrc);
        var result = deletion.delete(plan, AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
        });
        assertTrue(result.removed().isEmpty());
        assertEquals(Text.get("library.darkmusic.file_busy"), result.failed().getFirst().message());
        assertTrue(Files.exists(t.file()));
    }

}
