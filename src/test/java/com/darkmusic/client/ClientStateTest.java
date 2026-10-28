package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStateTest {
    @Test
    void freshClientAcceptsEverything() {
        var state = new ClientState();
        assertTrue(state.alive());
        assertTrue(state.idle());
        assertTrue(state.accepting());
    }

    @Test
    void shutdownAndResetStopEverything() {
        for (int flag = 0; flag < 2; flag++) {
            var state = new ClientState();
            if (flag == 0) state.closed = true;
            else state.resetting = true;
            assertFalse(state.alive());
            assertFalse(state.idle());
            assertFalse(state.accepting());
        }
    }

    @Test
    void mediaWorkKeepsTheClientAliveButNotIdle() {
        var state = new ClientState();
        state.mediaBusy = true;
        assertTrue(state.alive());
        assertFalse(state.idle());
        assertFalse(state.accepting());
    }

    @Test
    void switchingOutputOnlyBlocksNewExclusiveWork() {
        var state = new ClientState();
        state.outputChanging = true;
        assertTrue(state.alive());
        assertTrue(state.idle());
        assertFalse(state.accepting());
    }

    @Test
    void restoringAloneDoesNotChangeTheChecks() {
        var state = new ClientState();
        state.restoring = true;
        assertTrue(state.accepting());
    }
}
