package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.tools.DownloadDiagnostics;
import com.darkmusic.client.download.DownloadLyrics;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.config.LyricsLanguages;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.Toolchain;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.library.Track;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LyricsTest {
    @TempDir
    Path temp;

    @Test
    void choosesOneManualLanguageAndNeverRequestsAutomaticCaptionsOrChat() {
        var metadata = JsonParser.parseString("{\"language\":\"ja\",\"subtitles\":{\"live_chat\":[{}],\"ja\":[{}],\"en\":[{}]},\"automatic_captions\":{\"fr\":[{}]}}").getAsJsonObject();
        assertEquals("ja", LyricsLanguages.select(metadata, List.of("ja")));
        metadata.remove("subtitles");
        assertEquals("", DownloadLyrics.language(metadata));
    }

    @Test
    void captionDownloadConvertsToTimedLrcAndFailureRemainsOptional() throws Exception {
        var metadata = JsonParser.parseString("{\"subtitles\":{\"en\":[{}]}}").getAsJsonObject();
        var tools = new Toolchain(temp.resolve("yt-dlp"), temp.resolve("ffmpeg"), temp.resolve("ffprobe"), "", "");
        var entry = new DownloadModels.Entry(1, "Song", "https://example.com/song", "test", "song", true);
        var runner = new ProcessRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) throws IOException {
                assertTrue(args.contains("--skip-download"));
                assertTrue(args.contains("--no-write-auto-subs"));
                assertEquals("lrc", args.get(args.indexOf("--convert-subs") + 1));
                Files.writeString(dir.resolve("lyrics.en.lrc"), "[00:01.00]Timed caption\n[00:02.00]Next line");
                return new Result(0, "");
            }
        };
        var result = DownloadLyrics.fetch(runner, tools, entry, metadata, temp, new ProcessRunner.Token());
        assertTrue(result.warning().isBlank());
        assertTrue(result.text().contains("Timed caption"));
        var failed = new ProcessRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) {
                return new Result(1, "HTTP 429");
            }
        };
        var failedCaptions = DownloadLyrics.fetch(failed, tools, entry, metadata, temp, new ProcessRunner.Token());
        assertFalse(failedCaptions.warning().isBlank());
        assertEquals(DownloadDiagnostics.Category.RATE_LIMITED, failedCaptions.failure());
        var cancelled = new ProcessRunner.Token();
        cancelled.cancel();
        assertThrows(Exception.class, () -> DownloadLyrics.fetch(runner, tools, entry, metadata, temp, cancelled));
    }

    @Test
    void masterDisablesLoadingAndHudSpaceWithoutDeletingFilesAndCanBeReenabled() throws Exception {
        Path audio = temp.resolve("Song.opus"), file = temp.resolve("Song.lrc");
        Files.writeString(file, "[00:01.00]First line");
        var track = new Track("test", "Song.opus", audio, "Song", "Artist", "Album", 10, 1, 1, "");
        try (var service = new LyricsService()) {
            service.request(track);
            Await.of(() -> !service.get(track).lines().isEmpty());
            service.enabled(false);
            service.request(track);
            assertTrue(service.get(track).lines().isEmpty());
            assertTrue(Files.exists(file));
            Files.writeString(file, "[00:02.00]Updated line");
            service.enabled(true);
            service.request(track);
            Await.of(() -> !service.get(track).lines().isEmpty());
            assertEquals("Updated line", service.get(track).lines().getFirst().text());
        }
        var settings = ConfigStore.JSON.fromJson("{\"lyricsHud\":true}", Settings.class);
        assertFalse(settings.lyricsEnabled);
        settings.lyricsEnabled = true;
        int enabled = HudLayout.content(settings, 1000).height();
        settings.lyricsEnabled = false;
        assertEquals(enabled, HudLayout.content(settings, 1000).height());
        assertFalse(ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class).lyricsEnabled);
    }
}
