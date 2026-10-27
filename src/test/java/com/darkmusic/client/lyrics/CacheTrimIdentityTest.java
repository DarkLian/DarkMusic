package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CacheTrimIdentityTest {
    @TempDir
    Path temp;

    @Test
    void trimDoesNotDeleteAResultRefreshedAfterEnumeration() throws Exception {
        var provider = new LyricsProvider() {
            public String id() {
                return "offline";
            }

            public List<Candidate> search(Query q, ProcessRunner.Token t) {
                return List.of();
            }
        };
        try (var service = new LyricsService(temp.resolve("prefs"), provider, System::nanoTime)) {
            Path file = Files.writeString(temp.resolve("cache.json"), "old cache fixture");
            var old = LyricsResultCache.file(file);
            ConfigStore.atomicWrite(file, "newer, refreshed cache fixture");
            assertFalse(service.lookup.results.discard(old));
            assertEquals("newer, refreshed cache fixture", Files.readString(file));
            assertTrue(service.lookup.results.discard(LyricsResultCache.file(file)));
            assertFalse(Files.exists(file));
        }
    }
}
