package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.media.AudioFormats;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DecoderRoutingTest {
    @Test
    void detectedWaveAndAiffIgnoreMisleadingExtensionsAndUnknownOggCanUseInstalledTools() throws Exception {
        String ff = System.getProperty("darkmusic.test.ffmpeg", ""), probe = System.getProperty("darkmusic.test.ffprobe", "");
        Assumptions.assumeFalse(ff.isBlank() || probe.isBlank(), "Explicit real codec tools required");
        Path root = Path.of(".local-tools/verification/runs").toAbsolutePath();
        Files.createDirectories(root);
        Path scratch = Files.createTempDirectory(root, "codec-routing-");
        ExternalAudio.configure(null);
        try {
            for (String container : List.of("wav", "aiff")) {
                Path source = scratch.resolve("real." + container);
                var converted = new ProcessRunner().run(List.of(ff, "-nostdin", "-v", "error", "-ss", "1", "-i", TestMusic.featured().toString(), "-t", "1", "-vn", "-c:a", container.equals("wav") ? "pcm_s16le" : "pcm_s16be", "-f", container, source.toString()), scratch, new ProcessRunner.Token(), Duration.ofSeconds(30), 8192);
                assertEquals(0, converted.exit(), converted.output());
                for (String suffix : List.of("mp3", "ogg", "flac")) {
                    Path mislabeled = Files.copy(source, scratch.resolve(container + "-misleading." + suffix));
                    try (var pcm = PcmDecoder.open(mislabeled)) {
                        assertEquals(16, pcm.getFormat().getSampleSizeInBits());
                        assertTrue(pcm.readNBytes(4096).length > 0);
                    }
                }
            }
            Path ogg = scratch.resolve("ogg-flac.ogg");
            var converted = new ProcessRunner().run(List.of(ff, "-nostdin", "-v", "error", "-ss", "1", "-i", TestMusic.featured().toString(), "-t", "1", "-vn", "-c:a", "flac", "-f", "ogg", ogg.toString()), scratch, new ProcessRunner.Token(), Duration.ofSeconds(30), 8192);
            assertEquals(0, converted.exit(), converted.output());
            assertEquals(AudioFormats.Container.UNKNOWN, AudioFormats.detect(ogg));
            ExternalAudio.configure(() -> new ExternalAudio.Paths(scratch, ff, probe));
            try (var pcm = PcmDecoder.open(ogg)) {
                assertEquals(16, pcm.getFormat().getSampleSizeInBits());
                assertTrue(pcm.readNBytes(4096).length > 0);
            }
        } finally {
            ExternalAudio.configure(null);
            try (var paths = Files.walk(scratch)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
