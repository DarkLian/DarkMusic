package com.darkmusic.client.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VolumeFeedbackTest {
    @Test
    void shortcutFeedbackHoldsThenFadesAndNewPressRestartsIt() {
        var feedback = new VolumeFeedback();
        assertEquals(0, feedback.opacity(0));
        feedback.show(.55, 0);
        assertEquals(1, feedback.opacity(0));
        assertEquals(1, feedback.opacity(1_000_000_000L));
        assertEquals(.5, feedback.opacity(1_375_000_000L), 1e-8);
        assertEquals(0, feedback.opacity(1_750_000_000L));
        feedback.show(0, 2_000_000_000L);
        assertEquals(1, feedback.opacity(2_100_000_000L));
    }
}
