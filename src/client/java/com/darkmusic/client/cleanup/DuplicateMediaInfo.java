package com.darkmusic.client.cleanup;

import com.darkmusic.client.audio.ExternalAudio;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.google.gson.JsonObject;

public record DuplicateMediaInfo(String codec, String sampleRate, String channels, String bitrate, String source) {
    public static final DuplicateMediaInfo EMPTY = new DuplicateMediaInfo("", "", "", "", "");

    public static DuplicateMediaInfo read(Track track) {
        return read(track, SourceLinks.read(track.file()));
    }

    /**
     * Bulk callers already loaded sidecars once for the directory.
     */
    static DuplicateMediaInfo read(Track track, String source) {
        try {
            var info = ExternalAudio.probe(track.file());
            var audio = info.getAsJsonArray("streams").get(0).getAsJsonObject();
            var format = info.getAsJsonObject("format");
            for (var object : new JsonObject[]{format, audio})
                if (source.isEmpty() && object != null && object.has("tags")) {
                    for (var tag : object.getAsJsonObject("tags").entrySet())
                        if (java.util.Set.of("purl", "source_url", "webpage_url", "url", "comment").contains(tag.getKey().toLowerCase(java.util.Locale.ROOT))) {
                            source = SourceLinks.safe(tag.getValue().getAsString());
                            if (!source.isEmpty()) break;
                        }
                }
            String bitrate = value(audio, "bit_rate");
            if (bitrate.isEmpty()) bitrate = value(format, "bit_rate");
            return new DuplicateMediaInfo(value(audio, "codec_name"), value(audio, "sample_rate"), value(audio, "channels"), bitrate, source);
        } catch (Exception ignored) {
            return new DuplicateMediaInfo("", "", "", "", source);
        }
    }

    private static String value(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
