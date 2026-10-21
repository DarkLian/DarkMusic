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
        String line = output.lines().filter(s -> s.startsWith("{")).reduce((a, b) -> b).orElseThrow(() -> new IllegalArgumentException(com.darkmusic.client.text.Text.get("download.no_info")));
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
                else
                    entries.add(new Entry(position, com.darkmusic.client.text.Text.get("format.darkmusic.unavailable_entry", position), "", "", "", false));
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
        if (title.isBlank())
            title = id.isBlank() ? com.darkmusic.client.text.Text.get("format.darkmusic.entry", position) : id;
        if (title.equals("[Deleted video]") || title.equals("[Private video]")) available = false;
        return new Entry(position, title, link, extractor, id.isBlank() ? link : id, available);
    }

    public static String friendly(String details) {
        if (com.darkmusic.client.text.Text.isMessage(details)) return details;
        String s = details.toLowerCase(Locale.ROOT);
        if (s.matches("(?s).*\\bhttp(?: error| status| status code)?[ :]+429\\b.*") || s.contains("too many requests"))
            return com.darkmusic.client.text.Text.get("text.darkmusic.the_site_is_rate_limiting_downloads_429_wait_before_retrying");
        if (s.matches("(?s).*\\bhttp(?: error| status| status code)?[ :]+403\\b.*"))
            return com.darkmusic.client.text.Text.get("text.darkmusic.the_site_refused_access_403_try_a_current_yt_dlp_version_or_another_public_entry");
        if (s.contains("video is private") || s.contains("private video") || s.contains("sign in to") || s.contains("login required") || s.matches("(?s).*\\bdrm\\b.*") || s.contains("age-restricted") || s.contains("requires authentication"))
            return com.darkmusic.client.text.Text.get("text.darkmusic.this_entry_is_private_restricted_or_requires_authentication_access_controls_are_no");
        if (s.contains("unsupported url"))
            return com.darkmusic.client.text.Text.get("text.darkmusic.yt_dlp_does_not_support_this_url_or_collection");
        if (s.contains("no space") || s.contains("disk full") || s.contains("not enough space"))
            return com.darkmusic.client.text.Text.get("text.darkmusic.not_enough_disk_space_free_space_in_the_music_and_temporary_directories");
        if (s.contains("javascript runtime") || s.contains("challenge solving failed") || s.contains("challenge solver") || s.contains("js runtime"))
            return com.darkmusic.client.text.Text.get("text.darkmusic.youtube_needs_a_compatible_deno_node_runtime_and_current_yt_dlp_check_download_too");
        if (s.contains("timed out") || s.contains("timeout") || s.contains("network is unreachable") || s.contains("connection refused") || s.contains("unable to resolve") || s.contains("name resolution"))
            return com.darkmusic.client.text.Text.get("text.darkmusic.network_request_timed_out_or_failed_check_your_connection_and_retry");
        org.slf4j.LoggerFactory.getLogger("darkmusic-download").debug("Tool failure details: {}", details);
        return com.darkmusic.client.text.Text.get("download.error.unknown");
    }
}
