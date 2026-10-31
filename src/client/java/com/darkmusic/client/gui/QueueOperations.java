package com.darkmusic.client.gui;

import com.darkmusic.client.library.Track;

import java.util.List;

/**
 * Changes to the songs queued after the current one. Each applies the queue's own admission (the client accepting
 * operations and the player existing) and returns false without changing anything when the queue may not change now.
 */
public interface QueueOperations {
    /**
     * False also when the queue is full.
     */
    boolean playNext(Track track);

    /**
     * False also when the queue is full.
     */
    boolean addToQueue(Track track);

    /**
     * False also when the queue is full or the song is missing.
     */
    boolean insert(Track track, int index);

    /**
     * Applies order to the queued songs only if they are still expectedIds. False also when they changed meanwhile.
     */
    boolean reorder(List<Long> expectedIds, List<Integer> order);

    /**
     * Removes each queued song that is still queued.
     */
    boolean remove(List<Long> ids);

    boolean clear();
}
