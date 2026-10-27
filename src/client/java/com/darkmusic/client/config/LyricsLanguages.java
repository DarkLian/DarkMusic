package com.darkmusic.client.config;

import com.darkmusic.client.text.Text;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * User language preferences select captions; neither video metadata nor captions verify a song's language.
 */
public final class LyricsLanguages {
    private LyricsLanguages() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return "auto";
        if (value.length() > 100) throw invalid();
        var languages = new LinkedHashSet<String>();
        for (String part : value.split(",", -1)) {
            String language = part.strip().equalsIgnoreCase("auto") ? "auto" : tag(part.strip());
            if (language.isEmpty()) throw invalid();
            languages.add(language);
        }
        return String.join(", ", languages);
    }

    /**
     * Retained for callers migrating from the former interface-language policy.
     */
    public static List<String> resolve(String preferences, String automatic) {
        return resolve(preferences);
    }

    public static List<String> resolve(String preferences) {
        // Auto is a policy, never the interface or OS language. The second argument is retained for compatibility.
        var languages = new LinkedHashSet<String>();
        for (String part : normalize(preferences).split(", ")) languages.add(part);
        return List.copyOf(languages);
    }

    /**
     * An explicit song tag may break Auto's caption tie; it never overrides the user's ordered choices.
     */
    public static String preferSongLanguage(String preferences, String language) {
        if (!normalize(preferences).equals("auto")) return preferences;
        String tag = normalizeLanguage(language);
        if (tag.equals("und") || tag.equals("mul")) return preferences;
        try {
            return normalize(tag + ", auto");
        } catch (IllegalArgumentException unknown) {
            return preferences;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(Text.get("lyrics.darkmusic.language_invalid"));
    }

    private static String tag(String value) {
        if (value == null || !value.matches("[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8}){0,3}") || value.equalsIgnoreCase("all"))
            return "";
        try {
            return new Locale.Builder().setLanguageTag(value.replace('_', '-')).build().toLanguageTag();
        } catch (java.util.IllformedLocaleException e) {
            return "";
        }
    }

    public static String select(JsonObject metadata, List<String> preferences) {
        return select(metadata, preferences, false);
    }

    public static String select(JsonObject metadata, List<String> preferences, boolean generated) {
        String field = generated ? "automatic_captions" : "subtitles";
        if (!metadata.has(field) || !metadata.get(field).isJsonObject()) return "";
        var subtitles = metadata.getAsJsonObject(field);
        for (String preferred : preferences) {
            if (preferred.equals("auto")) {
                // Only a single eligible original track is unambiguous. Multiple originals require explicit preferences.
                String sole = "";
                int eligible = 0;
                for (var entry : subtitles.entrySet()) {
                    if (!tag(entry.getKey()).isEmpty() && entry.getValue().isJsonArray()
                        && !entry.getValue().getAsJsonArray().isEmpty()
                        && java.util.stream.StreamSupport.stream(entry.getValue().getAsJsonArray().spliterator(), false).allMatch(f -> manual(f, generated))) {
                        sole = entry.getKey();
                        eligible++;
                    }
                }
                if (eligible == 1) return sole;
                continue;
            }
            String best = "";
            int score = 0;
            for (var entry : subtitles.entrySet()) {
                String candidate = tag(entry.getKey());
                if (candidate.isEmpty() || !entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().isEmpty())
                    continue;
                boolean manual = true;
                for (JsonElement format : entry.getValue().getAsJsonArray())
                    if (!manual(format, generated)) {
                        manual = false;
                        break;
                    }
                int match = manual ? match(preferred, candidate) : 0;
                if (match > score) {
                    best = entry.getKey();
                    score = match;
                }
            }
            if (score > 0) return best;
        }
        return "";
    }

    private static boolean manual(JsonElement value, boolean generated) {
        if (!value.isJsonObject()) return false;
        var format = value.getAsJsonObject();
        if (!generated && text(format, "kind").equalsIgnoreCase("asr")) return false;
        for (String flag : generated ? List.of("is_translated") : List.of("is_translated", "is_auto_generated"))
            if (text(format, flag).equalsIgnoreCase("true")) return false;
        String url = text(format, "url");
        if (url.isBlank()) return true;
        try {
            String query = URI.create(url).getRawQuery();
            if (query != null) for (String part : query.split("&"))
                if (URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8).equalsIgnoreCase("tlang"))
                    return false;
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int match(String preferred, String candidate) {
        if (preferred.equalsIgnoreCase(candidate)) return 100;
        var wanted = Locale.forLanguageTag(preferred);
        var offered = Locale.forLanguageTag(candidate);
        if (!wanted.getLanguage().equals(offered.getLanguage())) return 0;
        if (wanted.getScript().isEmpty() && wanted.getCountry().isEmpty() && wanted.getVariant().isEmpty()) return 10;
        String script = script(wanted);
        if (!script.isEmpty() && !script.equals(script(offered))) return 0;
        if (!wanted.getCountry().isEmpty() && !offered.getCountry().isEmpty() && !wanted.getCountry().equals(offered.getCountry()))
            return 0;
        if (!wanted.getVariant().isEmpty() && !wanted.getVariant().equals(offered.getVariant())) return 0;
        return 50;
    }

    private static String script(Locale language) {
        if (!language.getScript().isEmpty()) return language.getScript();
        if (language.getLanguage().equals("zh")) return switch (language.getCountry()) {
            case "TW", "HK", "MO" -> "Hant";
            case "CN", "SG" -> "Hans";
            default -> "";
        };
        return "";
    }

    public static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) return "und";
        String tag = language.strip().replace('_', '-');
        tag = switch (tag.toLowerCase(Locale.ROOT)) {
            case "eng" -> "en";
            case "jpn" -> "ja";
            case "kor" -> "ko";
            case "zho", "chi" -> "zh";
            case "mul" -> "mul";
            default -> tag;
        };
        try {
            return new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
        } catch (java.util.IllformedLocaleException invalid) {
            return "und";
        }
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
