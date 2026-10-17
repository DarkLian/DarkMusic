package com.darkmusic.client.text;

import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Localized UI prose with bundled English fallbacks; user metadata is never translated.
 */
public final class Text {
    private static final Map<String, String> ENGLISH = english();

    private Text() {
    }

    private static Map<String, String> english() {
        try (var input = Text.class.getResourceAsStream("/assets/darkmusic/lang/en_us.json")) {
            if (input == null) throw new IllegalStateException("Missing DarkMusic English translations");
            var entries = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> result = new HashMap<>();
            entries.entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsString()));
            return Map.copyOf(result);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read DarkMusic translations", e);
        }
    }

    public static MutableComponent component(String key, Object... args) {
        return Component.translatableWithFallback(key, ENGLISH.getOrDefault(key, key), args);
    }

    public static String get(String key, Object... args) {
        return component(key, args).getString();
    }
}
