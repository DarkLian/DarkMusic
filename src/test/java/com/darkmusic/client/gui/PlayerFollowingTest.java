package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerFollowingTest {
    @Test
    void automaticFollowingDoesNotInterruptAnActiveOrUnfinishedSearch() {
        assertTrue(PlayerBrowser.canFollowPlayback("", false));
        assertFalse(PlayerBrowser.canFollowPlayback("", true));
        assertFalse(PlayerBrowser.canFollowPlayback("song", false));
        assertFalse(PlayerBrowser.canFollowPlayback("song", true));
        assertFalse(PlayerBrowser.canFollowPlayback(" ", false));
    }
}
