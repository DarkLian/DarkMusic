package com.darkmusic.client.download;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.darkmusic.client.download.DownloadModels.*;

public final class DownloadJson {
    private DownloadJson() {
    }

    public static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    public static int number(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static JsonObject object(String output) {
        String line = output.lines().filter(s -> s.startsWith("{")).reduce((a, b) -> b).orElseThrow(() -> new IllegalArgumentException("The tool returned no video information. See the log for details."));
        return JsonParser.parseString(line).getAsJsonObject();
    }

    public static Preview preview(String original, int first, JsonObject json) {
        boolean collection = json.has("entries") && json.get("entries").isJsonArray();
        List<Entry> entries = new ArrayList<>();
        if (collection) {
            int index = first;
            int offset = 0;
            for (JsonElement value : json.getAsJsonArray("entries")) {
                int position = index;
                if (json.has("requested_entries") && json.get("requested_entries").isJsonArray() && offset < json.getAsJsonArray("requested_entries").size()) {
                    try {
                        position = json.getAsJsonArray("requested_entries").get(offset).getAsInt();
                    } catch (RuntimeException ignored) {
                    }
                }
                if (value.isJsonObject()) entries.add(entry(value.getAsJsonObject(), position, original));
                else entries.add(new Entry(position, "Unavailable Entry " + position, "", "", "", false));
                index++;
                offset++;
            }
        } else entries.add(entry(json, 0, original));
        int total = collection ? number(json, "playlist_count", -1) : 1;
        // n_entries counts this selected page, not the entire collection.
        if (original.contains("list=RD")) total = -1; // Dynamic YouTube Mixes have no stable end.
        boolean more = collection && (total >= 0 ? first + entries.size() - 1 < total : entries.size() >= PAGE_SIZE);
        return new Preview(original, text(json, "title"), source(original), collection, videoAlso(original), first, total, more, entries);
    }

    private static Entry entry(JsonObject json, int index, String original) {
        int position = number(json, "playlist_index", index);
        String id = text(json, "id"), extractor = text(json, "ie_key");
        if (extractor.isBlank()) extractor = text(json, "extractor_key");
        String link = text(json, "webpage_url");
        if (link.isBlank()) link = text(json, "url");
        if (!link.startsWith("http") && extractor.toLowerCase(Locale.ROOT).startsWith("youtube") && !id.isBlank())
            link = "https://www.youtube.com/watch?v=" + id;
        if (link.isBlank() && !json.has("entries")) link = original;
        boolean available = !text(json, "availability").matches("private|premium_only|subscriber_only|needs_auth");
        try {
            link = url(link);
        } catch (IllegalArgumentException e) {
            link = "";
            available = false;
        }
        String title = text(json, "title");
        if (title.isBlank()) title = id.isBlank() ? "Entry " + position : id;
        if (title.equals("[Deleted video]") || title.equals("[Private video]")) available = false;
        return new Entry(position, title, link, extractor, id.isBlank() ? link : id, available);
    }

    public static String friendly(String details) {
        String s = details.toLowerCase(Locale.ROOT);
        if (s.contains("429") || s.contains("too many requests"))
            return "The site is rate limiting downloads (429). Wait before retrying.";
        if (s.contains("403") || s.contains("forbidden"))
            return "The site refused access (403). Try a current yt-dlp version or another public entry.";
        if (s.contains("private") || s.contains("sign in") || s.contains("login") || s.contains("drm") || s.contains("restricted"))
            return "This entry is private, restricted, or requires authentication. Access controls are not bypassed.";
        if (s.contains("unsupported url")) return "yt-dlp does not support this URL or collection.";
        if (s.contains("no space") || s.contains("disk full") || s.contains("not enough space"))
            return "Not enough disk space. Free space in the music and temporary directories.";
        if (s.contains("javascript") || s.contains("challenge") || s.contains("js runtime"))
            return "YouTube needs a compatible Deno/Node runtime and current yt-dlp. Check Download Tools.";
        if (s.contains("timed out") || s.contains("timeout") || s.contains("network") || s.contains("resolve"))
            return "Network request timed out or failed. Check your connection and retry.";
        String last = details.lines().filter(v -> !v.isBlank()).reduce((a, b) -> b).orElse("Unknown tool error");
        return last.length() > 240 ? last.substring(0, 240) + "…" : last;
    }
}
