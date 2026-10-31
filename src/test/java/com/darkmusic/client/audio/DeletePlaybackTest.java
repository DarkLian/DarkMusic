package com.darkmusic.client.audio;

import com.darkmusic.client.playlist.PlaylistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DeletePlaybackTest {
    @TempDir
    Path temp;

    @Test
    void removedTracksLeaveQueueHistoryAndShuffleWithoutChangingSourceOrPausedPosition() {
        for (boolean shuffle : new boolean[]{false, true}) {
            var audio = new PlaybackTest.Fake();
            var player = new PlaybackController(audio, message -> fail(message));
            var tracks = new PlaybackTest().tracks();
            var source = new PlaybackController.Source("Playlists", "evening", "", "root");
            player.play(tracks, tracks.getFirst(), source);
            player.shuffle(shuffle);
            audio.state = new AudioBackend.State(tracks.getFirst(), AudioBackend.Status.PAUSED, 12, 60, "");
            var resume = player.suspendForEdit();
            var removed = Set.of(tracks.get(1).id(), tracks.get(3).id());
            player.finishDeletion(resume, removed);
            assertEquals(tracks.getFirst(), player.state().track());
            assertEquals(AudioBackend.Status.PAUSED, player.state().status());
            assertEquals(12, audio.lastSeek);
            assertEquals(source, player.source());
            for (int i = 0; i < 20; i++) {
                player.next();
                assertFalse(removed.contains(player.state().track().id()));
            }
            for (int i = 0; i < 20; i++) {
                player.previous();
                assertFalse(removed.contains(player.state().track().id()));
            }
        }
    }

    @Test
    void deletingCurrentFileStopsAndFailedDeletionResumesWithoutUndoingLaterUserStop() {
        var audio = new PlaybackTest.Fake();
        var player = new PlaybackController(audio, message -> fail(message));
        var tracks = new PlaybackTest().tracks();
        player.play(tracks, tracks.getFirst());
        player.finishDeletion(player.suspendForEdit(), Set.of());
        assertEquals(AudioBackend.Status.PLAYING, player.state().status());
        player.finishDeletion(player.suspendForEdit(), Set.of(tracks.getFirst().id()));
        assertEquals(AudioBackend.Status.STOPPED, player.state().status());
        player.next();
        assertNotEquals(tracks.getFirst(), player.state().track());
        var resume = player.suspendForEdit();
        player.stop();
        player.finishDeletion(resume, Set.of());
        assertEquals(AudioBackend.Status.STOPPED, player.state().status());
    }

    @Test
    void successfulFilesAreRemovedFromAllPlaylistsAndFavoritesButFailedFilesRemain() {
        var tracks = new PlaybackTest().tracks();
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new PlaylistStore(temp, io, message -> fail(message));
            store.loaded(List.of());
            String first = store.create("First"), second = store.create("Second");
            store.addAll(first, tracks);
            store.addAll(second, tracks);
            store.toggleFavorite(tracks.getFirst());
            store.toggleFavorite(tracks.get(1));
            store.removeTracks(Set.of(tracks.getFirst().id()));
            assertFalse(store.favorite(tracks.getFirst()));
            assertTrue(store.favorite(tracks.get(1)));
            assertEquals(tracks.subList(1, tracks.size()), store.resolve(first, tracks));
            assertEquals(tracks.subList(1, tracks.size()), store.resolve(second, tracks));
        }
    }
}
