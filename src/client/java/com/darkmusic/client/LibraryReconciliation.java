package com.darkmusic.client;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.cleanup.DuplicateResult;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.text.Text;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Brings the services that keep per-track state in step with the library after each scan. Polled once per client
 * tick; owns the revision markers and the one outstanding playlist cleanup.
 * <p>
 * Every consumer reads the same snapshot but applies its own evidence rule, because a track missing from a scan is
 * not proof it was deleted:
 * <ul>
 * <li>lyric choices: registered roots, complete roots, and unreadable scopes kept;
 * <li>duplicate reviews and scans: complete roots without unreadable scopes;
 * <li>source index: the roots, plus tracks once the scan finished;
 * <li>queued playback: tracks of complete registered roots;
 * <li>playlists: entries of complete roots whose file is absent on disk, checked on the storage executor; an
 * inaccessible file is not absent. The result is applied only if the playlists, the roots and this generation are
 * unchanged and the client is idle, and entries are rechecked against the current snapshot.
 * </ul>
 * Nothing runs while a media operation holds the files. Reset starts a new generation: markers from the old library
 * are dropped, and a cleanup still running for it is discarded.
 */
final class LibraryReconciliation {
    interface Inputs {
        MusicLibrary.Snapshot snapshot();

        Map<Path, String> roots();

        /**
         * Null until startup reads them.
         */
        PlaylistStore playlists();

        boolean playlistsReady();

        boolean idle();

        boolean mediaBusy();

        /**
         * The consumers below are null when not created; the cleanup ones are built only on first use.
         */
        PlaybackController playback();

        LyricsLookup lyrics();

        DuplicateScanner cleanupScanner();

        DuplicateReviewStore cleanupReviews();

        LibrarySourceIndex sourceIndex();
    }

    private final Inputs inputs;
    private final Executor storage, client;
    private final Consumer<String> warn;
    private long generation, consumersRevision = -1, cleanedScan = -1, cleanedPlaylists = -1, evidenceRevision = -1;
    private boolean cleanupPending;
    private List<DuplicateResult> evidence = List.of();

    LibraryReconciliation(Inputs inputs, Executor storage, Executor client, Consumer<String> warn) {
        this.inputs = inputs;
        this.storage = storage;
        this.client = client;
        this.warn = warn;
    }

    /**
     * The library is being replaced: forget what was reconciled and ignore a cleanup still in flight.
     */
    void reset() {
        generation++;
        consumersRevision = cleanedScan = cleanedPlaylists = evidenceRevision = -1;
        cleanupPending = false;
        evidence = List.of();
    }

    /**
     * Client thread. Returns whether the playlists are reconciled with the current scan, which autoplay waits for.
     */
    boolean tick() {
        var snapshot = inputs.snapshot();
        if (!inputs.mediaBusy() && consumersRevision != snapshot.revision()) {
            consumersRevision = snapshot.revision();
            updateConsumers(snapshot);
        }
        var scanner = inputs.cleanupScanner();
        var index = inputs.sourceIndex();
        if (index != null && scanner != null && evidenceRevision != scanner.snapshot().revision()) {
            evidenceRevision = scanner.snapshot().revision();
            var pairs = scanner.snapshot().pairs();
            if (pairs != evidence) {
                evidence = pairs;
                storage.execute(() -> index.evidence(pairs));
            }
        }
        return cleanPlaylists();
    }

    private void updateConsumers(MusicLibrary.Snapshot snapshot) {
        var roots = inputs.roots();
        var registered = new HashSet<>(roots.values());
        var lyrics = inputs.lyrics();
        if (!snapshot.scanning() && lyrics != null)
            lyrics.prune(snapshot.tracks(), registered, snapshot.completeRoots(), snapshot.unreadableScopes());
        if (!snapshot.scanning()) {
            var cleanupRoots = new HashSet<>(snapshot.completeRoots());
            cleanupRoots.removeAll(snapshot.unreadableScopes().keySet());
            if (inputs.cleanupReviews() != null)
                inputs.cleanupReviews().prune(snapshot.tracks(), registered, cleanupRoots);
            if (inputs.cleanupScanner() != null)
                inputs.cleanupScanner().prune(snapshot.tracks(), registered, cleanupRoots);
        }
        var index = inputs.sourceIndex();
        if (index != null) {
            index.roots(roots);
            if (!snapshot.scanning()) index.update(snapshot.tracks(), roots, snapshot.complete());
        }
        var playback = inputs.playback();
        if (!snapshot.scanning() && playback != null && inputs.playlists() != null)
            playback.reconcileQueued(inputs.playlists().index(snapshot.tracks()), registered, snapshot.completeRoots());
    }

    private boolean cleanPlaylists() {
        var store = inputs.playlists();
        if (inputs.mediaBusy() || !inputs.playlistsReady() || store == null || cleanupPending) return false;
        var snapshot = inputs.snapshot();
        if (snapshot.completeRoots().isEmpty()) return false;
        if (cleanedScan == snapshot.revision() && cleanedPlaylists == store.revision()) return true;
        var entries = store.all();
        long revision = store.revision();
        var roots = inputs.roots();
        long started = generation;
        cleanupPending = true;
        storage.execute(() -> {
            Set<PlaylistStore.Entry> missing;
            try {
                var all = new HashSet<PlaylistStore.Entry>();
                for (var entry : roots.entrySet())
                    if (snapshot.completeRoots().contains(entry.getValue()))
                        all.addAll(PlaylistStore.missing(entry.getKey(), entry.getValue(), entries, snapshot.tracks()));
                missing = all;
            } catch (Exception e) {
                missing = Set.of();
                warn.accept(Text.failure(e, "error.playlist_scan"));
            }
            var result = missing;
            client.execute(() -> {
                if (started != generation) return;
                cleanupPending = false;
                if (!inputs.idle() || inputs.playlists() != store || store.revision() != revision
                    || !roots.equals(inputs.roots())) return;
                var current = inputs.snapshot();
                var present = store.index(current.tracks());
                store.removeMissing(result.stream().filter(entry -> current.completeRoots().contains(entry.rootId())
                    && !present.containsKey(entry.key())).collect(Collectors.toSet()));
                store.acknowledgeTracks(current.tracks());
                cleanedScan = snapshot.revision();
                cleanedPlaylists = store.revision();
            });
        });
        return false;
    }
}
