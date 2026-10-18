package com.darkmusic.client.library;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.nio.file.Path;

/**
 * Only read operations; linked artwork is never resolved.
 */
public final class MetadataReader {
    public Track read(String root, String relative, Path file, long size, long modified) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        String artist = "Unknown artist", album = "Unknown album", problem = "";
        double duration = 0;
        try {
            var container = com.darkmusic.client.audio.AudioFormats.detect(file);
            if (java.util.Set.of(com.darkmusic.client.audio.AudioFormats.Container.OPUS, com.darkmusic.client.audio.AudioFormats.Container.AAC, com.darkmusic.client.audio.AudioFormats.Container.WEBM).contains(container))
                throw new java.io.IOException("Use codec-aware metadata inspection");
            var audio = AudioFileIO.read(file.toFile());
            Tag tag = audio.getTag();
            if (tag != null) {
                title = value(tag, FieldKey.TITLE, title);
                artist = value(tag, FieldKey.ARTIST, artist);
                album = value(tag, FieldKey.ALBUM, album);
            }
            double length = audio.getAudioHeader().getPreciseTrackLength();
            duration = Double.isFinite(length) ? Math.max(0, length) : 0;
        } catch (Exception e) {
            problem = "Metadata unavailable: " + e.getClass().getSimpleName();
            try {
                var info = com.darkmusic.client.audio.ExternalAudio.probe(file);
                var format = info.getAsJsonObject("format");
                var stream = info.getAsJsonArray("streams").get(0).getAsJsonObject();
                var tags = format != null && format.has("tags") ? format.getAsJsonObject("tags") : new com.google.gson.JsonObject();
                if (stream.has("tags"))
                    for (var tag : stream.getAsJsonObject("tags").entrySet()) tags.add(tag.getKey(), tag.getValue());
                for (var tag : tags.entrySet())
                    switch (tag.getKey().toLowerCase(java.util.Locale.ROOT)) {
                        case "title" -> {
                            if (!tag.getValue().getAsString().isBlank()) title = tag.getValue().getAsString();
                        }
                        case "artist" -> {
                            if (!tag.getValue().getAsString().isBlank()) artist = tag.getValue().getAsString();
                        }
                        case "album" -> {
                            if (!tag.getValue().getAsString().isBlank()) album = tag.getValue().getAsString();
                        }
                    }
                var length = stream.has("duration") ? stream.get("duration") : format == null ? null : format.get("duration");
                if (length != null) {
                    double value = length.getAsDouble();
                    if (Double.isFinite(value)) duration = Math.max(0, value);
                }
                problem = "";
            } catch (Exception unavailable) {
                problem += "; FFmpeg/FFprobe may be required for this format";
            }
        }
        return new Track(root, relative, file, title, artist, album, duration, size, modified, problem);
    }

    private String value(Tag tag, FieldKey field, String fallback) {
        String value = tag.getFirst(field);
        return value == null || value.isBlank() ? fallback : value.replace("\u0000", "").strip();
    }

    public byte[] artwork(Path file) throws Exception {
        var container = com.darkmusic.client.audio.AudioFormats.detect(file);
        if (container == com.darkmusic.client.audio.AudioFormats.Container.OPUS || container == com.darkmusic.client.audio.AudioFormats.Container.VORBIS)
            return OggArtwork.read(file);
        var tag = AudioFileIO.read(file.toFile()).getTag();
        if (tag == null) return null;
        var image = tag.getFirstArtwork();
        if (image == null || image.isLinked()) return null;
        byte[] bytes = image.getBinaryData();
        return bytes != null && bytes.length <= 8 * 1024 * 1024 ? bytes : null;
    }
}
