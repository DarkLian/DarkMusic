package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.library.*;
import com.darkmusic.client.lyrics.*;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OpusMetadataRegressionTest {
    @TempDir
    Path temp;

    @Test
    void opusTagsDurationAndLyricsNeedNoProbeAndTinyExternalSeekIsAccepted() throws Exception {
        String ff = System.getProperty("darkmusic.test.ffmpeg", ""), probe = System.getProperty("darkmusic.test.ffprobe", "");
        Assumptions.assumeFalse(ff.isBlank() || probe.isBlank(), "Private audio/existing codecs required");
        Path file = temp.resolve("derived.opus");
        var result = new ProcessRunner().run(List.of(ff, "-nostdin", "-v", "error", "-i", TestMusic.featured().toString(), "-t", "3", "-map", "0:a:0", "-vn", "-c:a", "libopus", "-metadata", "title=Beyoncé", "-metadata", "artist=Artist", "-metadata", "album=Album", "-metadata", "lyrics=[00:01]Embedded words", "-metadata", "language=ja", file.toString()), temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 8192, l -> {
        });
        assertEquals(0, result.exit(), result.output());
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, temp.resolve("missing-ffmpeg").toString(), temp.resolve("missing-ffprobe").toString()));
        try {
            var track = new MetadataReader().read("r", "derived.opus", file, Files.size(file), Files.getLastModifiedTime(file).toMillis());
            assertEquals("", track.problem());
            assertEquals("Beyoncé", track.title());
            assertEquals("Artist", track.artist());
            assertEquals(3, track.duration(), .03);
            try (var service = new LyricsService()) {
                service.inspect(track);
                Await.until(Duration.ofSeconds(5), () -> service.inspected(track), "Native Opus lyric read completes");
                assertEquals("Embedded words", service.get(track).lines().getFirst().text());
            }
            ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, ff, probe));
            try (var stream = ExternalAudio.openAt(file, 22.0 / 44100).stream()) {
                assertTrue(stream.readNBytes(4096).length > 0, "Sub-millisecond decimal offset is accepted by the real FFmpeg");
            }
            Path bare = temp.resolve("no-lyrics.opus");
            result = new ProcessRunner().run(List.of(ff, "-nostdin", "-v", "error", "-i", file.toString(), "-map_metadata", "-1", "-c:a", "copy", bare.toString()), temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 8192, l -> {
            });
            assertEquals(0, result.exit(), result.output());
            var absent = new MetadataReader().read("r", "no-lyrics.opus", bare, Files.size(bare), Files.getLastModifiedTime(bare).toMillis());
            try (var service = new LyricsService()) {
                service.inspect(absent);
                Await.until(Duration.ofSeconds(5), () -> service.inspected(absent), "Missing tags checked");
                assertEquals(LyricsInfo.LOCAL_MISSING, service.info(absent));
            }
        } finally {
            ExternalAudio.configure(null);
            Files.deleteIfExists(file);
            Files.deleteIfExists(temp.resolve("no-lyrics.opus"));
        }
    }
}
