package com.darkmusic.client;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.testing.Tracks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Playlist changes wait until the playlists have loaded and for no media operation: an import, edit, deletion or
 * restore reconciles the playlists itself.
 */
class PlaylistActionsTest {
    @TempDir
    Path temp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ClientState state = new ClientState();
    private PlaylistStore store;
    private boolean ready;
    private final PlaylistActions actions = new PlaylistActions(new PlaylistActions.Inputs() {
        public boolean playlistsReady() {
            return ready;
        }

        public PlaylistStore playlists() {
            return store;
        }
    }, state);
    private final List<Track> tracks = List.of(Tracks.of("r", "a.opus", Path.of("a.opus")),
        Tracks.of("r", "b.opus", Path.of("b.opus")), Tracks.of("r", "c.opus", Path.of("c.opus")));

    @AfterEach
    void close() throws InterruptedException {
        // Windows cannot delete the temporary folder while a save still has a file in it open.
        io.shutdown();
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
    }

    private void refusesEverything(String id) {
        assertEquals("", actions.create("New"));
        assertFalse(actions.rename(id, "Renamed"));
        assertFalse(actions.delete(id));
        assertFalse(actions.addAll(id, tracks));
        assertFalse(actions.removeAll(id, Set.of(tracks.getFirst().id())));
        assertFalse(actions.moveBlock(id, 0, 1, 2));
        assertFalse(actions.toggleFavorite(tracks.getFirst()));
    }

    @Test
    void nothingChangesBeforeThePlaylistsLoad() {
        refusesEverything("any");
        store = new PlaylistStore(temp, io, text -> {
        });
        refusesEverything("any");
        store.loaded(List.of());
        assertEquals(1, store.all().size(), "only Favorites");
    }

    @Test
    void nothingChangesWhileAMediaOperationOwnsTheFiles() {
        store = new PlaylistStore(temp, io, text -> {
        });
        store.loaded(List.of());
        ready = true;
        String id = actions.create("Mix");
        assertFalse(id.isEmpty());
        assertTrue(actions.addAll(id, tracks));
        state.mediaBusy = true;
        refusesEverything(id);
        assertEquals(tracks, store.resolve(id, tracks));
        assertFalse(store.favorite(tracks.getFirst()));
    }

    @Test
    void eachChangeReachesTheStore() {
        store = new PlaylistStore(temp, io, text -> {
        });
        store.loaded(List.of());
        ready = true;
        String id = actions.create("Mix");
        assertTrue(actions.addAll(id, tracks));
        assertTrue(actions.moveBlock(id, 0, 1, 2));
        assertEquals(List.of(tracks.get(1), tracks.get(2), tracks.getFirst()), store.resolve(id, tracks));
        assertTrue(actions.removeAll(id, Set.of(tracks.get(1).id())));
        assertEquals(List.of(tracks.get(2), tracks.getFirst()), store.resolve(id, tracks));
        assertTrue(actions.rename(id, "Renamed"));
        assertEquals("Renamed", store.displayName(store.get(id)));
        assertTrue(actions.toggleFavorite(tracks.getFirst()));
        assertTrue(store.favorite(tracks.getFirst()));
        assertTrue(actions.delete(id));
        assertNull(store.get(id));
    }
}
