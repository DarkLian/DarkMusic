package com.darkmusic.client;

import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.MetadataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class DecoderTest {
    @TempDir
    Path temp;

    @Test
    void decodesEveryRealSongWithoutChangingFiles() throws Exception {
        for (Path path : TestMusic.files()) {
            long size = Files.size(path), modified = Files.getLastModifiedTime(path).toMillis();
            try (var pcm = PcmDecoder.open(path)) {
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, pcm.getFormat().getEncoding());
                assertEquals(16, pcm.getFormat().getSampleSizeInBits());
                assertTrue(pcm.getFormat().getChannels() == 1 || pcm.getFormat().getChannels() == 2);
                assertTrue(pcm.readNBytes(32768).length > 0, path.toString());
            }
            assertEquals(size, Files.size(path));
            assertEquals(modified, Files.getLastModifiedTime(path).toMillis());
        }
        Path source = TestMusic.featured();
        Path copy = Files.copy(source, temp.resolve(source.getFileName()));
        assertTrue(copy.toFile().setReadOnly());
        try {
            new MetadataReader().read("real", copy.getFileName().toString(), copy, Files.size(copy), 0);
            try (var pcm = PcmDecoder.open(copy)) {
                assertTrue(pcm.readNBytes(1024).length > 0);
            }
            assertEquals(-1, Files.mismatch(source, copy));
        } finally {
            copy.toFile().setWritable(true);
        }
        Files.move(copy, copy.resolveSibling("closed-" + copy.getFileName()));
    }

    @Test
    void extractsRealTagsAndArtworkAndRejectsInvalidInput() throws Exception {
        Path source = TestMusic.featured();
        var reader = new MetadataReader();
        var track = reader.read("real", source.getFileName().toString(), source, Files.size(source), 0);
        assertFalse(track.title().isBlank());
        assertTrue(track.duration() > 0);
        assertNotNull(reader.artwork(source));
        Path broken = Files.writeString(temp.resolve("broken.mp3"), "This is not audio");
        assertThrows(Exception.class, () -> PcmDecoder.open(broken));
        assertEquals("broken", reader.read("real", "broken.mp3", broken, Files.size(broken), 0).title());
        assertThrows(Exception.class, () -> PcmDecoder.open(temp.resolve("missing.flac")));
    }

    @Test
    void malformedTimingFromRealAudioCannotPoisonCache() throws Exception {
        Path path = temp.resolve("zero-rate.wav");
        StorageTest.writeWav(path);
        byte[] bytes = Files.readAllBytes(path);
        Arrays.fill(bytes, 24, 32, (byte) 0);
        Files.write(path, bytes);
        var track = new MetadataReader().read("real", "zero-rate.wav", path, bytes.length, 0);
        assertTrue(Double.isFinite(track.duration()) && track.duration() >= 0);
        assertDoesNotThrow(() -> ConfigStore.JSON.toJson(track.duration()));
    }
}
