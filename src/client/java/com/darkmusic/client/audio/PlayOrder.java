package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * The order the collection plays in, as indexes into the controller's tracks, where playback is in it, and the songs
 * Previous returns to. The version changes whenever a prepared next song may no longer follow from the order. Client
 * thread.
 */
final class PlayOrder {
    private final List<Integer> order = new ArrayList<>();
    private final Deque<Visited> history = new ArrayDeque<>();
    private int cursor = -1;
    private long version;

    record Visited(Track track, boolean manual, int anchor) {
    }

    /**
     * The live order. A plan keeps this list when the next song follows from it, or a reshuffled copy when it wraps;
     * the controller tells the two apart by identity.
     */
    List<Integer> list() {
        return order;
    }

    int size() {
        return order.size();
    }

    boolean isEmpty() {
        return order.isEmpty();
    }

    int at(int position) {
        return order.get(position);
    }

    int cursor() {
        return cursor;
    }

    void cursor(int value) {
        cursor = value;
    }

    /**
     * The track index at the cursor, or -1.
     */
    int current() {
        return cursor >= 0 && cursor < order.size() ? order.get(cursor) : -1;
    }

    long version() {
        return version;
    }

    void bump() {
        version++;
    }

    /**
     * Every track once, shuffled or in order, with current (-1 for none) first when shuffled and at its own place
     * otherwise; the cursor points at it.
     */
    void rebuild(int current, int size, boolean shuffle) {
        version++;
        order.clear();
        for (int i = 0; i < size; i++) if (i != current) order.add(i);
        if (shuffle) Collections.shuffle(order);
        if (current >= 0) {
            if (shuffle) order.addFirst(current);
            else order.add(Math.min(current, order.size()), current);
        }
        cursor = current >= 0 ? order.indexOf(current) : -1;
    }

    /**
     * Repeat All passed the end: a new order from the start that does not begin with the song that just played.
     */
    void wrap(int previous, int size, boolean shuffle) {
        rebuild(-1, size, shuffle);
        cursor = 0;
        if (shuffle && order.size() > 1 && order.getFirst() == previous) Collections.swap(order, 0, 1);
    }

    /**
     * A planned next song became audible: its order replaces this one unless it is this one.
     */
    void adopt(List<Integer> planned, int destination) {
        if (planned != order) {
            order.clear();
            order.addAll(planned);
        }
        cursor = destination;
    }

    void remember(Track track, boolean manual) {
        history.addLast(new Visited(track, manual, current()));
        if (history.size() > 1000) history.removeFirst();
    }

    /**
     * The last song played, with the cursor back at its place in the order; null when there is none.
     */
    Visited back() {
        if (history.isEmpty()) return null;
        var visited = history.removeLast();
        cursor = order.indexOf(visited.anchor);
        return visited;
    }

    /**
     * Moves the cursor one place back, wrapping; false when there is no order to move in.
     */
    boolean stepBack() {
        if (cursor < 0 || order.isEmpty()) return false;
        cursor = (cursor + order.size() - 1) % order.size();
        return true;
    }

    void forget() {
        history.clear();
    }

    void reset() {
        order.clear();
        history.clear();
        cursor = -1;
    }

    void replaceTrack(String id, Track updated) {
        var visits = history.stream().map(v -> v.track.id().equals(id) ? new Visited(updated, v.manual, v.anchor) : v).toList();
        history.clear();
        history.addAll(visits);
    }

    /**
     * Songs were deleted: mapping gives each old track index its new one, or -1. Keeps the order and the history of
     * what remains, and the cursor on the current song, or else on the last remaining song before it.
     */
    void remap(int[] mapping, Set<String> removed) {
        int oldCursor = cursor;
        var nextOrder = order.stream().filter(i -> mapping[i] >= 0).map(i -> mapping[i]).toList();
        var nextHistory = history.stream().filter(v -> !removed.contains(v.track.id()))
            .map(v -> new Visited(v.track, v.manual, v.anchor < 0 || mapping[v.anchor] < 0 ? -1 : mapping[v.anchor])).toList();
        int predecessor = (int) order.stream().limit(Math.max(0, oldCursor)).filter(i -> mapping[i] >= 0).count() - 1;
        int anchor = oldCursor >= 0 && oldCursor < order.size() ? mapping[order.get(oldCursor)] : -1;
        order.clear();
        order.addAll(nextOrder);
        history.clear();
        history.addAll(nextHistory);
        cursor = anchor < 0 ? predecessor : order.indexOf(anchor);
    }
}
