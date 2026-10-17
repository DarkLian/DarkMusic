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
    void englishAndTraditionalChineseHaveMatchingKeysAndPlaceholders() throws Exception {
        var english = catalog("en_us");
        var chinese = catalog("zh_tw");
        assertEquals(english.keySet(), chinese.keySet());
        for (var key : english.keySet()) {
            assertFalse(chinese.get(key).isBlank(), key);
            assertEquals(english.get(key).replace("%%", "").split("%s", -1).length,
                chinese.get(key).replace("%%", "").split("%s", -1).length, key);
        }
    }

    @Test
    void allDynamicHudChoicesHaveTranslations() throws Exception {
        var english = catalog("en_us");
        var chinese = catalog("zh_tw");
        for (var choice : com.darkmusic.client.config.Settings.SpectrumPosition.values()) {
            String key = "hud.darkmusic.position." + choice.name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(english.containsKey(key));
            assertTrue(chinese.containsKey(key));
        }
        for (var choice : com.darkmusic.client.config.Settings.SpectrumDirection.values()) {
            String key = "hud.darkmusic.direction." + choice.name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(english.containsKey(key));
            assertTrue(chinese.containsKey(key));
        }
    }

    @Test
    void translationsAndEnglishFallbackPreserveFormattingAndUserMetadata() throws Exception {
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
            assertEquals("\u958b\u555f\u97f3\u6a02\u64ad\u653e\u5668", Text.get("key.darkmusic.open"));
            assertEquals("\u97f3\u91cf\uff1a50%", Text.get("format.darkmusic.volume", 50));
            assertTrue(Text.get("format.darkmusic.missing_song", "Private 429 music.mp3").endsWith("Private 429 music.mp3"));
            assertEquals("DarkMusic", Text.get("key.category.darkmusic.controls"));
            for (var key : chinese.keySet()) {
                String rendered = Text.get(key, "1", "2", "3", "4");
                assertNotEquals(rendered, key, key);
                assertFalse(rendered.contains("%s"), key);
                assertFalse(rendered.contains("%%"), key);
            }
            Language.inject(original);
            assertEquals("Volume: 50%", Text.get("format.darkmusic.volume", 50));
            assertEquals("Speed: 1.5x", Text.get("format.darkmusic.speed", "1.5"));
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
