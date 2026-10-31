package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;

import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * Worker-confined pinned loudness sessions and their smoothed gains. Output changes keep this state.
 */
final class Normalization {
    private double gain = 1, incomingGain = 1;
    private LoudnessCache.Session current, incoming;
    private long sessionId;

    void begin(long id) {
        if (sessionId != id) {
            current = null;
            gain = 1;
            sessionId = id;
        }
    }

    void restore(LoudnessCache.Session session, long id, double previousGain) {
        current = session;
        sessionId = id;
        gain = previousGain;
    }

    void resetIncoming() {
        incomingGain = 1;
        incoming = null;
    }

    void adoptIncoming(long id) {
        current = incoming;
        sessionId = id;
        gain = incomingGain;
        resetIncoming();
    }

    LoudnessCache.Session session(Track track, AudioSettings selected, boolean next, Supplier<LoudnessCache> caches) {
        var session = next ? incoming : current;
        if (session == null || !session.matches(selected)) {
            var cache = caches.get();
            if (cache == null) throw new CancellationException("Playback closed");
            session = cache.session(track, selected);
            if (next) {
                incoming = session;
                incomingGain = session.gain(selected);
            } else {
                current = session;
                gain = session.gain(selected);
            }
        }
        return session;
    }

    void initialize(Track track, AudioSettings selected, Supplier<LoudnessCache> caches) {
        gain = session(track, selected, false, caches).gain(selected);
    }

    void smooth(double target, double smoothing) {
        gain += (target - gain) * smoothing;
    }

    void smoothIncoming(double target, double smoothing) {
        incomingGain += (target - incomingGain) * smoothing;
    }

    double gain() {
        return gain;
    }

    double incomingGain() {
        return incomingGain;
    }

    LoudnessCache.Session current() {
        return current;
    }

    LoudnessCache.Status status(AudioSettings selected) {
        return current == null ? LoudnessCache.Status.OFF : current.status(selected, gain);
    }
}
