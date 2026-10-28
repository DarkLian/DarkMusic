package com.darkmusic.client.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class HashesTest {
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path temp;

    @Test
    void textBytesAndFilesShareOneLowercaseDigest() throws Exception {
        Path file = Files.writeString(temp.resolve("abc.txt"), "abc");
        assertEquals(ABC, Hashes.sha256("abc"));
        assertEquals(ABC, Hashes.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ABC, Hashes.sha256(file));
        assertEquals(ABC, Hashes.sha256(file, LinkOption.NOFOLLOW_LINKS));
        assertEquals(ABC, Hashes.sha256(file, new ProcessRunner.Token()));
    }

    @Test
    void cancelledTokenStopsAFileHash() throws Exception {
        Path file = Files.write(temp.resolve("large.bin"), new byte[200_000]);
        var token = new ProcessRunner.Token();
        token.cancel();
        assertThrows(CancellationException.class, () -> Hashes.sha256(file, token));
    }
}
