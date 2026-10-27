package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class LyricsFilesTest {
    @TempDir
    Path temp;

    @Test
    void rollbackAndRestartRecoveryPreserveOriginalBytes() throws Exception {
        Path audio = temp.resolve("identity.dat");
        var p = LyricsFiles.companions(audio);
        Files.writeString(p.getFirst(), "original");
        var desired = new LinkedHashMap<Path, byte[]>();
        desired.put(p.getFirst(), "new".getBytes(StandardCharsets.UTF_8));
        desired.put(p.get(1), "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> LyricsFiles.transaction(audio, desired, () -> {
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
        assertThrows(FileAlreadyExistsException.class, () -> LyricsFiles.save(audio, "new", "{}", false));
        var desired = new LinkedHashMap<Path, byte[]>();
        desired.put(p.getFirst(), "new".getBytes(StandardCharsets.UTF_8));
        assertThrows(AssertionError.class, () -> LyricsFiles.transaction(audio, desired, () -> {
            throw new AssertionError();
        }));
        Files.writeString(p.getFirst(), "external edit");
        assertThrows(IOException.class, () -> LyricsFiles.recover(audio));
        assertEquals("external edit", Files.readString(p.getFirst()));
    }
}
