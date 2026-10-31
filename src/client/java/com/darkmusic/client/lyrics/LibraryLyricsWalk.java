package com.darkmusic.client.lyrics;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.tools.ProcessRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Client-tick library traversal; local inspection jobs never touch this state.
 */
final class LibraryLyricsWalk {
    interface Inputs {
        void inspect(Track track);

        boolean inspected(Track track);

        LyricsDocument document(Track track);
    }

    private final LongSupplier clock;
    private final LyricsLookup lookup;
    private final Inputs inputs;
    private final BooleanSupplier available;
    private final Object serviceMonitor;
    private List<Track> backgroundTracks = List.of();
    private int backgroundCursor;
    private Track backgroundTrack;
    private long backgroundAt;
    private boolean backgroundActive;
    private long backgroundCacheRevision = -1;
    private final Map<String, Track> backgroundVisited = new HashMap<>();
    private Set<String> backgroundRoots;
    private boolean libraryRun;
    private ProcessRunner.Token libraryOwner;
    private long libraryRequestStart;

    LibraryLyricsWalk(LyricsLookup lookup, LongSupplier clock, Inputs inputs, BooleanSupplier available, Object serviceMonitor) {
        this.lookup = lookup;
        this.clock = clock;
        this.inputs = inputs;
        this.available = available;
        this.serviceMonitor = serviceMonitor;
    }

    // enabled(false) already holds the service monitor; do not give the walk a second lock.
    void disabled() {
        synchronized (serviceMonitor) {
            cancelLibraryLookup();
        }
    }

    void startLibraryLookup() {
        cancelLibraryLookup();
        libraryRun = true;
        libraryOwner = new ProcessRunner.Token();
        backgroundActive = false;
        backgroundTracks = List.of();
        backgroundVisited.clear();
        backgroundAt = 0;
        libraryRequestStart = lookup.requests();
    }

    void cancelLibraryLookup() {
        libraryRun = false;
        backgroundActive = false;
        lookup.cancelOwner(libraryOwner);
        libraryOwner = null;
    }

    boolean libraryLookupRunning() {
        return libraryRun;
    }

    int libraryChecked() {
        return backgroundVisited.size();
    }

    int libraryTotal() {
        return backgroundTracks.size();
    }

    long libraryRequests() {
        return lookup.requests() - libraryRequestStart;
    }

    long libraryRetrySeconds() {
        return lookup.ownerRetrySeconds(libraryOwner);
    }

    void background(List<Track> tracks, Set<String> healthyRoots, boolean active) {
        lookup.tickRetries();
        if (!available.getAsBoolean() || !active) {
            backgroundActive = false;
            return;
        }
        long now = clock.getAsLong();
        if (!backgroundActive || backgroundCacheRevision != lookup.cacheRevision()) {
            backgroundActive = true;
            backgroundCacheRevision = lookup.cacheRevision();
            backgroundTracks = tracks;
            backgroundRoots = healthyRoots;
            backgroundCursor = 0;
            backgroundTrack = null;
            backgroundVisited.clear();
        } else if (backgroundTrack == null && backgroundCursor >= backgroundTracks.size()
            && (backgroundTracks != tracks || !Objects.equals(backgroundRoots, healthyRoots))) {
            // Finish the current pass before adopting a rescan, so early songs cannot starve later ones.
            backgroundTracks = tracks;
            backgroundRoots = healthyRoots;
            backgroundCursor = 0;
        }
        // Initialize progress even while a previous lookup or provider cooldown is still active.
        if (now < backgroundAt || lookup.busy()) return;
        backgroundAt = now + TimeUnit.SECONDS.toNanos(1);
        if (backgroundTrack == null) {
            for (int skipped = 0; skipped < 1024 && backgroundCursor < backgroundTracks.size(); skipped++) {
                Track candidate = backgroundTracks.get(backgroundCursor++);
                if (!candidate.equals(backgroundVisited.get(candidate.id()))) {
                    backgroundTrack = candidate;
                    break;
                }
            }
            if (backgroundTrack == null) {
                if (backgroundCursor >= backgroundTracks.size() && !lookup.ownerOutstanding(libraryOwner))
                    libraryRun = false;
                return;
            }
        }
        if (healthyRoots != null && !healthyRoots.contains(backgroundTrack.rootId())) {
            backgroundTrack = null;
            return;
        }
        inputs.inspect(backgroundTrack);
        if (!inputs.inspected(backgroundTrack)) return;
        lookup.automatic(backgroundTrack, inputs.document(backgroundTrack).available(), libraryRun ? libraryOwner : null);
        backgroundVisited.put(backgroundTrack.id(), backgroundTrack);
        backgroundTrack = null;
    }

}
