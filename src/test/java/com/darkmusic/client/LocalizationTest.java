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
    void englishCatalogIsCompleteAndTraditionalChineseIsNotBundled() throws Exception {
        var english = catalog("en_us");
        assertFalse(english.isEmpty());
        english.forEach((key, value) -> assertFalse(value.isBlank(), key));
        assertNull(Text.class.getResource("/assets/darkmusic/lang/zh_tw.json"));
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
                public String getOrDefault(String key, String fallback) { return fallback; }
                @Override
                public boolean has(String key) { return false; }
                @Override
                public boolean isDefaultRightToLeft() { return false; }
                @Override
                public FormattedCharSequence getVisualOrder(FormattedText text) { return original.getVisualOrder(text); }
            });
            assertEquals("Volume: 50%", Text.get("format.darkmusic.volume", 50));
            assertEquals("Speed: 1.5x", Text.get("format.darkmusic.speed", "1.5"));
            String filename = "歌曲 音楽.mp3";
            assertTrue(Text.get("format.darkmusic.missing_song", filename).endsWith(filename));
            for (var key : catalog("en_us").keySet()) {
                String rendered = Text.get(key, "1", "2", "3", "4");
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
