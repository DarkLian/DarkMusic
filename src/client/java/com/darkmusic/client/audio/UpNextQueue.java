package com.darkmusic.client.audio;

import com.darkmusic.client.audio.PlaybackController.Queued;
import com.darkmusic.client.audio.PlaybackController.UpNextSnapshot;
import com.darkmusic.client.library.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Up Next: the songs queued by hand, each with an id that drags and removals name, and the snapshot screens read.
 * The controller decides when a change also replans the next song; this class only holds the entries. Client thread.
 */
final class UpNextQueue {
    private final List<Queued> entries = new ArrayList<>();
    private long ids, revision;
    private UpNextSnapshot snapshot;

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    boolean full() {
        return entries.size() >= PlaybackController.QUEUE_LIMIT;
    }

    Queued first() {
        return entries.getFirst();
    }

    /**
     * The entries in order; snapshots copy them.
     */
    List<Queued> entries() {
        return entries;
    }

    List<Long> ids() {
        return entries.stream().map(Queued::id).toList();
    }

    void insert(int index, Track track) {
        entries.add(Math.clamp(index, 0, entries.size()), new Queued(++ids, track));
    }

    void add(Track track, boolean first) {
        var entry = new Queued(++ids, track);
        if (first) entries.addFirst(entry);
        else entries.add(entry);
    }

    Queued removeFirst() {
        return entries.removeFirst();
    }

    int indexOf(long id) {
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).id() == id) return i;
        return -1;
    }

    boolean remove(long id) {
        return entries.removeIf(q -> q.id() == id);
    }

    void removeTracks(Set<String> trackIds) {
        entries.removeIf(q -> trackIds.contains(q.track().id()));
    }

    void clear() {
        entries.clear();
    }

    /**
     * The entries in the new order, or null when the drag is stale (the ids changed), names a position twice or out
     * of range, or changes nothing.
     */
    List<Queued> reordered(List<Long> expectedIds, List<Integer> order) {
        if (!ids().equals(expectedIds) || order.size() != entries.size() || new HashSet<>(order).size() != entries.size()
            || order.stream().anyMatch(i -> i == null || i < 0 || i >= entries.size())) return null;
        var reordered = order.stream().map(entries::get).toList();
        return reordered.equals(entries) ? null : reordered;
    }

    void replace(List<Queued> reordered) {
        entries.clear();
        entries.addAll(reordered);
    }

    /**
     * Whether a library change removed or changed a queued song: its root is gone, a complete scan no longer has it,
     * or it has new metadata.
     */
    boolean stale(Map<String, Track> index, Set<String> registeredRoots, Set<String> completeRoots) {
        return entries.stream().anyMatch(q -> !registeredRoots.contains(q.track().rootId())
            || !index.containsKey(q.track().id()) && completeRoots.contains(q.track().rootId())
            || index.containsKey(q.track().id()) && !index.get(q.track().id()).equals(q.track()));
    }

    void reconcile(Map<String, Track> index, Set<String> registeredRoots, Set<String> completeRoots) {
        entries.removeIf(q -> !registeredRoots.contains(q.track().rootId()) || !index.containsKey(q.track().id()) && completeRoots.contains(q.track().rootId()));
        entries.replaceAll(q -> new Queued(q.id(), index.getOrDefault(q.track().id(), q.track())));
    }

    void replaceTrack(String id, Track updated) {
        entries.replaceAll(q -> q.track().id().equals(id) ? new Queued(q.id(), updated) : q);
    }

    /**
     * Something the snapshot shows changed: the queue, the current song or the order.
     */
    void changed() {
        revision++;
        snapshot = null;
    }

    long revision() {
        return revision;
    }

    UpNextSnapshot snapshot() {
        return snapshot;
    }

    void snapshot(UpNextSnapshot value) {
        snapshot = value;
    }
}
