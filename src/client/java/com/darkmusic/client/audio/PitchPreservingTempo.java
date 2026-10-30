package com.darkmusic.client.audio;

import java.io.IOException;

/**
 * Streaming WSOLA, with one shared alignment search for both channels.
 */
final class PitchPreservingTempo {
    private final PcmRateConverter source;
    private final int channels, hop, search;
    private final float[][] ring;
    private final float[] decoded, overlap, output;
    private final double[] blend;
    private final double ratio, start;
    private long loaded;
    private double ideal;
    private double blockSpeed = 1;
    private int cursor, available;
    private boolean first = true, ended;

    PitchPreservingTempo(PcmRateConverter source, int channels, double deviceRate, double ratio) {
        this.source = source;
        this.channels = channels;
        this.ratio = ratio;
        start = source.rawPosition();
        hop = Math.clamp((int) Math.round(deviceRate * .02), 160, 3840);
        search = Math.max(32, hop / 4);
        decoded = new float[2048];
        // Hold everything from the oldest search candidate to the look-ahead plus one more decoded chunk.
        // The fixed 2048-frame terms do not scale with the rate, so hop * 12 alone is too small below ~22 kHz.
        int span = 2 * search + 2 * hop + 2048 + decoded.length / channels + 2;
        int capacity = Integer.highestOneBit(Math.max(hop * 12, span) - 1) << 1;
        ring = new float[capacity][channels];
        overlap = new float[hop * channels];
        output = new float[hop * channels];
        blend = new double[hop];
        for (int i = 0; i < hop; i++) blend[i] = .5 - .5 * Math.cos(Math.PI * i / hop);
    }

    double position() {
        return start + (ideal - Math.max(0, available - cursor) / (double) channels * blockSpeed) * ratio;
    }

    private boolean ensure(long end) throws IOException {
        while (!ended && loaded <= end) {
            int n = source.read(decoded, decoded.length, ratio);
            if (n < 0) {
                ended = true;
                break;
            }
            if (n == 0) return false; // Yield to the worker so stop/seek and stall detection can run.
            for (int i = 0; i < n / channels; i++) {
                var frame = ring[(int) (loaded++ & (ring.length - 1))];
                if (channels >= 0) System.arraycopy(decoded, i * channels + 0, frame, 0, channels);
            }
        }
        return true;
    }

    private double value(long frame, int c) {
        return frame < 0 || frame >= loaded ? 0 : ring[(int) (frame & (ring.length - 1))][c];
    }

    int read(float[] destination, int length, double speed) throws IOException {
        int written = 0;
        while (written + channels <= length) {
            if (cursor == available) {
                if (!ensure((long) ideal + search + 2L * hop + 2048)) return written;
                if (ended && ideal >= loaded) break;
                long selected = Math.max(0, Math.round(ideal));
                if (!first) {
                    double best = -Double.MAX_VALUE;
                    long low = Math.max(0, selected - search), high = Math.min(loaded - hop, selected + search);
                    // Normalized correlation; stereo channels always use the same displacement.
                    for (long candidate = low; candidate <= high; candidate += 4) {
                        double dot = 0, a = 1e-12, b = 1e-12;
                        for (int i = 0; i < hop; i += 8)
                            for (int c = 0; c < channels; c++) {
                                double x = overlap[i * channels + c], y = value(candidate + i, c);
                                dot += x * y;
                                a += x * x;
                                b += y * y;
                            }
                        double score = dot / Math.sqrt(a * b) - Math.abs(candidate - ideal) * 1e-7;
                        if (score > best) {
                            best = score;
                            selected = candidate;
                        }
                    }
                }
                int frames = ended ? Math.min(hop, Math.max(0, (int) Math.ceil((loaded - ideal) / speed))) : hop;
                for (int i = 0; i < frames; i++)
                    for (int c = 0; c < channels; c++) {
                        double x = value(selected + i, c);
                        output[i * channels + c] = (float) (first ? x : overlap[i * channels + c] * (1 - blend[i]) + x * blend[i]);
                    }
                for (int i = 0; i < hop; i++)
                    for (int c = 0; c < channels; c++) overlap[i * channels + c] = (float) value(selected + hop + i, c);
                cursor = 0;
                available = frames * channels;
                first = false;
                blockSpeed = speed;
                ideal += frames * speed;
                if (available == 0) break;
            }
            int n = Math.min(available - cursor, (length - written) / channels * channels);
            System.arraycopy(output, cursor, destination, written, n);
            cursor += n;
            written += n;
        }
        return written == 0 && ended ? -1 : written;
    }
}
