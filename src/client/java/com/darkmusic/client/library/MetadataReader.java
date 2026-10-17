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
        }
        return new Track(root, relative, file, title, artist, album, duration, size, modified, problem);
    }

    private String value(Tag tag, FieldKey field, String fallback) {
        String value = tag.getFirst(field);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public byte[] artwork(Path file) throws Exception {
        var tag = AudioFileIO.read(file.toFile()).getTag();
        if (tag == null) return null;
        var image = tag.getFirstArtwork();
        if (image == null || image.isLinked()) return null;
        byte[] bytes = image.getBinaryData();
        return bytes != null && bytes.length <= 8 * 1024 * 1024 ? bytes : null;
    }
}
