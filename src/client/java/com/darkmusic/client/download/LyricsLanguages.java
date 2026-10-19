package com.darkmusic.client.download;

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

    public static List<String> resolve(String preferences, String automatic) {
        String auto = tag(automatic);
        if (auto.isEmpty()) auto = "en";
        var languages = new LinkedHashSet<String>();
        for (String part : normalize(preferences).split(", ")) languages.add(part.equals("auto") ? auto : part);
        return List.copyOf(languages);
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
        if (!metadata.has("subtitles") || !metadata.get("subtitles").isJsonObject()) return "";
        var subtitles = metadata.getAsJsonObject("subtitles");
        for (String preferred : preferences) {
            String best = "";
            int score = 0;
            for (var entry : subtitles.entrySet()) {
                String candidate = tag(entry.getKey());
                if (candidate.isEmpty() || !entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().isEmpty())
                    continue;
                boolean manual = true;
                for (JsonElement format : entry.getValue().getAsJsonArray())
                    if (!manual(format)) {
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

    private static boolean manual(JsonElement value) {
        if (!value.isJsonObject()) return false;
        var format = value.getAsJsonObject();
        if (DownloadJson.text(format, "kind").equalsIgnoreCase("asr")) return false;
        for (String flag : List.of("is_translated", "is_auto_generated"))
            if (DownloadJson.text(format, flag).equalsIgnoreCase("true")) return false;
        String url = DownloadJson.text(format, "url");
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
}
