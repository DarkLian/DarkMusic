package com.darkmusic.client.lyrics;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.TestMusic;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class LyricsServiceTest {
    @TempDir
    Path temp;

    @Test
    void unchangedLyricsReuseParsedDataAndChangedOrRemovedSidecarInvalidatesCache() throws Exception {
        Path audio = temp.resolve("Song.opus"), sidecar = temp.resolve("Song.lrc");
        Files.writeString(sidecar, "[00:01.00]First line");
        var track = new Track("test", "Song.opus", audio, "Song", "", "", 4, 0, 0, "");
        try (var lyrics = new LyricsService()) {
            lyrics.request(track);
            Await.of(() -> !lyrics.get(track).lines().isEmpty());
            var original = lyrics.get(track);
            lyrics.enabled(false);
            lyrics.enabled(true);
            lyrics.request(track);
            Await.of(() -> !lyrics.get(track).lines().isEmpty());
            assertSame(original, lyrics.get(track), "Unchanged lyrics must not be reparsed");
            Files.writeString(sidecar, "[00:02.00]Other line");
            Files.setLastModifiedTime(sidecar, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            lyrics.enabled(false);
            lyrics.enabled(true);
            lyrics.request(track);
            Await.of(() -> !lyrics.get(track).lines().isEmpty());
            assertEquals("Other line", lyrics.get(track).lines().getFirst().text());
            Files.delete(sidecar);
            lyrics.enabled(false);
            lyrics.enabled(true);
            lyrics.request(track);
            var worker = LyricsService.class.getDeclaredField("io");
            worker.setAccessible(true);
            ((ExecutorService) worker.get(lyrics)).submit(() -> {
            }).get();
            assertTrue(lyrics.get(track).lines().isEmpty());
        }
    }

    @Test
    void embeddedTimedLyricsAreValidatedOnAPrivateRealMusicCopy() throws Exception {
        var original = TestMusic.files().stream().filter(p -> p.toString().matches("(?i).*\\.(mp3|flac)$")).findFirst();
        Assumptions.assumeTrue(original.isPresent(), "An editable real MP3/FLAC copy is required");
        var source = original.orElseThrow();
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
        Path copy = temp.resolve(source.getFileName());
        Files.copy(source, copy);
        var track = new Track("copy", copy.getFileName().toString(), copy, "", "", "", 10, 0, 0, "");
        try (var service = new LyricsService()) {
            for (String text : new String[]{"", "Plain untimed lyrics", "[00:01.00]日本語の歌詞"}) {
                var audio = AudioFileIO.read(copy.toFile());
                var tag = audio.getTagOrCreateAndSetDefault();
                tag.setField(FieldKey.LYRICS, text);
                audio.commit();
                service.enabled(false);
                service.enabled(true);
                service.request(track);
                Await.of(() -> service.pendingCount() == 0);
                assertEquals(text.isEmpty() ? LyricsInfo.LOCAL_MISSING : text.startsWith("[") ? LyricsInfo.AVAILABLE : LyricsInfo.PLAIN, service.info(track));
            }
            assertEquals("日本語の歌詞", service.get(track).lines().getFirst().text());
        }
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)), "Original music must remain byte-identical");
    }

    Track track(String name) {
        return new Track(name, name + ".opus", temp.resolve(name + ".opus"), name, "", "", 10, 0, 0, "");
    }

    @Test
    void localChecksRemainTrackKeyedBoundedAndCancelWithoutDeletingLyrics() throws Exception {
        var timed = track("timed");
        var plain = track("plain");
        var absent = track("absent");
        Files.writeString(temp.resolve("timed.lrc"), "[00:01]本当の歌詞");
        Files.writeString(temp.resolve("plain.lrc"), "Untimed words");
        Files.writeString(temp.resolve("absent.lrc"), "");
        try (var service = new LyricsService()) {
            service.request(timed);
            service.request(plain);
            service.request(absent);
            Await.of(() -> service.pendingCount() == 0);
            assertEquals(LyricsInfo.AVAILABLE, service.info(timed));
            assertEquals(LyricsInfo.PLAIN, service.info(plain));
            assertFalse(service.document(plain).synchronizedLyrics());
            assertEquals(List.of("Untimed words"), service.document(plain).plain());
            assertEquals(LyricsInfo.LOCAL_MISSING, service.info(absent));
            assertEquals(LyricsInfo.State.UNKNOWN, service.info(absent).state());
            assertEquals("?", service.info(absent).mark());
            assertEquals(LyricsInfo.UNKNOWN, service.info(track("missing")));
            for (int i = 0; i < 1000; i++) {
                service.inspect(track("unknown" + i));
                assertTrue(service.pendingCount() <= 8);
            }
            service.enabled(false);
            service.request(timed);
            assertEquals(0, service.pendingCount());
            assertEquals(0, service.cachedCount());
            assertEquals(LyricsInfo.UNKNOWN, service.info(timed));
            assertTrue(Files.exists(temp.resolve("timed.lrc")));
            Files.writeString(temp.resolve("timed.lrc"), "[00:02]Updated");
            service.enabled(true);
            service.request(timed);
            Await.of(() -> !service.get(timed).lines().isEmpty());
            assertEquals("Updated", service.get(timed).lines().getFirst().text());
            for (int i = 0; i < 70; i++) {
                var t = track("cache" + i);
                Files.writeString(temp.resolve("cache" + i + ".lrc"), "[00:01]Timed");
                service.request(t);
                Await.of(() -> service.pendingCount() == 0);
                assertTrue(service.cachedCount() <= 64);
            }
            assertEquals(64, service.cachedCount());
        }
    }
}
