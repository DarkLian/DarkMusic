package com.darkmusic.client.audio;

import com.darkmusic.client.cleanup.ComparisonAudition;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComparisonAuditionTest {
    @Test
    void samePreviewPausesAndResumesWithoutReopeningOrResettingPosition() {
        var output = new UpNextTest.Fake();
        var main = new PlaybackController(output, s -> {
        });
        var a = UpNextTest.track("r", "a");
        var b = UpNextTest.track("r", "b");
        main.play(List.of(a, b), a);
        main.addToQueue(b);
        var queue = main.upNextSnapshot().manual();
        var preview = new UpNextTest.Fake();
        try (var audition = new ComparisonAudition(main, () -> preview)) {
            audition.play(a, 1);
            preview.seek(5);
            assertTrue(audition.active(a));
            audition.play(a, 1);
            assertFalse(audition.active(a));
            assertEquals(AudioBackend.Status.PAUSED, preview.state().status());
            assertEquals(5, preview.state().position());
            assertEquals(AudioBackend.Status.PAUSED, main.state().status());
            audition.play(a, 1);
            assertTrue(audition.active(a));
            assertEquals(5, preview.state().position());
            assertEquals(1, preview.opens);
            audition.play(b, 1);
            assertEquals(b, preview.state().track());
            assertFalse(audition.active(a));
            assertTrue(audition.active(b));
            assertEquals(2, preview.opens);
            audition.stop();
            audition.play(b, 1);
            assertEquals(3, preview.opens);
            assertEquals(queue, main.upNextSnapshot().manual());
        }
        assertEquals(AudioBackend.Status.PLAYING, main.state().status());
    }

    @Test
    void outputInitializationFailureRestoresMainPlayback() {
        var output = new UpNextTest.Fake();
        var main = new PlaybackController(output, s -> {
        });
        var a = UpNextTest.track("r", "a");
        main.play(List.of(a), a);
        main.seek(12);
        try (var audition = new ComparisonAudition(main, () -> {
            throw new IllegalStateException("Rejected test output");
        })) {
            assertDoesNotThrow(() -> audition.play(a, 1));
            assertFalse(audition.error().isBlank());
            assertEquals(AudioBackend.Status.PLAYING, main.state().status());
            assertEquals(12, main.state().position());
        }
    }

    @Test
    void previewPreservesSourceQueuePauseAndPositionWithoutPlayingThroughTheController() {
        var output = new UpNextTest.Fake();
        var main = new PlaybackController(output, s -> {
        });
        var a = UpNextTest.track("r", "a");
        var b = UpNextTest.track("r", "b");
        var x = UpNextTest.track("r", "x");
        main.play(List.of(a, b), a);
        main.addToQueue(x);
        main.seek(8);
        var queued = main.upNextSnapshot().manual();
        var source = main.source();
        var preview = new UpNextTest.Fake();
        var audition = new ComparisonAudition(main, () -> preview);
        audition.play(b, 1);
        assertEquals(AudioBackend.Status.PAUSED, main.state().status());
        audition.play(x, 1);
        assertEquals(x, preview.state().track());
        assertEquals(a, main.state().track());
        assertEquals(queued, main.upNextSnapshot().manual());
        assertEquals(source, main.source());
        assertEquals(1, output.opens);
        audition.close();
        assertEquals(AudioBackend.Status.PLAYING, main.state().status());
        assertEquals(8, main.state().position());
        main.pause();
        var paused = new ComparisonAudition(main, UpNextTest.Fake::new);
        paused.play(b, 1);
        paused.close();
        assertEquals(AudioBackend.Status.PAUSED, main.state().status());
        main.resume();
        var stopped = new ComparisonAudition(main, UpNextTest.Fake::new);
        stopped.play(b, 1);
        main.stop();
        stopped.close();
        assertEquals(AudioBackend.Status.STOPPED, main.state().status());
    }
}
