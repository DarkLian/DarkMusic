package com.darkmusic.client.audio;

import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class UpNextTest {
    static Track track(String root, String name) {
        return new Track(root, name, Path.of(root, name), name, "", "", 60, 0, 0, "");
    }

    static final class Fake implements AudioBackend {
        State state = new State(null, Status.STOPPED, 0, 0, "");
        Runnable ended;
        Consumer<String> failed;
        Prepared prepared;
        int opens;
        int promoteOnRead;

        record Prepared(Track track, Runnable started, Runnable ended, Consumer<String> failed, Transition gate) {
        }

        public State state() {
            if (promoteOnRead > 0 && --promoteOnRead == 0) promote(prepared, true);
            return state;
        }

        public void play(Track t, Runnable e, Consumer<String> f) {
            opens++;
            state = new State(t, Status.PLAYING, 0, t.duration(), "");
            ended = e;
            failed = f;
        }

        public void pause() {
            state = new State(state.track(), Status.PAUSED, state.position(), state.duration(), "");
        }

        public void resume() {
            state = new State(state.track(), Status.PLAYING, state.position(), state.duration(), "");
        }

        public void stop() {
            state = new State(state.track(), Status.STOPPED, 0, state.duration(), "");
        }

        public void seek(double p) {
            state = new State(state.track(), state.status(), p, state.duration(), "");
        }

        public void volume(double v) {
        }

        public void speed(double v) {
        }

        public void close() {
        }

        public void nextTrack(Track t, Runnable s, Runnable e, Consumer<String> f, Transition gate) {
            if (prepared != null && prepared.gate == gate && prepared.track.equals(t)) return;
            prepared = t == null ? null : new Prepared(t, s, e, f, gate);
        }

        public void nextTrack(Track t, Runnable s, Runnable e, Consumer<String> f) {
            prepared = null;
        }

        boolean promote(Prepared p, boolean delayed) {
            if (!p.gate.claim()) return false;
            state = new State(p.track, Status.PLAYING, 0, p.track.duration(), "");
            ended = p.ended;
            failed = p.failed;
            if (!delayed) p.started.run();
            return true;
        }
    }

    final Fake audio = new Fake();
    final List<String> warnings = new ArrayList<>();
    final PlaybackController player = new PlaybackController(audio, warnings::add);
    final List<Track> source = List.of(track("root", "A"), track("root", "B"), track("root", "C"));
    final Track x = track("other", "X"), y = track("other", "Y");
    final PlaybackController.Source origin = new PlaybackController.Source("Playlists", "evening", "", "root");

    void start() {
        player.play(source, source.getFirst(), origin);
    }

    /**
     * Drags one queued entry to the head of Up Next, the way the screen does.
     */
    boolean moveToFront(long id) {
        var ids = player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::id).toList();
        var order = new ArrayList<>(IntStream.range(0, ids.size()).boxed().toList());
        order.remove(Integer.valueOf(ids.indexOf(id)));
        order.addFirst(ids.indexOf(id));
        return player.reorderQueued(ids, order);
    }

    @Test
    void tailAndRejectedQueueEditsPreserveThePreparedTransition() {
        start();
        player.addToQueue(x);
        player.addToQueue(y);
        player.addToQueue(x);
        var ids = player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::id).toList();
        var prepared = audio.prepared;
        assertFalse(player.removeQueued(-1));
        assertFalse(player.reorderQueued(ids, List.of(0, 1, 2)));
        assertFalse(player.reorderQueued(ids, List.of(0, 1, 3)));
        assertSame(prepared, audio.prepared);
        assertTrue(player.reorderQueued(ids, List.of(0, 2, 1)));
        assertSame(prepared, audio.prepared);
        assertTrue(player.removeQueued(ids.get(1)));
        assertSame(prepared, audio.prepared);
        assertTrue(player.removeQueued(ids.getFirst()));
        assertNotSame(prepared, audio.prepared);
    }

    @Test
    void aPromotionDuringHeadInvalidationDoesNotRemoveTheFollowingEntry() {
        start();
        player.addToQueue(x);
        player.addToQueue(y);
        long head = player.upNextSnapshot().manual().getFirst().id();
        audio.promoteOnRead = 2;
        assertFalse(player.removeQueued(head));
        assertEquals(x, audio.state().track());
        assertEquals(List.of(y), player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::track).toList());
    }

    @Test
    void dragReordersStableOccurrencesAndRejectsStaleOrInvalidOrders() {
        start();
        player.addToQueue(x);
        player.addToQueue(x);
        player.addToQueue(y);
        var ids = player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::id).toList();
        assertTrue(player.reorderQueued(ids, List.of(2, 0, 1)));
        assertEquals(List.of(ids.get(2), ids.get(0), ids.get(1)), player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::id).toList());
        assertFalse(player.reorderQueued(ids, List.of(0, 1, 2)));
        var current = player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::id).toList();
        assertFalse(player.reorderQueued(current, List.of(0, 0, 2)));
        assertFalse(player.reorderQueued(current, List.of(0, 1, 3)));
        assertFalse(player.reorderQueued(current, Arrays.asList(0, 1, null)));
        player.next();
        assertEquals(y, audio.state.track());
        assertFalse(player.reorderQueued(current, List.of(2, 1, 0)));
        player.next();
        assertEquals(x, audio.state.track());
        player.next();
        assertEquals(x, audio.state.track());
        player.next();
        assertEquals(source.get(1), audio.state.track());
    }

    @Test
    void collectionDropCopiesAtInsertionWithoutChangingTheSource() {
        start();
        player.addToQueue(x);
        var before = player.upNextSnapshot().automatic();
        assertTrue(player.insertQueued(source.get(1), 0));
        assertEquals(before, player.upNextSnapshot().automatic());
        assertEquals(source.get(1), audio.prepared.track());
        player.next();
        assertEquals(source.get(1), audio.state.track());
        player.next();
        assertEquals(x, audio.state.track());
        player.next();
        assertEquals(source.get(1), audio.state.track());
        assertEquals(origin, player.source());
    }

    @Test
    void mostRecentPlayNextWinsThenOriginalCollectionResumesWithoutSkippingItsOwnOccurrences() {
        start();
        player.playNext(x);
        player.playNext(y);
        player.addToQueue(source.get(1));
        for (Track expected : List.of(y, x, source.get(1), source.get(1), source.get(2))) {
            player.next();
            assertEquals(expected, audio.state.track());
            assertEquals(origin, player.source());
        }
        audio.ended.run();
        assertEquals(AudioBackend.Status.STOPPED, audio.state.status());
    }

    @Test
    void appendDuplicatesHaveStableIdsAndCanBeMovedOrRemovedIndependently() {
        start();
        player.addToQueue(x);
        player.addToQueue(x);
        player.addToQueue(y);
        var queued = player.upNextSnapshot().manual();
        assertNotEquals(queued.get(0).id(), queued.get(1).id());
        assertTrue(player.removeQueued(queued.get(0).id()));
        assertEquals(queued.get(1).id(), player.upNextSnapshot().manual().getFirst().id());
        assertTrue(moveToFront(queued.get(2).id()));
        player.next();
        assertEquals(y, audio.state.track());
        player.next();
        assertEquals(x, audio.state.track());
    }

    @Test
    void clearStopAndNewSessionHaveDistinctSemantics() {
        start();
        player.addToQueue(x);
        player.stop();
        assertEquals(1, player.upNextSnapshot().manual().size());
        player.toggle();
        assertEquals(source.getFirst(), audio.state.track());
        player.clearQueued();
        assertEquals(source.getFirst(), audio.state.track());
        player.addToQueue(x);
        player.play(source, source.get(1), origin);
        assertTrue(player.upNextSnapshot().manual().isEmpty());
    }

    @Test
    void repeatAndManualNavigationPreserveTheSpecifiedPriorities() {
        for (var repeat : Repeat.values()) {
            player.repeat(repeat);
            player.play(source, source.getLast(), origin);
            player.addToQueue(x);
            audio.ended.run();
            assertEquals(repeat == Repeat.ONE ? source.getLast() : x, audio.state.track());
            if (repeat == Repeat.ONE) {
                assertEquals(1, player.upNextSnapshot().manual().size());
                player.next();
                assertEquals(x, audio.state.track());
                player.repeat(Repeat.OFF);
            }
            audio.ended.run();
            assertEquals(repeat == Repeat.ALL ? AudioBackend.Status.PLAYING : AudioBackend.Status.STOPPED, audio.state.status());
            if (repeat == Repeat.ALL) assertEquals(source.getFirst(), audio.state.track());
        }
    }

    @Test
    void previousTraversesActualManualHistoryWithoutReinsertingEntries() {
        start();
        player.addToQueue(x);
        player.addToQueue(y);
        player.next();
        player.next();
        player.next();
        for (Track expected : List.of(y, x, source.getFirst())) {
            player.previous();
            assertEquals(expected, audio.state.track());
            assertTrue(player.upNextSnapshot().manual().isEmpty());
        }
        player.previous();
        assertEquals(source.getLast(), audio.state.track());
    }

    @Test
    void shuffleDoesNotChangeManualOrderAndLargeSourcePreviewStaysBounded() {
        var huge = IntStream.range(0, 100000).mapToObj(i -> track("root", "Song" + i)).toList();
        player.play(huge, huge.getFirst());
        player.addToQueue(x);
        player.addToQueue(y);
        player.shuffle(true);
        assertEquals(List.of(x, y), player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::track).toList());
        assertEquals(25, player.upNextSnapshot().automatic().size());
        assertSame(player.upNextSnapshot(), player.upNextSnapshot());
        player.next();
        assertEquals(x, audio.state.track());
        player.shuffle(false);
        player.next();
        assertEquals(y, audio.state.track());
        player.next();
        assertEquals(huge.get(1), audio.state.track());
    }

    @Test
    void queueLimitsRejectRatherThanEvictAndIdleQueueDoesNotAutoplay() {
        for (int i = 0; i < 1000; i++) assertTrue(player.addToQueue(x));
        assertFalse(player.playNext(y));
        assertEquals(1000, player.upNextSnapshot().manual().size());
        assertEquals(0, audio.opens);
        assertEquals(1, warnings.size());
        player.toggle();
        assertEquals(x, audio.state.track());
        assertEquals(999, player.upNextSnapshot().manual().size());
    }

    @Test
    void failedManualEntriesAreConsumedAndDoNotLoopUnderRepeatAll() {
        player.repeat(Repeat.ALL);
        start();
        player.addToQueue(x);
        player.addToQueue(y);
        player.next();
        audio.failed.accept("missing");
        assertEquals(y, audio.state.track());
        audio.ended.run();
        assertEquals(source.get(1), audio.state.track());
        assertTrue(player.upNextSnapshot().manual().isEmpty());
        for (int i = 0; i < source.size(); i++) audio.failed.accept("missing");
        assertEquals(AudioBackend.Status.STOPPED, audio.state.status());
    }

    @Test
    void headMutationsInvalidatePreparedPromotionsIncludingDuplicateTracks() {
        start();
        var old = audio.prepared;
        player.playNext(x);
        assertFalse(audio.promote(old, false));
        assertEquals(source.getFirst(), audio.state.track());
        var first = audio.prepared;
        player.addToQueue(y);
        assertSame(first, audio.prepared, "Appending must retain the existing prepared head");
        player.removeQueued(player.upNextSnapshot().manual().getFirst().id());
        assertFalse(audio.promote(first, false));
        assertTrue(audio.promote(audio.prepared, false));
        assertEquals(y, audio.state.track());
        assertTrue(player.upNextSnapshot().manual().isEmpty());
        player.addToQueue(x);
        player.addToQueue(x);
        var duplicate = audio.prepared;
        var id = player.upNextSnapshot().manual().get(1).id();
        moveToFront(id);
        assertFalse(audio.promote(duplicate, false));
        assertTrue(audio.promote(audio.prepared, false));
        assertEquals(1, player.upNextSnapshot().manual().size());
    }

    @Test
    void committedPromotionIsSettledBeforeAnEditAndDelayedCallbacksCannotConsumeAgain() {
        start();
        player.addToQueue(x);
        player.addToQueue(y);
        var prepared = audio.prepared;
        assertTrue(audio.promote(prepared, true));
        player.playNext(track("other", "Z"));
        assertEquals(List.of("Z", "Y"), player.upNextSnapshot().manual().stream().map(q -> q.track().title()).toList());
        prepared.started.run();
        assertEquals(2, player.upNextSnapshot().manual().size());
        player.next();
        assertEquals("Z", audio.state.track().title());
        prepared.ended.run();
        assertEquals("Z", audio.state.track().title());
    }

    @Test
    void preloadedRepeatOneAndOutputChangesRetainQueueAndIgnoreStalePlans() {
        start();
        player.addToQueue(x);
        player.repeat(Repeat.ONE);
        assertEquals(source.getFirst(), audio.prepared.track());
        assertTrue(audio.promote(audio.prepared, false));
        assertEquals(1, player.upNextSnapshot().manual().size());
        var old = audio.prepared;
        player.outputChanged();
        assertFalse(audio.promote(old, false));
        player.next();
        assertEquals(x, audio.state.track());
    }

    @Test
    void editsDeletionAndRootChangesReconcileOnlyAffectedEntries() {
        start();
        player.addToQueue(x);
        player.addToQueue(x);
        player.addToQueue(y);
        var before = player.upNextSnapshot().manual();
        var edited = track("other", "X-renamed");
        player.finishEdit(player.suspendForEdit(), x, edited);
        assertEquals(List.of(edited, edited, y), player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::track).toList());
        assertEquals(before.getFirst().id(), player.upNextSnapshot().manual().getFirst().id());
        player.finishDeletion(player.suspendForEdit(), Set.of());
        assertEquals(3, player.upNextSnapshot().manual().size());
        player.reconcileQueued(Map.of(), Set.of("root", "other"), Set.of());
        assertEquals(3, player.upNextSnapshot().manual().size(), "Offline roots retain entries");
        player.finishDeletion(player.suspendForEdit(), Set.of(edited.id()));
        assertEquals(List.of(y), player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::track).toList());
        player.reconcileQueued(Map.of(), Set.of("root"), Set.of());
        assertTrue(player.upNextSnapshot().manual().isEmpty());
        player.addToQueue(y);
        player.next();
        assertEquals("All", player.playingSource().mode());
        assertEquals("@other", player.playingSource().group());
        assertEquals(origin, player.source());
    }

    @Test
    void bufferedPromotionNeverResumesIncomingAtOutgoingPosition() {
        start();
        player.addToQueue(x);
        var prepared = audio.prepared;
        assertTrue(prepared.gate.claim());
        audio.state = new AudioBackend.State(source.getFirst(), AudioBackend.Status.PLAYING, 49, 60, "");
        var resume = player.suspendForEdit();
        assertEquals(0, resume.position());
        assertTrue(player.upNextSnapshot().manual().isEmpty());
        player.finishEdit(resume, source.getFirst(), source.getFirst());
        assertEquals(x, audio.state.track());
        assertEquals(0, audio.state.position());
        prepared.started.run();
        assertEquals(x, player.upNextSnapshot().current());
    }

    @Test
    void completedRootsPruneOnlyConfirmedMissingAndRefreshMetadataInPlace() {
        start();
        player.addToQueue(x);
        player.addToQueue(y);
        long id = player.upNextSnapshot().manual().getFirst().id();
        var updated = new Track(x.rootId(), x.relativePath(), x.file(), "Updated title", "New artist", "", 60, 0, 0, "");
        player.reconcileQueued(Map.of(updated.id(), updated), Set.of("root", "other"), Set.of("root"));
        assertEquals(2, player.upNextSnapshot().manual().size());
        assertEquals(id, player.upNextSnapshot().manual().getFirst().id());
        assertEquals(updated, player.upNextSnapshot().manual().getFirst().track());
        player.reconcileQueued(Map.of(updated.id(), updated), Set.of("root", "other"), Set.of("other"));
        assertEquals(List.of(updated), player.upNextSnapshot().manual().stream().map(PlaybackController.Queued::track).toList());
    }

    @Test
    void rapidNextAndLateCallbacksCannotSkipTheNewCurrentSong() {
        start();
        player.addToQueue(x);
        player.addToQueue(y);
        var obsolete = audio.prepared;
        player.next();
        player.next();
        player.next();
        assertEquals(source.get(1), audio.state.track());
        obsolete.started.run();
        obsolete.ended.run();
        obsolete.failed.accept("late");
        assertEquals(source.get(1), audio.state.track());
        assertTrue(player.upNextSnapshot().manual().isEmpty());
    }

    @Test
    void seekingReassertsTheSamePlanIfTheBackendDroppedItsPreload() {
        start();
        player.addToQueue(x);
        var gate = audio.prepared.gate();
        audio.prepared = null;
        player.seek(10);
        assertNotNull(audio.prepared);
        assertSame(gate, audio.prepared.gate());
        assertEquals(x, audio.prepared.track());
        assertTrue(audio.promote(audio.prepared, false));
        assertTrue(player.upNextSnapshot().manual().isEmpty());
        assertEquals(source.get(1), audio.prepared.track());
    }

    @Test
    void collectionPreviewDoesNotMislabelARepeatingManualSongAsASourceEntry() {
        start();
        player.addToQueue(x);
        player.next();
        player.repeat(Repeat.ONE);
        assertEquals(x, audio.prepared.track());
        assertEquals(source.subList(1, source.size()), player.upNextSnapshot().automatic());
    }

    @Test
    void previewDoesNotInventAnUnpreparedShuffleCycleBehindManualSongs() {
        player.play(source, source.getLast(), origin);
        player.repeat(Repeat.ALL);
        player.shuffle(true);
        // With shuffle enabled the anchor is first; advance to the final source entry.
        player.next();
        player.next();
        player.addToQueue(x);
        assertTrue(player.upNextSnapshot().automatic().isEmpty());
        assertEquals(3, player.upNextSnapshot().sourceSize());
        player.next();
        assertEquals(audio.prepared.track(), player.upNextSnapshot().automatic().getFirst());
    }
}
