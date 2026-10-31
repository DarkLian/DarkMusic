package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceLinksTest {
    @TempDir
    Path temp;

    @Test
    void sourceLinksValidatePersistAndRecoverOlderYoutubeDownloads() throws Exception {
        Path audio = Files.writeString(temp.resolve("song.txt"), "Disposable provenance identity");
        for (String unsafe : List.of("file:///C:/private", "javascript:alert(1)", "https://user:secret@example.com", "not a URL"))
            assertEquals("", SourceLinks.safe(unsafe));
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        Files.writeString(temp.resolve(".darkmusic-downloads.json"), "{\"youtube:abcdefghijk:original\":\"song.txt\"}");
        assertEquals("", SourceLinks.read(audio), "Filename-only legacy history is unverified");
        assertEquals(url, SourceLinks.resolve(audio), "Legacy links remain available for explicit inspection");
        SourceLinks.record(audio, url);
        Path renamed = temp.resolve("renamed.txt");
        Files.move(audio, renamed);
        SourceLinks.renamed(audio, renamed, url);
        Files.delete(temp.resolve(".darkmusic-downloads.json"));
        assertEquals(url, SourceLinks.read(renamed));
        SourceLinks.record(renamed, "file:///invalid");
        assertEquals("", SourceLinks.read(renamed));
    }
}
