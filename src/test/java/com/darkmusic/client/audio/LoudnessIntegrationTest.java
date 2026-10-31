package com.darkmusic.client.audio;

import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.library.MetadataReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class LoudnessIntegrationTest {
    @TempDir
    Path temp;

    @AfterEach
    void restore() {
        ExternalAudio.configure(null);
    }

    @Test
    void replayGainAndMeasuredLufsRespectPeakHeadroomAndCacheIdentity() throws Exception {
        String ff = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeFalse(ff.isBlank(), "Real FFmpeg integration is opt-in");
        Path ffmpeg = Path.of(ff);
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, ff, ffmpeg.resolveSibling(ffmpeg.getFileName().toString().replace("ffmpeg", "ffprobe")).toString()));
        Path source = temp.resolve("source.wav"), tagged = temp.resolve("tagged.flac");
        try (var in = TestMusic.clip().stream()) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, source.toFile());
        }
        var runner = new ProcessRunner();
        var token = new ProcessRunner.Token();
        var r = runner.run(List.of(ff, "-nostdin", "-v", "error", "-i", source.toString(), "-c:a", "flac", "-metadata", "replaygain_track_gain=6 dB", "-metadata", "replaygain_track_peak=0.5", tagged.toString()), temp, token, Duration.ofSeconds(30), 16000);
        assertEquals(0, r.exit(), r.output());
        var reader = new MetadataReader();
        var track = reader.read("t", "tagged.flac", tagged, Files.size(tagged), Files.getLastModifiedTime(tagged).toMillis());
        byte[] before = Files.readAllBytes(tagged);
        var options = new AudioSettings();
        options.neutral = false;
        options.normalization = true;
        try (var cache = new LoudnessCache()) {
            double gain = 1;
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (gain == 1 && System.nanoTime() < deadline) {
                gain = cache.gain(track, options);
                Thread.sleep(20);
            }
            assertEquals(Math.pow(10, -1 / 20.0) / .5, gain, .01, "Tagged sample peak must constrain gain");
            assertArrayEquals(before, Files.readAllBytes(tagged));
            Await.of(() -> Files.isRegularFile(temp.resolve("loudness-cache.json")));
            options.analyzeLoudness = true;
            var untagged = reader.read("t", "source.wav", source, Files.size(source), Files.getLastModifiedTime(source).toMillis());
            deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                cache.gain(untagged, options);
                if (Files.readString(temp.resolve("loudness-cache.json")).contains("source.wav")) break;
                Thread.sleep(30);
            }
            assertTrue(Files.readString(temp.resolve("loudness-cache.json")).contains("source.wav"));
            Files.setLastModifiedTime(source, FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 2000));
            var changed = reader.read("t", "source.wav", source, Files.size(source), Files.getLastModifiedTime(source).toMillis());
            options.analyzeLoudness = false;
            assertEquals(1, cache.gain(changed, options), "Changed files must not reuse stale measured gain");
        }
    }
}
