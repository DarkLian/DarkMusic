package com.darkmusic.client.library;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Embedded Xiph picture comments; bounded header inspection, never linked artwork.
 */
public final class OggArtwork {
    private static final int LIMIT = 12 * 1024 * 1024;

    private OggArtwork() {
    }

    public static byte[] read(Path file) throws IOException {
        byte[] packet = commentPacket(file);
        return packet == null ? null : comments(packet);
    }

    public static Map<String, String> tags(Path file) throws IOException {
        byte[] packet = commentPacket(file);
        if (packet == null) throw new IOException("Ogg comments unavailable within inspection bounds");
        var tags = new HashMap<String, String>();
        try {
            var buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            skip(buffer, buffer.getInt());
            int count = buffer.getInt();
            if (count < 0 || count > buffer.remaining() / 4) return Map.of();
            for (int i = 0; i < count; i++) {
                int length = buffer.getInt();
                if (length < 0 || length > buffer.remaining()) return Map.of();
                if (length <= 1_000_000) {
                    String text = new String(packet, buffer.position(), length, StandardCharsets.UTF_8);
                    int equals = text.indexOf('=');
                    if (equals > 0) {
                        String key = text.substring(0, equals).toLowerCase(Locale.ROOT);
                        if (Set.of("title", "artist", "album", "lyrics", "unsyncedlyrics", "language").contains(key))
                            tags.putIfAbsent(key, text.substring(equals + 1));
                    }
                }
                skip(buffer, length);
            }
        } catch (IllegalArgumentException | BufferUnderflowException malformed) {
            throw new IOException("Invalid Ogg comments", malformed);
        }
        return Map.copyOf(tags);
    }

    private static byte[] commentPacket(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var packet = new ByteArrayOutputStream();
            int consumed = 0, packets = 0;
            Integer serial = null;
            while (consumed < LIMIT && packets < 3) {
                byte[] header = input.readNBytes(27);
                if (header.length != 27 || header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S' || header[4] != 0)
                    return null;
                int current = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(14);
                if (serial == null) serial = current;
                byte[] segments = input.readNBytes(header[26] & 255);
                if (segments.length != (header[26] & 255)) return null;
                consumed += 27 + segments.length;
                for (byte segment : segments) {
                    int length = segment & 255;
                    if ((consumed += length) > LIMIT) return null;
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length != length) return null;
                    if (serial != current) continue;
                    packet.write(bytes);
                    if (length < 255) {
                        byte[] complete = packet.toByteArray();
                        packet.reset();
                        packets++;
                        if (starts(complete, "OpusTags", 0))
                            return Arrays.copyOfRange(complete, 8, complete.length);
                        if (complete.length > 0 && complete[0] == 3 && starts(complete, "vorbis", 1))
                            return Arrays.copyOfRange(complete, 7, complete.length);
                    }
                }
            }
        }
        return null;
    }

    private static boolean starts(byte[] bytes, String text, int offset) {
        if (bytes.length < offset + text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (bytes[offset + i] != text.charAt(i)) return false;
        return true;
    }

    private static byte[] comments(byte[] packet) {
        try {
            var buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            skip(buffer, buffer.getInt());
            int count = buffer.getInt();
            if (count < 0 || count > buffer.remaining() / 4) return null;
            for (int i = 0; i < count; i++) {
                int length = buffer.getInt();
                if (length < 0 || length > buffer.remaining()) return null;
                byte[] bytes = new byte[length];
                buffer.get(bytes);
                String comment = new String(bytes, StandardCharsets.UTF_8);
                String key = "METADATA_BLOCK_PICTURE=";
                if (comment.regionMatches(true, 0, key, 0, key.length())) {
                    var picture = ByteBuffer.wrap(Base64.getDecoder().decode(comment.substring(key.length())));
                    picture.getInt();
                    int mimeLength = picture.getInt();
                    if (mimeLength == 3 && picture.remaining() >= 3 && picture.get(picture.position()) == '-' && picture.get(picture.position() + 1) == '-' && picture.get(picture.position() + 2) == '>')
                        continue;
                    skip(picture, mimeLength);
                    skip(picture, picture.getInt());
                    skip(picture, 16);
                    int size = picture.getInt();
                    if (size <= 0 || size > 8 * 1024 * 1024 || size > picture.remaining()) return null;
                    byte[] image = new byte[size];
                    picture.get(image);
                    return image;
                }
            }
        } catch (IllegalArgumentException | BufferUnderflowException ignored) {
            // Malformed optional artwork cannot prevent audio playback.
        }
        return null;
    }

    private static void skip(ByteBuffer buffer, int length) {
        if (length < 0 || length > buffer.remaining()) throw new IllegalArgumentException("Invalid picture length");
        buffer.position(buffer.position() + length);
    }

    public static String picture(byte[] jpeg, int width, int height) throws IOException {
        return picture(jpeg, width, height, "image/jpeg");
    }

    public static String picture(byte[] jpeg, int width, int height, String mimeType) throws IOException {
        if (jpeg.length > 8 * 1024 * 1024) throw new IOException("Cover image exceeds size limit");
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            byte[] mime = mimeType.getBytes(StandardCharsets.US_ASCII);
            output.writeInt(3);
            output.writeInt(mime.length);
            output.write(mime);
            output.writeInt(0);
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(24);
            output.writeInt(0);
            output.writeInt(jpeg.length);
            output.write(jpeg);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
