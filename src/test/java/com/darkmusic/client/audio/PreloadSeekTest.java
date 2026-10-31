package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class PreloadSeekTest {
    @Test
    @Timeout(15)
    void seekingKeepsAnUntouchedUpcomingDecoderAndUpdatesItsGeneration() throws Exception {
        var clip = TestMusic.clip();
        var opens = new AtomicInteger();
        var error = new AtomicReference<String>();
        var first = new Track("root", "first", Path.of("first"), "", "", "", 4, 1, 1, "");
        var second = new Track("root", "second", Path.of("second"), "", "", "", 4, 1, 1, "");
        JavaSoundBackend.Output output = format -> (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getFormat" -> format;
            case "available", "getBufferSize" -> 4096;
            case "getLongFramePosition" -> 0L;
            case "isOpen" -> true;
            case "isRunning" -> false;
            case "stop", "start", "flush", "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        try (var backend = new JavaSoundBackend(Runnable::run, path -> {
            if (path.equals(second.file())) opens.incrementAndGet();
            return clip.stream();
        }, output)) {
            backend.configure(new AudioSettings());
            backend.playFrom(first, 0, true, () -> {
            }, error::set);
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            backend.nextTrack(second, () -> {
            }, () -> {
            }, error::set);
            Await.of(() -> opens.get() == 1);
            for (double seconds : new double[]{.1, 1.2, .8}) {
                backend.seek(seconds);
                backend.nextTrack(second, () -> {
                }, () -> {
                }, error::set);
                Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED && Math.abs(backend.state().position() - seconds) < .001);
            }
            assertEquals(1, opens.get());
            assertNull(error.get());
        }
    }
}
