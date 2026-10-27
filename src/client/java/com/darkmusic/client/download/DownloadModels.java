package com.darkmusic.client.download;

import com.darkmusic.client.text.Text;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class DownloadModels {
    public static final int PAGE_SIZE = 100, MAX_BATCH = 1000;

    private DownloadModels() {
    }

    public static String url(String value) {
        if (value != null) value = value.strip();
        if (value == null || value.length() > 4096 || value.chars().anyMatch(c -> c <= 32 || c == 127))
            throw new IllegalArgumentException(Text.get("text.darkmusic.paste_a_single_http_or_https_video_collection_url"));
        try {
            URI uri = URI.create(value.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(Text.get("text.darkmusic.enter_a_valid_http_or_https_url_without_login_credentials"));
        }
    }

    public static String source(String value) {
        String host = URI.create(url(value)).getHost().toLowerCase(Locale.ROOT);
        if (host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")) return "YouTube";
        if (host.equals("b23.tv") || host.equals("bilibili.com") || host.endsWith(".bilibili.com")) return "Bilibili";
        return "yt-dlp: " + host;
    }

    public static boolean videoAlso(String value) {
        URI uri = URI.create(value);
        return (source(value).equals("YouTube") && (uri.getPath().equals("/watch") || uri.getHost().equals("youtu.be")))
            || (source(value).equals("Bilibili") && uri.getPath().contains("/video/"));
    }

    public static List<Integer> range(int first, int last) {
        if (first < 1 || last < first || (long) last - first + 1 > MAX_BATCH)
            throw new IllegalArgumentException(Text.get("download.range_invalid", MAX_BATCH));
        return java.util.stream.IntStream.rangeClosed(first, last).boxed().toList();
    }

    public enum Status {
        QUEUED, DOWNLOADING, CONVERTING, SAVED, SKIPPED, FAILED, CANCELLED;

        public boolean finished() {
            return ordinal() >= SAVED.ordinal();
        }
    }

    public record Tools(String ytDlp, String ffmpeg, String ffprobe, String javascript) {
    }

    public record Entry(int index, String title, String url, String extractor, String mediaId, boolean available) {
        public String key() {
            return extractor.toLowerCase(Locale.ROOT) + ":" + mediaId;
        }
    }

    public record Preview(String url, String title, String source, boolean collection, boolean videoAlso,
                          int first, int total, boolean hasMore, List<Entry> entries) {
        public Preview {
            entries = List.copyOf(entries);
        }
    }

    public record Item(long id, String title, Path directory, Status status, double progress, String message) {
    }

    public record Snapshot(boolean analyzing, String message, String toolStatus, Preview preview, List<Item> items) {
        public Snapshot {
            items = List.copyOf(items);
        }
    }
}
