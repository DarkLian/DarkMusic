package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LyricsLanguageTest {
    @TempDir
    Path temp;

    private JsonObject metadata(String subtitles) {
        return JsonParser.parseString("{\"language\":\"fr\",\"subtitles\":" + subtitles + "}").getAsJsonObject();
    }

    @Test
    void preferencesAreOrderedAndIgnoreVideoMetadataLanguage() {
        var info = metadata("{\"fr\":[{}],\"en\":[{}],\"ja\":[{}]}");
        assertEquals("ja", LyricsLanguages.select(info, LyricsLanguages.resolve("ja, en", "fr")));
        assertEquals("en", LyricsLanguages.select(info, List.of("ko", "en")));
        assertEquals("", LyricsLanguages.select(info, List.of("ko")));
    }

    @Test
    void autoUsesTheSuppliedMinecraftLanguageWithoutEnglishOrAlphabeticalFallback() {
        var info = metadata("{\"de\":[{}],\"en\":[{}],\"ja\":[{}]}");
        assertEquals("ja", LyricsLanguages.select(info, LyricsLanguages.resolve("auto", "ja_jp")));
        assertEquals("", LyricsLanguages.select(info, LyricsLanguages.resolve("auto", "ko_kr")));
        assertEquals(List.of("ja", "zh-TW", "en"), LyricsLanguages.resolve("ja, auto, en", "zh_tw"));
    }

    @Test
    void exactRegionalMatchWinsAndExplicitFallbackOrderIsRespected() {
        assertEquals("en-US", LyricsLanguages.select(metadata("{\"en-GB\":[{}],\"en-US\":[{}],\"en\":[{}]}"), List.of("en-US")));
        assertEquals("en", LyricsLanguages.select(metadata("{\"en-GB\":[{}],\"en\":[{}]}"), List.of("en-US")));
        assertEquals("", LyricsLanguages.select(metadata("{\"en-GB\":[{}]}"), List.of("en-US")));
        assertEquals("en-GB", LyricsLanguages.select(metadata("{\"en-GB\":[{}]}"), List.of("en-US", "en")));
    }

    @Test
    void scriptAndChineseRegionalVariantsNeverCrossAnExplicitScriptPreference() {
        assertEquals("zh-TW", LyricsLanguages.select(metadata("{\"zh-CN\":[{}],\"zh-TW\":[{}]}"), List.of("zh-Hant")));
        assertEquals("zh-Hant", LyricsLanguages.select(metadata("{\"zh-Hans\":[{}],\"zh-Hant\":[{}]}"), List.of("zh-TW")));
        assertEquals("", LyricsLanguages.select(metadata("{\"zh-Hans\":[{}],\"zh\":[{}]}"), List.of("zh-TW")));
        assertEquals("", LyricsLanguages.select(metadata("{\"sr-Cyrl\":[{}]}"), List.of("sr-Latn")));
    }

    @Test
    void automaticAndMachineTranslatedTracksCannotBecomeManualSongLanguageEvidence() {
        var info = metadata("{\"live_chat\":[{}],\"en\":[{\"url\":\"https://example.com/c?tlang=en\"}],\"de\":[{\"is_translated\":true}],\"fr\":[{\"kind\":\"asr\"}],\"ja\":[{}]}");
        info.add("automatic_captions", JsonParser.parseString("{\"ko\":[{}]}"));
        assertEquals("", LyricsLanguages.select(info, List.of("en", "de", "fr", "ko")));
        assertEquals("ja", LyricsLanguages.select(info, List.of("en", "ja")));
        assertEquals("", LyricsLanguages.select(metadata("{\"en\":[{\"url\":\"https://example.com/c?%74lang=en\"}]}"), List.of("en")));
    }

    @Test
    void invalidPreferencesCannotBecomeToolArgumentsAndOldSettingsDefaultToAuto() {
        assertEquals("zh-TW, ja, auto", LyricsLanguages.normalize("zh_tw, JA, auto, ja"));
        for (String invalid : List.of("en.*", "all", "en,", "../en", "--exec", "en;ja"))
            assertThrows(IllegalArgumentException.class, () -> LyricsLanguages.normalize(invalid));
        var old = ConfigStore.JSON.fromJson("{}", Settings.class);
        old.validate();
        assertEquals("auto", old.lyricsLanguages);
        old.lyricsLanguages = "ja, zh-Hant, en";
        var restored = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(old), Settings.class);
        restored.validate();
        assertEquals(old.lyricsLanguages, restored.lyricsLanguages);
        restored.lyricsLanguages = "all";
        restored.validate();
        assertEquals("auto", restored.lyricsLanguages);
    }

    @Test
    void anUnmatchedLanguageDoesNotInvokeAnySubtitleDownload() throws Exception {
        var runner = new ProcessRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, java.util.function.Consumer<String> line) {
                fail("No process should run for an unacceptable language");
                return null;
            }
        };
        var result = DownloadLyrics.fetch(runner, new Toolchain(temp.resolve("yt-dlp"), temp.resolve("ffmpeg"), temp.resolve("ffprobe"), "", ""), new DownloadModels.Entry(1, "Song", "https://example.com/song", "test", "song", true), metadata("{\"fr\":[{}]}"), temp, new ProcessRunner.Token(), List.of("ja"));
        assertEquals("", result.text());
        assertFalse(result.warning().isBlank());
    }
}
