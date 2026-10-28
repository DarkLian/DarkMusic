package com.darkmusic.client;

/**
 * Whether the client is shutting down, resetting storage or busy with the music files, and so which actions may
 * start. Written on the client thread; closed is also read from workers.
 */
final class ClientState {
    volatile boolean closed;
    boolean resetting;
    // Set while an import, edit, deletion or backup restore owns the music files; menus read it to disable actions.
    boolean mediaBusy;
    boolean restoring;
    boolean outputChanging;

    /**
     * Not shutting down and not resetting storage.
     */
    boolean alive() {
        return !closed && !resetting;
    }

    /**
     * Alive, with no import, restore or other media operation in progress.
     */
    boolean idle() {
        return alive() && !mediaBusy;
    }

    /**
     * Idle and not switching the output device: free to start another exclusive operation.
     */
    boolean accepting() {
        return idle() && !outputChanging;
    }
}
