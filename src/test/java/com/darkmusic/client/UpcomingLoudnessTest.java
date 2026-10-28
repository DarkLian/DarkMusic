package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UpcomingLoudnessTest {
    @TempDir
    Path directory;

    @Test
    @Timeout(30)
    void upcomingRealAudioIsAnalyzedBeforePlaybackWithoutChangingTheActiveSession() throws Exception {
        String configured = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeFalse(configured.isBlank(), "Real FFmpeg integration is opt-in");
        Path ff = Path.of(configured), audio = directory.resolve("current.wav"), next = directory.resolve("upcoming.wav");
        var result = new ProcessRunner().run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", TestMusic.featured().toString(),
            "-t", "4", "-vn", "-map_metadata", "-1", "-c:a", "pcm_s16le", audio.toString()), directory, new ProcessRunner.Token(), Duration.ofSeconds(15), 16000);
        assertEquals(0, result.exit(), result.output());
        Files.copy(audio, next);
        var current = new Track("real", "current.wav", audio, "", "", "", 4, Files.size(audio), 0, "");
        var upcoming = new Track("real", "upcoming.wav", next, "", "", "", 4, Files.size(next), 0, "");
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.normalization = true;
        settings.analyzeLoudness = true;
        settings.gapless = false; // Analysis is useful even when decoder preloading is disabled.
        var errors = new AtomicReference<String>();
        ExternalAudio.configure(() -> new ExternalAudio.Paths(directory, ff.toString(), ff.resolveSibling(ff.getFileName().toString().replace("ffmpeg", "ffprobe")).toString()));
        JavaSoundBackend.Output output = format -> (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getFormat" -> format;
            case "available", "getBufferSize" -> 4096;
            case "getLongFramePosition" -> 0L;
            case "isOpen" -> true;
            case "isRunning" -> false;
            case "stop", "start", "flush", "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        var openedCache = new AtomicReference<LoudnessCache>();
        try (var backend = new JavaSoundBackend(Runnable::run, PcmDecoder::open, output)) {
            backend.configure(settings);
            backend.playFrom(current, 0, true, () -> {
            }, errors::set);
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            var field = JavaSoundBackend.class.getDeclaredField("loudness");
            field.setAccessible(true);
            var cache = (LoudnessCache) field.get(backend);
            openedCache.set(cache);
            backend.nextTrack(upcoming, () -> {
            }, () -> {
            }, errors::set);
            StorageTest.await(() -> cache.status(upcoming, settings, 1).state() == LoudnessCache.State.READY);
            assertEquals(AudioBackend.Status.PAUSED, backend.state().status());
            assertEquals(0, backend.normalization().appliedDb(), 0, "Finishing background analysis must not alter the paused session");
            double expected = 20 * Math.log10(cache.gain(upcoming, settings));
            backend.playFrom(upcoming, 0, true, () -> {
            }, errors::set);
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED && backend.state().track().id().equals(upcoming.id()));
            assertEquals(expected, backend.normalization().appliedDb(), 1e-9);
            assertNull(errors.get());
        } finally {
            if (openedCache.get() != null) openedCache.get().close();
            ExternalAudio.configure(null);
        }
    }
}
