package com.darkmusic.client.audio;

import java.util.ArrayDeque;

/**
 * Worker-confined device/source timeline. The backend still publishes state and hearing callbacks.
 */
final class PlaybackClock {
    record Segment(long outputFrame, double sourceSeconds, double speed) {
        double position(long played, float outputRate) {
            return sourceSeconds + Math.max(0, played - outputFrame) / (double) outputRate * speed;
        }
    }

    private final ArrayDeque<Segment> timeline = new ArrayDeque<>();
    private long submittedFrames, hearingFrame;
    private double offset, deviceRate;

    void open(double offset, double speed) {
        this.offset = offset;
        submittedFrames = 0;
        hearingFrame = 0;
        timeline.clear();
        timeline.add(new Segment(0, offset, speed));
    }

    void speed(double speed, double sourceFrames, float sourceRate) {
        if (timeline.getLast().speed != speed)
            timeline.add(new Segment(submittedFrames, offset + sourceFrames / sourceRate, speed));
    }

    void handoff(double start, double speed) {
        offset = 0;
        timeline.clear();
        timeline.add(new Segment(submittedFrames, start, speed));
    }

    Segment segment(long played) {
        while (timeline.size() > 1) {
            var first = timeline.removeFirst();
            if (timeline.getFirst().outputFrame > played) {
                timeline.addFirst(first);
                break;
            }
        }
        return timeline.getFirst();
    }

    long heard(long played) {
        long advanced = Math.max(0, played - hearingFrame);
        hearingFrame = played;
        return advanced;
    }

    void submitted(int frames) {
        submittedFrames += frames;
    }

    long submittedFrames() {
        return submittedFrames;
    }

    double offset() {
        return offset;
    }

    void deviceRate(double rate) {
        deviceRate = rate;
    }

    double deviceRate() {
        return deviceRate;
    }

    void clear() {
        timeline.clear();
    }
}
