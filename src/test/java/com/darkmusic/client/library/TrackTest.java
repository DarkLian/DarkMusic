package com.darkmusic.client.library;

import com.darkmusic.client.config.ConfigStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackTest {
    @Test
    void normalizedSearchAndIdsAreCachedWithoutChangingPersistedMetadata() {
        var track = new Track("root", "Ｆｏｌｄｅｒ/song.mp3", null, "Beyoncé ガ", "Artist", "Album", 5, 10, 20, "");
        assertTrue(track.matches("BEYONCE"));
        assertTrue(track.matches("folder"));
        assertTrue(track.matches("ガ"));
        assertFalse(track.matches("カ"));
        assertSame(track.id(), track.id());
        String json = ConfigStore.JSON.toJson(track);
        assertFalse(json.contains("searchKey"));
        assertFalse(json.contains("cachedId"));
        assertEquals(track, ConfigStore.JSON.fromJson(json, Track.class));
        assertTrue(ConfigStore.JSON.fromJson(json, Track.class).matches("beyonce"));
    }
}
