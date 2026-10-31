package com.darkmusic.client.playlist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistStoreTest {
    @TempDir
    Path temp;

    @Test
    void damagedPlaylistRetainsOtherPlaylistsAndValidEntries() throws Exception {
        Path file = temp.resolve("playlists.json");
        String original = "[{\"id\":\"a\",\"name\":\"Keep\",\"entries\":[{\"rootId\":\"r\",\"path\":\"song.mp3\"},{\"rootId\":\"r\",\"path\":\"../bad\"}]},{\"id\":\"b\",\"name\":\"Other\",\"entries\":[]},{\"id\":null,\"name\":\"Bad\",\"entries\":[]}]";
        Files.writeString(file, original);
        var warnings = new CopyOnWriteArrayList<String>();
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new PlaylistStore(temp, io, warnings::add);
            var loaded = io.submit(store::read).get();
            assertEquals(2, loaded.size());
            assertEquals(1, loaded.getFirst().entries().size());
            assertFalse(warnings.isEmpty());
            assertEquals(original, Files.readString(file));
            store.loaded(loaded);
            store.rename("a", "Recovered");
            io.submit(() -> {
            }).get();
            try (var paths = Files.list(temp)) {
                Path backup = paths.filter(f -> f.getFileName().toString().startsWith("playlists.invalid-")).findFirst().orElseThrow();
                assertEquals(original, Files.readString(backup));
            }
        }
    }

    @Test
    void cleanupRemovesOnlyConfirmedMissingFilesFromCurrentRootIncludingFavorites() throws Exception {
        Path root = Files.createDirectory(temp.resolve("music"));
        Path existing = Files.writeString(root.resolve("existing.txt"), "");
        var missing = new PlaylistStore.Entry("here", "deleted.opus");
        var offline = new PlaylistStore.Entry("other", "deleted.opus");
        var unindexed = new PlaylistStore.Entry("here", "existing.txt");
        var entries = List.of(missing, offline, unindexed);
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new PlaylistStore(temp, io, message -> fail(message));
            store.loaded(List.of(new PlaylistStore.Playlist(PlaylistStore.FAVORITES, "Favorite Music", entries),
                new PlaylistStore.Playlist("custom", "Custom", entries)));
            assertTrue(PlaylistStore.missing(temp.resolve("offline"), "here", store.all(), List.of()).isEmpty());
            var result = PlaylistStore.missing(root, "here", store.all(), List.of());
            assertEquals(Set.of(missing), result);
            store.removeMissing(result);
            for (var playlist : store.all()) assertEquals(List.of(offline, unindexed), playlist.entries());
            assertTrue(Files.exists(existing));
        }
    }
}
