package com.darkmusic.client.cleanup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class FingerprintScratchTest {
    @TempDir
    Path temp;

    @Test
    void onlyOldRecognizableScratchIsRemoved() throws Exception {
        long now = System.currentTimeMillis();
        Path old = Files.writeString(temp.resolve("fingerprint-12345.pcm"), "Disposable scratch"), young = Files.writeString(temp.resolve("fingerprint-67890.pcm"), "Recent scratch"), other = Files.writeString(temp.resolve("recording.pcm"), "Keep");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(now - 2 * 86_400_000L));
        FingerprintService.pruneScratch(temp, now);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(young));
        assertTrue(Files.exists(other));
    }
}
