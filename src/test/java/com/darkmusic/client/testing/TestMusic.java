package com.darkmusic.client.testing;

import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.library.MetadataReader;
import org.junit.jupiter.api.Assumptions;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Audio tests share the private collection used for playtests and screenshots.
 */
public final class TestMusic {
    private static Clip cached;

    public static Path root() {
        return Path.of(System.getProperty("darkmusic.test.music", "run/config/darkmusic/music")).toAbsolutePath();
    }

    public static List<Path> files() throws Exception {
        List<Path> files = List.of();
        if (Files.isDirectory(root())) try (var paths = Files.walk(root())) {
            files = paths.filter(Files::isRegularFile).filter(p -> p.toString().matches("(?i).*\\.(mp3|flac|wav|ogg)$")).sorted().toList();
        }
        String reason = "Private music unavailable; set DARKMUSIC_TEST_MUSIC or -PmusicDirectory";
        if (files.isEmpty() && Files.isDirectory(root())) try (var paths = Files.walk(root())) {
            // An Opus-only collection is present but unreadable here; verify.ps1 prepares MP3 excerpts from it.
            if (paths.anyMatch(p -> p.toString().matches("(?i).*\\.(opus|m4a|aac|mp4|webm)$")))
                reason = "The collection at " + root() + " has no MP3/FLAC/WAV/OGG files; run scripts/verify.ps1, which prepares real-audio excerpts";
        }
        if (Boolean.getBoolean("darkmusic.test.requireMusic") && files.isEmpty())
            throw new AssertionError("Real music required: " + reason);
        Assumptions.assumeFalse(files.isEmpty(), reason);
        return files;
    }

    public static Path featured() throws Exception {
        var files = files();
        return files.stream().filter(p -> p.getFileName().toString().contains("Departure")).findFirst().orElse(files.getFirst());
    }

    public static synchronized Clip clip() throws Exception {
        if (cached == null) {
            Path file = featured();
            var metadata = new MetadataReader().read("real", file.getFileName().toString(), file, Files.size(file), 0);
            try (var pcm = PcmDecoder.open(file)) {
                var format = pcm.getFormat();
                long remaining = (long) (Math.min(30, Math.max(0, metadata.duration() - 5)) * format.getFrameRate()) * format.getFrameSize();
                byte[] discard = new byte[16_384 / format.getFrameSize() * format.getFrameSize()];
                while (remaining > 0) {
                    int read = pcm.read(discard, 0, (int) Math.min(remaining, discard.length));
                    if (read < 0)
                        throw new EOFException("Real music excerpt ended before the requested position");
                    remaining -= read;
                }
                byte[] bytes = pcm.readNBytes((int) format.getFrameRate() * format.getFrameSize() * 4);
                if (bytes.length < 16384)
                    throw new AssertionError("Choose a real song with at least four seconds of audio");
                cached = new Clip(bytes, format);
            }
        }
        return cached;
    }

    /**
     * Writes the clip as a WAV file, for tests that need a real song on disk.
     */
    public static void writeWav(Path path) throws Exception {
        try (var stream = clip().stream()) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    public record Clip(byte[] bytes, AudioFormat format) {
        public AudioInputStream stream() {
            return new AudioInputStream(new ByteArrayInputStream(bytes), format, bytes.length / format.getFrameSize());
        }
    }
}
