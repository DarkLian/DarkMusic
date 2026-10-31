package com.darkmusic.client;

/**
 * A pending chat destination, tied to the world that requested it. Client thread.
 */
record ScreenRequest(ScreenRequests.RequestedScreen kind, Object world, long deadline) {
    boolean due(long now, Object currentWorld, boolean alive, boolean playerAlive) {
        return alive && currentWorld == world && now <= deadline && playerAlive;
    }
}
