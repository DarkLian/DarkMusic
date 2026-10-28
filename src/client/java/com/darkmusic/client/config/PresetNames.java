package com.darkmusic.client.config;

import com.darkmusic.client.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared naming rules; mutations remain explicit at the caller.
 */
public final class PresetNames {
    public static final int MAX_LENGTH = 80, ENVIRONMENT_LIMIT = 32;

    private PresetNames() {
    }

    public static String valid(String input) {
        return valid(input, MAX_LENGTH);
    }

    public static String valid(String input, int maxLength) {
        String name = input == null ? "" : input.strip();
        if (name.isBlank() || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(Text.get("preset.invalid_name"));
        if (name.length() > maxLength)
            throw new IllegalArgumentException(Text.get("preset.name_too_long", maxLength));
        return name;
    }

    public static String existing(Map<String, ?> presets, String name) {
        return presets.keySet().stream().filter(k -> k.strip().equalsIgnoreCase(name.strip())).findFirst().orElse(null);
    }

    public static <T> void save(Map<String, T> presets, String input, T value, int limit, boolean replace) {
        save(presets, input, value, limit, replace, MAX_LENGTH);
    }

    public static <T> void save(Map<String, T> presets, String input, T value, int limit, boolean replace, int maxLength) {
        String name = valid(input, maxLength), old = existing(presets, name);
        if (old != null && !replace) throw new IllegalArgumentException(Text.get("preset.name_exists"));
        if (old == null && presets.size() >= limit) throw new IllegalArgumentException(Text.get("preset.limit", limit));
        // A case-only replacement retains its established display name and insertion order.
        presets.put(old == null ? name : old, value);
    }

    public static <T> void rename(Map<String, T> presets, String old, String input) {
        rename(presets, old, input, MAX_LENGTH);
    }

    public static <T> void rename(Map<String, T> presets, String old, String input, int maxLength) {
        String name = valid(input, maxLength), collision = existing(presets, name);
        if (collision != null && !collision.equals(old))
            throw new IllegalArgumentException(Text.get("preset.name_exists"));
        if (!presets.containsKey(old)) throw new IllegalArgumentException(Text.get("preset.not_found"));
        var renamed = new LinkedHashMap<String, T>();
        presets.forEach((k, v) -> renamed.put(k.equals(old) ? name : k, v));
        presets.clear();
        presets.putAll(renamed);
    }
}
