package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScreenRequestsTest {
    @Test
    void everyDestinationKeepsItsWorldAndFiveSecondDeadline() {
        Object world = new Object();
        for (var kind : ScreenRequests.RequestedScreen.values()) {
            ScreenRequest request = ScreenRequests.request(kind, world, 17);
            assertEquals(kind, request.kind());
            assertSame(world, request.world());
            assertEquals(17 + TimeUnit.SECONDS.toNanos(5), request.deadline());
            assertTrue(request.due(request.deadline(), world, true, true));
            assertFalse(request.due(request.deadline() + 1, world, true, true));
        }
    }

    @Test
    void worldIdentityDeathAndClosingInvalidateARequest() {
        Object world = new String("world"), equalWorld = new String("world");
        ScreenRequest request = ScreenRequests.request(ScreenRequests.RequestedScreen.LYRICS, world, 0);
        assertFalse(request.due(1, equalWorld, true, true));
        assertFalse(request.due(1, world, false, true));
        assertFalse(request.due(1, world, true, false));
        assertTrue(request.due(1, world, true, true));
    }

    @Test
    void titleScreenRequestsBelongToTheNullWorld() {
        ScreenRequest request = ScreenRequests.request(ScreenRequests.RequestedScreen.PLAYER, null, 0);
        assertTrue(request.due(1, null, true, true));
        assertFalse(request.due(1, new Object(), true, true));
    }
}
