package com.darkmusic.client.pip;

import com.darkmusic.client.audio.SpectrumAnalyzer;
import com.darkmusic.client.config.PipSpectrumSettings;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.LockSupport;

/**
 * Session-owned PCM analysis. Nothing is played, recorded or fed into music DSP.
 */
public final class PipAudio implements AutoCloseable {
    private final SpectrumAnalyzer analyzer = new SpectrumAnalyzer();
    private volatile InputStream input;
    private volatile boolean closed;
    private volatile long packets, lastPacket;
    private volatile String status = "pip.audio_starting";

    public PipAudio() {
        analyzer.begin(44100, 2);
    }

    public void configure(PipSpectrumSettings settings) {
        analyzer.configure(true, settings.bands, settings.sensitivity, settings.smoothing);
        analyzer.refine(settings.quality);
    }

    public SpectrumAnalyzer analyzer() {
        return analyzer;
    }

    public long packets() {
        return packets;
    }

    public String status() {
        return status;
    }

    public boolean available() {
        return !closed && packets > 0 && System.nanoTime() - lastPacket < 1_000_000_000L;
    }

    public void failure(String key) {
        if (!closed) status = key;
    }

    /**
     * One background reader; fixed-size storage and strict validation before reading payload.
     */
    public void read(InputStream stream) throws IOException {
        input = stream;
        if (closed) {
            stream.close();
            return;
        }
        analyzer.begin(44100, 2);
        byte[] header = new byte[32], pcm = new byte[32768];
        long lastSequence = 0, clock = 0;
        try (stream) {
            while (!closed) {
                int first = stream.read();
                if (first < 0) return;
                header[0] = (byte) first;
                exact(stream, header, 1, 31);
                var h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (h.getInt() != 0x414D4444 || h.getInt() != 1 || h.getInt() != 44100 || h.getInt() != 2)
                    throw new IOException("Invalid PiP audio protocol");
                int frames = h.getInt(), flags = h.getInt();
                long sequence = h.getLong();
                if (frames < 1 || frames > 8192 || (flags & ~1) != 0 || sequence <= lastSequence)
                    throw new IOException("Invalid PiP audio packet");
                exact(stream, pcm, 0, frames * 4);
                if (closed) return;
                if ((flags & 1) != 0) {
                    analyzer.begin(44100, 2);
                    clock = 0;
                }
                analyzer.accept(pcm, 0, frames * 4, clock, 2);
                clock += frames;
                analyzer.advance(clock, true);
                lastSequence = sequence;
                lastPacket = System.nanoTime();
                packets++;
                status = "pip.audio_live";
            }
        }
    }

    private void exact(InputStream stream, byte[] bytes, int offset, int length) throws IOException {
        int count = 0;
        long progress = System.nanoTime();
        while (count < length && !closed) {
            int n = stream.read(bytes, offset + count, length - count);
            if (n < 0) throw new EOFException("Truncated PiP audio packet");
            if (n > 0) {
                count += n;
                progress = System.nanoTime();
            } else {
                if (System.nanoTime() - progress > 5_000_000_000L) throw new IOException("PiP audio made no progress");
                LockSupport.parkNanos(1_000_000);
            }
        }
        if (closed) throw new EOFException("Closed PiP audio");
    }

    @Override
    public void close() {
        closed = true;
        var stream = input;
        if (stream != null) try {
            stream.close();
        } catch (IOException ignored) {
        }
    }
}
