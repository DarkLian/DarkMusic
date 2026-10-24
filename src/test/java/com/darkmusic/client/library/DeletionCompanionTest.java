package com.darkmusic.client.library;

import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.lyrics.LyricsFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
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
        var before = Files.readAttributes(t.file(), java.nio.file.attribute.BasicFileAttributes.class);
        var noKey = new java.nio.file.attribute.BasicFileAttributes() {
            public java.nio.file.attribute.FileTime lastModifiedTime() {
                return before.lastModifiedTime();
            }

            public java.nio.file.attribute.FileTime lastAccessTime() {
                return before.lastAccessTime();
            }

            public java.nio.file.attribute.FileTime creationTime() {
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
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(t.file())));
        var item = new AudioDeletion.Item(t, t.file(), null, hash);
        var matches = AudioDeletion.class.getDeclaredMethod("sameIdentity", AudioDeletion.Item.class, java.nio.file.attribute.BasicFileAttributes.class);
        matches.setAccessible(true);
        assertEquals(true, matches.invoke(null, item, noKey));
        Files.writeString(t.file(), "Changed identity!! ");
        Files.setLastModifiedTime(t.file(), before.lastModifiedTime());
        assertEquals(before.size(), Files.size(t.file()));
        assertEquals(false, matches.invoke(null, item, noKey));
        assertEquals(false, matches.invoke(null, new AudioDeletion.Item(t, t.file(), null), noKey));
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
    void changedCompanionIsPreservedAndReportedSeparatelyFromRemovedAudio() throws Exception {
        var t = track("changed.dat");
        Path lrc = LyricsFiles.companions(t.file()).getFirst();
        Files.writeString(lrc, "before");
        var service = service(false);
        var plan = service.prepare(Map.of(temp, "root"), List.of(t));
        Files.writeString(lrc, "external changes after confirmation");
        var result = service.delete(plan.trashCompanions(true), AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
        });
        assertEquals(List.of(t), result.removed());
        assertEquals(List.of(lrc), result.companionFailures());
        assertEquals("external changes after confirmation", Files.readString(lrc));
    }
}
