package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerFollowingTest {
    @Test
    void automaticFollowingDoesNotInterruptAnActiveOrUnfinishedSearch() {
        assertTrue(MusicScreen.canFollowPlayback("", false));
        assertFalse(MusicScreen.canFollowPlayback("", true));
        assertFalse(MusicScreen.canFollowPlayback("song", false));
        assertFalse(MusicScreen.canFollowPlayback("song", true));
        assertFalse(MusicScreen.canFollowPlayback(" ", false));
    }
}
