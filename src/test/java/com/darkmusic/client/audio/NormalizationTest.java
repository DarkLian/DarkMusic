package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class NormalizationTest {
    private Track track(String name) {
        return new Track("pcm", name, Path.of(name), "", "", "", 4, 10, 20, "");
    }

    private AudioSettings settings() {
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.normalization = true;
        return settings;
    }

    @Test
    void handoffAdoptsIncomingAndReopeningTheSameSessionKeepsGain() {
        ExternalAudio.configure(null);
        var selected = settings();
        try (var cache = new LoudnessCache()) {
            var normalization = new Normalization();
            normalization.begin(1);
            normalization.initialize(track("a"), selected, () -> cache);
            var previous = normalization.current();
            normalization.smooth(.25, .5);
            assertEquals(.625, normalization.gain());
            normalization.begin(1);
            assertSame(previous, normalization.current());
            assertEquals(.625, normalization.gain());
            var incoming = normalization.session(track("b"), selected, true, () -> cache);
            normalization.smoothIncoming(.5, .5);
            normalization.adoptIncoming(2);
            assertSame(incoming, normalization.current());
            assertEquals(.75, normalization.gain());
            assertEquals(1, normalization.incomingGain());
            normalization.restore(previous, 1, .625);
            normalization.begin(1);
            assertSame(previous, normalization.current());
            assertEquals(.625, normalization.gain());
            var status = normalization.status(selected);
            assertEquals(status, normalization.status(selected), "Output reopen only reads status");
            normalization.begin(3);
            assertNull(normalization.current());
            assertEquals(1, normalization.gain());
            assertEquals(LoudnessCache.Status.OFF, normalization.status(selected));
        }
    }

    @Test
    void pendingResetLeavesCurrentSessionAloneAndClosedCacheKeepsTheOriginalFailure() {
        ExternalAudio.configure(null);
        try (var cache = new LoudnessCache()) {
            var normalization = new Normalization();
            var selected = settings();
            normalization.initialize(track("a"), selected, () -> cache);
            var current = normalization.current();
            normalization.smoothIncoming(.25, .5);
            normalization.resetIncoming();
            assertSame(current, normalization.current());
            assertEquals(1, normalization.incomingGain());
            var failure = assertThrows(CancellationException.class,
                () -> normalization.session(track("b"), selected, true, () -> null));
            assertEquals("Playback closed", failure.getMessage());
        }
    }
}
