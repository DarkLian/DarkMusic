package com.darkmusic.client;

import com.darkmusic.client.audio.PlaybackController.Repeat;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalizationTest {
    @Test
    void playerFacingFailuresAreLocalizedWithoutRawExceptionNames() throws Exception {
        Language original = Language.getInstance();
        try {
            for (String locale : List.of("en_us", "zh_tw")) {
                var messages = catalog(locale);
                Language.inject(new Language() {
                    public String getOrDefault(String key, String fallback) {
                        return messages.getOrDefault(key, fallback);
                    }

                    public boolean has(String key) {
                        return messages.containsKey(key);
                    }

                    public boolean isDefaultRightToLeft() {
                        return false;
                    }

                    public FormattedCharSequence getVisualOrder(FormattedText text) {
                        return original.getVisualOrder(text);
                    }
                });
                for (String key : List.of("download.queue_capacity", "download.history_read", "download.processing_failed", "tools.error.integrity", "audio.error.device", "preset.name_too_long")) {
                    String value = Text.get(key, 32);
                    assertTrue(Text.isMessage(value), key);
                    assertEquals(value, com.darkmusic.client.download.DownloadJson.friendly(value));
                }
                String unknown = com.darkmusic.client.download.DownloadJson.friendly("java.io.IOException: internal object failure");
                assertEquals(Text.get("download.error.unknown"), unknown);
                assertFalse(unknown.contains("IOException"));
            }
        } finally {
            Language.inject(original);
        }
    }

    private Map<String, String> catalog(String locale) throws Exception {
        try (var input = Text.class.getResourceAsStream("/assets/darkmusic/lang/" + locale + ".json")) {
            assertNotNull(input);
            var result = new LinkedHashMap<String, String>();
            JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()
                .entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsString()));
            return result;
        }
    }

    @Test
    void englishAndTraditionalChineseCatalogsHaveMatchingKeysAndPlaceholders() throws Exception {
        var english = catalog("en_us");
        assertFalse(english.isEmpty());
        english.forEach((key, value) -> assertFalse(value.isBlank(), key));
        var chinese = catalog("zh_tw");
        assertEquals(english.keySet(), chinese.keySet());
        var placeholders = java.util.regex.Pattern.compile("%(?:[0-9]+\\$)?[a-zA-Z%]");
        english.forEach((key, value) -> {
            assertFalse(chinese.get(key).isBlank(), key);
            assertEquals(placeholders.matcher(value).results().map(java.util.regex.MatchResult::group).toList(),
                placeholders.matcher(chinese.get(key)).results().map(java.util.regex.MatchResult::group).toList(), key);
            assertEquals(value.chars().filter(c -> c == '\n').count(),
                chinese.get(key).chars().filter(c -> c == '\n').count(), key);
        });
    }


    @Test
    void traditionalChineseRendersThroughTheGameTranslationPath() throws Exception {
        Language original = Language.getInstance();
        var chinese = catalog("zh_tw");
        try {
            Language.inject(new Language() {
                @Override
                public String getOrDefault(String key, String fallback) {
                    return chinese.getOrDefault(key, fallback);
                }

                @Override
                public boolean has(String key) {
                    return chinese.containsKey(key);
                }

                @Override
                public boolean isDefaultRightToLeft() {
                    return false;
                }

                @Override
                public FormattedCharSequence getVisualOrder(FormattedText text) {
                    return original.getVisualOrder(text);
                }
            });
            assertEquals("音量：50%", Text.get("format.darkmusic.volume", 50));
            assertEquals("速度：1.5x", Text.get("format.darkmusic.speed", "1.5"));
            assertEquals("[遺失] 私人歌曲.mp3", Text.get("format.darkmusic.missing_song", "私人歌曲.mp3"));
            chinese.forEach((key, value) -> {
                String rendered = Text.get(key, "1", "2", "3", "4", "5", "6", "7", "8");
                assertNotEquals(key, rendered, key);
                assertFalse(rendered.contains("%s"), key);
            });
        } finally {
            Language.inject(original);
        }
    }

    @Test
    void allDynamicHudChoicesHaveEnglishLabels() throws Exception {
        var english = catalog("en_us");
        for (var choice : Settings.SpectrumPosition.values())
            assertTrue(english.containsKey("hud.darkmusic.position." + choice.name().toLowerCase(java.util.Locale.ROOT)));
        for (var choice : Settings.SpectrumDirection.values())
            assertTrue(english.containsKey("hud.darkmusic.direction." + choice.name().toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    void untranslatedGameLanguagesUseEnglishWithoutChangingUserMetadata() throws Exception {
        Language original = Language.getInstance();
        try {
            Language.inject(new Language() {
                @Override
                public String getOrDefault(String key, String fallback) {
                    return fallback;
                }

                @Override
                public boolean has(String key) {
                    return false;
                }

                @Override
                public boolean isDefaultRightToLeft() {
                    return false;
                }

                @Override
                public FormattedCharSequence getVisualOrder(FormattedText text) {
                    return original.getVisualOrder(text);
                }
            });
            assertEquals("Volume: 50%", Text.get("format.darkmusic.volume", 50));
            assertEquals("Speed: 1.5x", Text.get("format.darkmusic.speed", "1.5"));
            String filename = "歌曲 音楽.mp3";
            assertTrue(Text.get("format.darkmusic.missing_song", filename).endsWith(filename));
            for (var key : catalog("en_us").keySet()) {
                String rendered = Text.get(key, "1", "2", "3", "4", "5", "6", "7", "8");
                assertNotEquals(key, rendered, key);
                assertFalse(rendered.contains("%s"), key);
            }
        } finally {
            Language.inject(original);
        }
    }

    @Test
    void enumSettingsReadLegacyAndMalformedValuesWithoutDiscardingPreferences() {
        for (var repeat : Repeat.values()) {
            Settings settings = ConfigStore.JSON.fromJson("{\"repeat\":\"" + repeat + "\",\"volume\":0.32}", Settings.class);
            settings.validate();
            assertSame(repeat, settings.repeat);
            assertEquals(.32, settings.volume);
            assertTrue(ConfigStore.JSON.toJson(settings).contains("\"" + repeat + "\""));
        }
        for (String invalid : List.of("null", "123", "{}", "\"unknown\"")) {
            Settings settings = ConfigStore.JSON.fromJson("{\"repeat\":" + invalid + ",\"volume\":0.32}", Settings.class);
            settings.validate();
            assertSame(Repeat.OFF, settings.repeat);
            assertEquals(.32, settings.volume);
        }
    }
}
