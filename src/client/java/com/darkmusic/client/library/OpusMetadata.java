package com.darkmusic.client.library;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.Map;

/**
 * Bounded Opus header/tail inspection. Chained or incomplete streams fall back to the codec probe.
 */
public final class OpusMetadata {
    private OpusMetadata() {
    }

    public record Info(Map<String, String> tags, double duration) {
    }

    public static Info read(Path file) throws IOException {
        byte[] head;
        try (var in = Files.newInputStream(file)) {
            head = in.readNBytes(512);
        }
        if (head.length < 47 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S')
            throw new IOException("Invalid Opus header");
        int packet = 27 + (head[26] & 255);
        if (packet + 19 > head.length || !new String(head, packet, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("OpusHead"))
            throw new IOException("Invalid Opus identification packet");
        var first = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int serial = first.getInt(14), preSkip = Short.toUnsignedInt(first.getShort(packet + 10));
        double duration = -1;
        try (var channel = Files.newByteChannel(file)) {
            int length = (int) Math.min(channel.size(), 131072);
            var tail = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(channel.size() - length);
            while (tail.hasRemaining()) if (channel.read(tail) < 0) break;
            byte[] bytes = tail.array();
            for (int i = 0; i + 27 <= tail.position(); i++) {
                if (bytes[i] != 'O' || bytes[i + 1] != 'g' || bytes[i + 2] != 'g' || bytes[i + 3] != 'S' || bytes[i + 4] != 0)
                    continue;
                int segments = bytes[i + 26] & 255, pageLength = 27 + segments;
                if (i + pageLength > tail.position()) continue;
                for (int j = 0; j < segments; j++) pageLength += bytes[i + 27 + j] & 255;
                if (i + pageLength != tail.position() || tail.getInt(i + 14) != serial || (bytes[i + 5] & 4) == 0)
                    continue;
                long granule = tail.getLong(i + 6);
                if (granule >= preSkip) duration = (granule - preSkip) / 48000.0;
            }
        }
        return new Info(OggArtwork.tags(file), duration);
    }
}
