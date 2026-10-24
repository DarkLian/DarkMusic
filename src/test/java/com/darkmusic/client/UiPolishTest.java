package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.DownloadJson;
import com.darkmusic.client.gui.Help;
import com.darkmusic.client.gui.ScrollBar;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsInfo;
import com.darkmusic.client.lyrics.LyricsService;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UiPolishTest {
    @TempDir
    Path temp;

    Track track(String name) {
        return new Track(name, name + ".opus", temp.resolve(name + ".opus"), name, "", "", 10, 0, 0, "");
    }

    @Test
    void embeddedTimedLyricsAreValidatedOnAPrivateRealMusicCopy() throws Exception {
        var original = TestMusic.files().stream().filter(p -> p.toString().matches("(?i).*\\.(mp3|flac)$")).findFirst();
        org.junit.jupiter.api.Assumptions.assumeTrue(original.isPresent(), "An editable real MP3/FLAC copy is required");
        var source = original.orElseThrow();
        byte[] before = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
        Path copy = temp.resolve(source.getFileName());
        Files.copy(source, copy);
        var track = new Track("copy", copy.getFileName().toString(), copy, "", "", "", 10, 0, 0, "");
        try (var service = new LyricsService()) {
            for (String text : new String[]{"", "Plain untimed lyrics", "[00:01.00]日本語の歌詞"}) {
                var audio = org.jaudiotagger.audio.AudioFileIO.read(copy.toFile());
                var tag = audio.getTagOrCreateAndSetDefault();
                tag.setField(org.jaudiotagger.tag.FieldKey.LYRICS, text);
                audio.commit();
                service.enabled(false);
                service.enabled(true);
                service.request(track);
                StorageTest.await(() -> service.pendingCount() == 0);
                assertEquals(text.isEmpty() ? LyricsInfo.LOCAL_MISSING : text.startsWith("[") ? LyricsInfo.AVAILABLE : LyricsInfo.PLAIN, service.info(track));
            }
            assertEquals("日本語の歌詞", service.get(track).lines().getFirst().text());
        }
        assertArrayEquals(before, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)), "Original music must remain byte-identical");
    }

    @Test
    void hoverPreferenceDefaultsOnAndTooltipBoxesStayWithinViewport() {
        assertTrue(ConfigStore.JSON.fromJson("{}", Settings.class).showHoverInfo);
        var settings = new Settings();
        settings.showHoverInfo = false;
        assertFalse(ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class).showHoverInfo);
        for (int w : new int[]{160, 320, 854})
            for (int h : new int[]{120, 240, 480})
                for (int x : new int[]{0, w / 2, w - 1})
                    for (int y : new int[]{0, h / 2, h - 1}) {
                        var p = Help.position(w, h, x, y, w - 20, h - 20);
                        assertTrue(p.x() >= 4 && p.x() + w - 20 <= w - 4);
                        assertTrue(p.y() >= 4 && p.y() + h - 20 <= h - 4);
                    }
    }

    @Test
    void scrollbarThumbRetainsUsefulHitAreaAndFitsShortTracks() {
        assertEquals(12, ScrollBar.thumbLength(100, 10000, 100));
        assertEquals(50, ScrollBar.thumbLength(100, 200, 100));
        assertEquals(5, ScrollBar.thumbLength(5, 10000, 100));
        assertEquals(100, ScrollBar.thumbLength(100, 0, 100));
    }

    @Test
    void previewDoesNotCarryCaptionEvidenceEvenWhenMetadataContainsIt() {
        var plain = JsonParser.parseString("{\"id\":\"song\",\"title\":\"Song\",\"url\":\"https://example.com/song\"}").getAsJsonObject();
        var enriched = plain.deepCopy();
        enriched.add("subtitles", JsonParser.parseString("{\"ja\":[{}]}"));
        enriched.add("automatic_captions", JsonParser.parseString("{\"en\":[{}]}"));
        assertEquals(DownloadJson.preview("https://example.com/song", 1, plain), DownloadJson.preview("https://example.com/song", 1, enriched));
        assertTrue(java.util.Arrays.stream(com.darkmusic.client.download.DownloadModels.Entry.class.getRecordComponents()).noneMatch(field -> field.getName().equals("lyrics")));
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
            StorageTest.await(() -> service.pendingCount() == 0);
            assertEquals(LyricsInfo.AVAILABLE, service.info(timed));
            assertEquals(LyricsInfo.PLAIN, service.info(plain));
            assertFalse(service.document(plain).synchronizedLyrics());
            assertEquals(java.util.List.of("Untimed words"), service.document(plain).plain());
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
            StorageTest.await(() -> !service.get(timed).lines().isEmpty());
            assertEquals("Updated", service.get(timed).lines().getFirst().text());
            for (int i = 0; i < 70; i++) {
                var t = track("cache" + i);
                Files.writeString(temp.resolve("cache" + i + ".lrc"), "[00:01]Timed");
                service.request(t);
                StorageTest.await(() -> service.pendingCount() == 0);
                assertTrue(service.cachedCount() <= 64);
            }
            assertEquals(64, service.cachedCount());
        }
    }
}
