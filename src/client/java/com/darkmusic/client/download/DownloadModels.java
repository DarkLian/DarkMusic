package com.darkmusic.client.download;

import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.SourceUrls;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

public final class DownloadModels {
    public static final int PAGE_SIZE = 100, MAX_BATCH = 1000;

    private DownloadModels() {
    }

    public static String url(String value) {
        return SourceUrls.url(value);
    }

    public static String source(String value) {
        return SourceUrls.source(value);
    }

    public static boolean videoAlso(String value) {
        URI uri = URI.create(value);
        return (source(value).equals("YouTube") && (uri.getPath().equals("/watch") || uri.getHost().equals("youtu.be")))
            || (source(value).equals("Bilibili") && uri.getPath().contains("/video/"));
    }

    public static List<Integer> range(int first, int last) {
        if (first < 1 || last < first || (long) last - first + 1 > MAX_BATCH)
            throw new IllegalArgumentException(Text.get("download.range_invalid", MAX_BATCH));
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    public enum Status {
        QUEUED, DOWNLOADING, CONVERTING, SAVED, SKIPPED, FAILED, CANCELLED;

        public boolean finished() {
            return ordinal() >= SAVED.ordinal();
        }
    }

    public record Entry(int index, String title, String url, String extractor, String mediaId,
                        boolean available) implements LibrarySourceIndex.Source {
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
