package com.darkmusic.client.pip;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class PipAudioRecoveryTest {
    @Test
    void reconnectsAtFreshBoundariesAndStopsAfterThreeBrokenStreams() {
        try (var audio = new PipAudio()) {
            var opens = new AtomicInteger();
            var closes = new AtomicInteger();
            PipSession.receiveAudio(audio, () -> false, () -> {
                opens.incrementAndGet();
                return new ByteArrayInputStream(new byte[]{1}) {
                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }
                };
            }, () -> {
            }, System::nanoTime);
            assertEquals(3, opens.get());
            assertEquals(3, closes.get());
            assertEquals("pip.audio_error", audio.status());
        }
    }

    @Test
    void cancellationAndUnsupportedAudioDoNotReconnect() {
        try (var audio = new PipAudio()) {
            var stopped = new AtomicBoolean();
            var opens = new AtomicInteger();
            PipSession.receiveAudio(audio, stopped::get, () -> {
                opens.incrementAndGet();
                stopped.set(true);
                return InputStream.nullInputStream();
            }, () -> {
            }, System::nanoTime);
            assertEquals(1, opens.get());
            assertNotEquals("pip.audio_error", audio.status());
            audio.failure("pip.audio_unsupported");
            PipSession.receiveAudio(audio, () -> false, () -> {
                fail("Unsupported capability must not reconnect");
                return null;
            }, () -> {
            }, System::nanoTime);
            assertEquals("pip.audio_unsupported", audio.status());
        }
    }

    @Test
    void missingPipeHasABoundedConnectionDeadline() {
        try (var audio = new PipAudio()) {
            var now = new AtomicLong();
            var opens = new AtomicInteger();
            PipSession.receiveAudio(audio, () -> false, () -> {
                opens.incrementAndGet();
                throw new FileNotFoundException("injected unavailable route");
            }, () -> now.addAndGet(1_000_000_000), now::get);
            assertEquals(20, opens.get());
            assertEquals("pip.audio_error", audio.status());
        }
    }
}
