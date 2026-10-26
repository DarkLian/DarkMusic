package com.darkmusic.client.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Bounded signature checks; containers and codecs are deliberately distinct.
 */
public final class AudioFormats {
    public enum Container {MP3, FLAC, WAVE, VORBIS, OPUS, MP4, AAC, WEBM, AIFF, UNKNOWN}

    private AudioFormats() {
    }

    public static Container detect(Path file) throws IOException {
        byte[] b;
        try (var in = Files.newInputStream(file)) {
            b = in.readNBytes(4096);
        }
        String s = new String(b, StandardCharsets.ISO_8859_1);
        if (s.startsWith("fLaC")) return Container.FLAC;
        if (s.startsWith("RIFF") && s.length() >= 12 && s.startsWith("WAVE", 8)) return Container.WAVE;
        if (s.startsWith("FORM") && s.length() >= 12 && Set.of("AIFF", "AIFC").contains(s.substring(8, 12)))
            return Container.AIFF;
        if (s.startsWith("OggS"))
            return s.contains("OpusHead") ? Container.OPUS : s.contains("vorbis") ? Container.VORBIS : Container.UNKNOWN;
        if (s.length() >= 12 && s.startsWith("ftyp", 4)) {
            String brand = s.substring(8, 12);
            return Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis", "qt  ").contains(brand) ? Container.UNKNOWN : Container.MP4;
        }
        if (b.length >= 4 && (b[0] & 255) == 0x1a && (b[1] & 255) == 0x45 && (b[2] & 255) == 0xdf && (b[3] & 255) == 0xa3)
            return s.contains("webm") && !s.contains("matroska") ? Container.WEBM : Container.UNKNOWN;
        if (s.startsWith("ID3")) return Container.MP3;
        if (b.length > 1 && (b[0] & 255) == 255) {
            if ((b[1] & 0xf6) == 0xf0) return Container.AAC;
            if ((b[1] & 0xe0) == 0xe0) return Container.MP3;
        }
        return Container.UNKNOWN;
    }

    public static boolean candidate(Path file) {
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.matches(".*\\.(mp3|flac|wav|ogg|oga|opus|m4a|mp4|aac|webm|aif|aiff)$");
    }
}
