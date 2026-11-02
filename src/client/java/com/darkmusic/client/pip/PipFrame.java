package com.darkmusic.client.pip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Versioned, packed RGBA8 frames from the explicitly selected local capture source.
 */
public record PipFrame(int width, int height, int bytes, long sequence, long capturedMicros,
                       long sentMicros, long receivedNanos, int sourceWidth, int sourceHeight,
                       int cropX, int cropY, int cropWidth, int cropHeight) {
    public static final int HEADER_BYTES = 96, MAGIC = 0x49504d44, VERSION = 2;
    public static final int MAX_WIDTH = 7680, MAX_HEIGHT = 7680, MAX_PIXELS = 3840 * 2160;

    public PipFrame(int width, int height, int bytes, long sequence, long capturedMicros, long sentMicros, long receivedNanos) {
        this(width, height, bytes, sequence, capturedMicros, sentMicros, receivedNanos, width, height, 0, 0, width, height);
    }

    public static boolean validDimensions(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_WIDTH && height <= MAX_HEIGHT && (long) width * height <= MAX_PIXELS;
    }

    public static PipFrame decode(ByteBuffer header, long receivedNanos) throws IOException {
        if (header.remaining() != HEADER_BYTES) throw new IOException("Invalid PiP header size");
        header.order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt() != MAGIC || header.getInt() != VERSION) throw new IOException("Unsupported PiP protocol");
        int width = header.getInt(), height = header.getInt(), stride = header.getInt(), bytes = header.getInt();
        long sequence = header.getLong(), captured = header.getLong(), sent = header.getLong();
        int format = header.getInt(), flags = header.getInt();
        long reserved = header.getLong();
        int sourceWidth = header.getInt(), sourceHeight = header.getInt();
        int cropX = header.getInt(), cropY = header.getInt(), cropWidth = header.getInt(), cropHeight = header.getInt();
        long reserved2 = header.getLong();
        if (!validDimensions(sourceWidth, sourceHeight) || cropX < 0 || cropY < 0 || cropWidth < 1 || cropHeight < 1
            || (long) cropX + cropWidth > sourceWidth || (long) cropY + cropHeight > sourceHeight
            || width > cropWidth || height > cropHeight || reserved2 != 0 || !validDimensions(width, height)
            || stride != width * 4 || bytes != (long) stride * height || sequence < 1
            || captured < 0 || sent < captured || format != 1 || flags != 0 || reserved != 0)
            throw new IOException("Invalid PiP frame dimensions or metadata");
        return new PipFrame(width, height, bytes, sequence, captured, sent, receivedNanos,
            sourceWidth, sourceHeight, cropX, cropY, cropWidth, cropHeight);
    }
}
