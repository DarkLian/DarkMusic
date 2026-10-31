package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackClockTest {
    @Test
    void queuedSpeedSegmentsTakeEffectOnlyWhenTheDeviceReachesThem() {
        var clock = new PlaybackClock();
        clock.open(2, 1);
        clock.deviceRate(48_000);
        clock.submitted(48_000);
        clock.speed(1.25, 48_000, 48_000);
        clock.submitted(48_000);
        clock.speed(.5, 108_000, 48_000);
        assertEquals(2.5, clock.segment(24_000).position(24_000, 48_000));
        assertEquals(3, clock.segment(48_000).position(48_000, 48_000));
        assertEquals(3.625, clock.segment(72_000).position(72_000, 48_000));
        assertEquals(4.5, clock.segment(120_000).position(120_000, 48_000));
        assertEquals(48_000, clock.deviceRate());
    }

    @Test
    void handoffCarriesDeviceFramesButReplacesTheSourceTimelineAndOffset() {
        var clock = new PlaybackClock();
        clock.open(8, 1);
        clock.submitted(96_000);
        assertEquals(48_000, clock.heard(48_000));
        clock.handoff(3, 1.25);
        assertEquals(96_000, clock.submittedFrames());
        assertEquals(0, clock.offset());
        assertEquals(3, clock.segment(95_000).position(95_000, 48_000));
        assertEquals(4.25, clock.segment(144_000).position(144_000, 48_000));
        assertEquals(96_000, clock.heard(144_000));
        assertEquals(0, clock.heard(144_000));
        clock.clear();
        clock.open(12, .5);
        assertEquals(0, clock.submittedFrames());
        assertEquals(100, clock.heard(100));
        assertEquals(12, clock.offset());
    }
}
