package com.darkmusic.client.download;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class DownloadCommitTest {
    @TempDir
    Path temp;

    @Test
    void interruptedCommitRepairsHistoryButNeverClaimsAReusedFilename() throws Exception {
        Path incoming = Files.writeString(temp.resolve("incoming.txt"), "Disposable commit identity");
        Path target = temp.resolve("saved.txt");
        String id = "Youtube:abcdefghijk", url = "https://youtu.be/abcdefghijk";
        DownloadCommit.prepare(incoming, target, id, url);
        Files.move(incoming, target);
        var archive = new JsonObject();
        assertEquals(target, DownloadCommit.recover(temp, id, archive));
        assertEquals("saved.txt", archive.get(id).getAsString());
        assertTrue(com.darkmusic.client.library.SourceLinks.matchesArchive(target, id));
        incoming = Files.writeString(temp.resolve("next.txt"), "Different pending identity");
        String second = "Youtube:lmnopqrstuv";
        DownloadCommit.prepare(incoming, temp.resolve("reused.txt"), second, "https://youtu.be/lmnopqrstuv");
        Files.writeString(temp.resolve("reused.txt"), "Unrelated existing file");
        assertNull(DownloadCommit.recover(temp, second, archive));
        assertFalse(archive.has(second));
        assertEquals("Unrelated existing file", Files.readString(temp.resolve("reused.txt")));
    }
}
