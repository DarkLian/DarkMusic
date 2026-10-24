package com.darkmusic.client.lyrics;

import com.darkmusic.client.download.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit opt-in connectivity check using public metadata, never the private music collection.
 */
class LrclibLiveTest {
    @Test
    @EnabledIfSystemProperty(named = "darkmusic.test.lyricsNetwork", matches = "true")
    void documentedEndpointWorksThroughTheProductionBoundedHttpClient() throws Exception {
        try (var provider = new LrclibProvider()) {
            var candidates = provider.search(new LyricsProvider.Query("Never Gonna Give You Up", "Rick Astley", "", 213), new ProcessRunner.Token());
            assertFalse(candidates.isEmpty());
            assertTrue(candidates.stream().anyMatch(c -> c.document().available()));
            assertTrue(candidates.stream().allMatch(c -> c.document().provider().equals("lrclib")));
        }
    }
}
