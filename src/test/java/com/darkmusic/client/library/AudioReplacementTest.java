package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AudioReplacementTest {
    @TempDir
    Path directory;

    @Test
    void failedRenameRollsBackDownloadHistoryBeforeAnotherDownloadCanReadIt() throws Exception {
        Path source = Files.writeString(directory.resolve("old.txt"), "original"), target = Files.writeString(directory.resolve("new.txt"), "collision");
        Path history = Files.writeString(directory.resolve(".darkmusic-downloads.json"), "{\"source:id\":\"old.txt\"}");
        assertThrows(IOException.class, () -> DownloadArchive.renameRecorded(source, target, () -> Files.move(source, target)));
        assertEquals("old.txt", com.google.gson.JsonParser.parseString(Files.readString(history)).getAsJsonObject().get("source:id").getAsString());
        assertEquals("original", Files.readString(source));
        assertEquals("collision", Files.readString(target));
    }

    @Test
    void failedPublicationPreservesOriginalAndDoesNotOverwriteUnknownPartialTarget() throws Exception {
        Path target = Files.writeString(directory.resolve("original.txt"), "original"), staged = Files.writeString(directory.resolve("edited.txt"), "edited"), backup = directory.resolve("backup.txt");
        assertThrows(AudioReplacement.RecoveryRequired.class, () -> AudioReplacement.replace(staged, target, backup, (from, to, options) -> {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test provider");
            Files.writeString(to, "partial write");
            throw new IOException("replacement interrupted");
        }));
        assertEquals("partial write", Files.readString(target));
        assertEquals("original", Files.readString(backup));
        assertTrue(Files.exists(staged));
    }

    @Test
    void replacementDoesNotRequireAtomicMoveSupport() throws Exception {
        Path target = Files.writeString(directory.resolve("original.txt"), "original"), staged = Files.writeString(directory.resolve("edited.txt"), "edited"), backup = directory.resolve("backup.txt");
        AudioReplacement.replace(staged, target, backup, (from, to, options) -> {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test provider");
            Files.move(from, to, options);
        });
        assertEquals("edited", Files.readString(target));
        assertEquals("original", Files.readString(backup));
        assertFalse(Files.exists(staged));
    }

    @Test
    void failedPublicationIntoAnEmptyTargetRestoresTheOriginal() throws Exception {
        Path target = Files.writeString(directory.resolve("original.txt"), "original");
        Path staged = Files.writeString(directory.resolve("edited.txt"), "edited");
        assertThrows(IOException.class, () -> AudioReplacement.replace(staged, target, directory.resolve("backup.txt"),
            (from, to, options) -> {
                throw new IOException("Move rejected without writing");
            }));
        assertEquals("original", Files.readString(target));
        assertEquals("edited", Files.readString(staged));
    }
}
