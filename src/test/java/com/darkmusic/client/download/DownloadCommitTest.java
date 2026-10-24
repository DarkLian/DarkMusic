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

    @Test
    void failedProvenanceRemainsRepairableWithoutDownloadingAgain() throws Exception {
        String id = "Youtube:abcdefghijk:original";
        Path incoming = Files.writeString(temp.resolve("incoming.txt"), "Disposable receipt fixture");
        Path target = temp.resolve("saved.txt");
        DownloadCommit.prepare(incoming, target, id, "https://youtu.be/abcdefghijk");
        Files.move(incoming, target);
        Path provenance = Files.createDirectory(temp.resolve(".darkmusic-sources.json"));
        assertFalse(com.darkmusic.client.library.SourceLinks.record(target, "https://youtu.be/abcdefghijk", id));
        var pipeline = new DownloadPipeline(new ProcessRunner(), temp.resolve("data"));
        assertFalse(pipeline.repairBookkeeping(temp, id));
        assertTrue(DownloadCommit.pending(temp, id));
        assertEquals("Disposable receipt fixture", Files.readString(target));
        Files.delete(provenance);
        assertTrue(pipeline.repairBookkeeping(temp, id));
        assertFalse(DownloadCommit.pending(temp, id));
        assertTrue(com.darkmusic.client.library.SourceLinks.matchesArchive(target, id));
    }

    @Test
    void expectedHashPreventsBindingAReplacementDuringRepair() throws Exception {
        Path audio = Files.writeString(temp.resolve("replaced.txt"), "expected audio identity");
        String expected = com.darkmusic.client.library.SourceLinks.contentHash(audio);
        Files.writeString(audio, "external replacement");
        assertFalse(com.darkmusic.client.library.SourceLinks.record(audio, "https://youtu.be/abcdefghijk", "Youtube:abcdefghijk", expected));
        assertEquals("", com.darkmusic.client.library.SourceLinks.read(audio));
        assertEquals("external replacement", Files.readString(audio));
    }

}
