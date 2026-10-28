package com.darkmusic.client;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.VisualQuality;
import com.darkmusic.client.download.DownloadPipeline;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class ReviewRegressionTest {
    @TempDir
    Path temp;

    @Test
    void profileSwitchClearsOldEqAndCustomVisualsCyclePredictably() {
        var audio = new AudioSettings();
        for (String profile : new String[]{"DEFAULT", "MUSIC", "GAMING", "NIGHT"}) {
            audio.selectProfile("VOCAL");
            assertNotEquals(0, audio.bands[0]);
            audio.selectProfile(profile);
            audio.equalizer = true;
            assertArrayEquals(new double[10], audio.bands);
            assertEquals("FLAT", audio.eqPreset);
        }
        var quality = new VisualQuality();
        quality.glow = true;
        assertEquals(VisualQuality.Preset.CUSTOM, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.STANDARD, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.ENHANCED, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.EXTREME, quality.preset());
        quality.next();
        assertEquals(VisualQuality.Preset.STANDARD, quality.preset());
    }

    @Test
    void unchangedLyricsReuseParsedDataAndChangedOrRemovedSidecarInvalidatesCache() throws Exception {
        Path audio = temp.resolve("Song.opus"), sidecar = temp.resolve("Song.lrc");
        Files.writeString(sidecar, "[00:01.00]First line");
        var track = new Track("test", "Song.opus", audio, "Song", "", "", 4, 0, 0, "");
        try (var lyrics = new LyricsService()) {
            lyrics.request(track);
            StorageTest.await(() -> !lyrics.get(track).lines().isEmpty());
            var original = lyrics.get(track);
            lyrics.enabled(false);
            lyrics.enabled(true);
            lyrics.request(track);
            StorageTest.await(() -> !lyrics.get(track).lines().isEmpty());
            assertSame(original, lyrics.get(track), "Unchanged lyrics must not be reparsed");
            Files.writeString(sidecar, "[00:02.00]Other line");
            Files.setLastModifiedTime(sidecar, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            lyrics.enabled(false);
            lyrics.enabled(true);
            lyrics.request(track);
            StorageTest.await(() -> !lyrics.get(track).lines().isEmpty());
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
    void explicitFilenameExtensionKeepsExistingMp3IdentityAndRejectsPaths() {
        String old = DownloadPipeline.filename("音樂 / Song", "same-id");
        assertEquals(old, DownloadPipeline.filename("音樂 / Song", "same-id", "mp3"));
        assertEquals(old.substring(0, old.length() - 4) + ".opus", DownloadPipeline.filename("音樂 / Song", "same-id", "opus"));
        assertThrows(IllegalArgumentException.class, () -> DownloadPipeline.filename("Song", "id", "../wav"));
    }
}
