package com.darkmusic.client.media;

import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class IndexedSeekTest {
    @TempDir
    Path directory;

    @Test
    @Timeout(40)
    void indexedSeekingMatchesLinearDecodedAudioAcrossRealCodecs() throws Exception {
        String ff = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeFalse(ff.isBlank(), "Real codec tools are opt-in");
        ExternalAudio.configure(() -> new ExternalAudio.Paths(directory, ff, System.getProperty("darkmusic.test.ffprobe")));
        try {
            for (String extension : List.of("wav", "flac", "opus", "mp3")) {
                Path clip = directory.resolve("collection." + extension);
                var command = new ArrayList<>(List.of(ff, "-nostdin", "-v", "error", "-ss", "30", "-i", TestMusic.featured().toString(), "-t", "8", "-vn", "-map_metadata", "-1"));
                if (extension.equals("wav")) command.addAll(List.of("-c:a", "pcm_s24le"));
                command.add(clip.toString());
                var result = new ProcessRunner().run(command, directory, new ProcessRunner.Token(), Duration.ofSeconds(20), 16000);
                assertEquals(0, result.exit(), result.output());
                for (double seconds : new double[]{1.25, 5.1}) {
                    byte[] expected;
                    int frame;
                    float rate;
                    try (var input = ExternalAudio.open(clip)) {
                        frame = input.getFormat().getFrameSize();
                        rate = input.getFormat().getFrameRate();
                        input.skipNBytes((long) (seconds * rate) * frame);
                        input.readNBytes((int) (rate * .25) * frame); // Allow lossy decoder preroll to settle.
                        expected = input.readNBytes((int) rate * frame);
                    }
                    var opened = ExternalAudio.openAt(clip, seconds);
                    assertEquals(Math.floor(seconds * rate) / rate, opened.offset(), 1e-10);
                    byte[] actual;
                    try (var input = opened.stream()) {
                        input.readNBytes((int) (rate * .25) * frame);
                        actual = input.readNBytes(expected.length);
                    }
                    assertEquals(expected.length, actual.length);
                    var a = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                    var b = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                    double error = 0, power = 0;
                    while (a.hasRemaining()) {
                        double x = a.get(), y = b.get();
                        assertTrue(Double.isFinite(y));
                        error += (x - y) * (x - y);
                        power += x * x;
                    }
                    assertTrue(error / Math.max(1e-20, power) < .002, extension + " seek error ratio=" + error / Math.max(1e-20, power));
                }
            }
        } finally {
            ExternalAudio.configure(null);
        }
    }
}
