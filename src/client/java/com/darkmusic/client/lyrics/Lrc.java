package com.darkmusic.client.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Timestamped local lyrics only. No alignment inference or remote lookup.
 */
public record Lrc(List<Line> lines) {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("(?im)\\[offset:([+-]?\\d+)]");

    public Lrc {
        lines = List.copyOf(lines);
    }

    public record Line(long millis, String text) {
    }

    public static Lrc parse(String text) {
        List<Line> result = new ArrayList<>();
        long offset = 0;
        var offsets = OFFSET.matcher(text);
        while (offsets.find()) try {
            offset = Long.parseLong(offsets.group(1));
        } catch (NumberFormatException ignored) {
        }
        offset = Math.clamp(offset, -3_600_000, 3_600_000);
        for (String raw : text.split("\\R")) {
            var matcher = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (matcher.find()) {
                int seconds = Integer.parseInt(matcher.group(2));
                if (seconds >= 60) continue;
                String fraction = matcher.group(3);
                int ms = fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3));
                times.add(Long.parseLong(matcher.group(1)) * 60_000 + seconds * 1000L + ms - offset);
                end = matcher.end();
            }
            String words = raw.substring(end).strip();
            for (long time : times) result.add(new Line(time, words));
        }
        result.sort(Comparator.comparingLong(Line::millis));
        return new Lrc(result);
    }

    public int current(double seconds) {
        long ms = (long) (Math.max(0, seconds) * 1000);
        int low = 0, high = lines.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).millis <= ms) low = mid + 1;
            else high = mid;
        }
        return low - 1;
    }
}
