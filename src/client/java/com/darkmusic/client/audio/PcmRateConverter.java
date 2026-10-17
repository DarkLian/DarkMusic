package com.darkmusic.client.audio;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;

/**
 * Bounded, band-limited streaming resampling. Output retains the device's original sample rate.
 */
public final class PcmRateConverter {
    private static final int TAPS = 32, PHASES = 512;
    private final AudioInputStream input;
    private final int channels, frameSize;
    private final byte[] buffer = new byte[4096];
    private final double[][] ring;
    private final double[][] weights = new double[PHASES][TAPS];
    private int cursor, limit;
    private long loaded;
    private boolean ended;
    private double position, prepared = -1;

    public PcmRateConverter(AudioInputStream input) {
        this.input = input;
        channels = input.getFormat().getChannels();
        frameSize = channels * 2;
        if (channels < 1 || channels > 2 || input.getFormat().getSampleSizeInBits() != 16 || input.getFormat().isBigEndian())
            throw new IllegalArgumentException("Expected little-endian 16-bit mono/stereo PCM");
        ring = new double[64][channels];
    }

    public static double clamp(double value) {
        return Double.isFinite(value) ? Math.round(Math.clamp(value, .5, 3) * 10) / 10.0 : 1;
    }

    public double positionFrames() {
        return position;
    }

    private void prepare(double speed) {
        if (prepared == speed) return;
        prepared = speed;
        double cutoff = speed > 1 ? .95 / speed : 1;
        for (int phase = 0; phase < PHASES; phase++) {
            double sum = 0, fraction = phase / (double) PHASES;
            for (int i = 0; i < TAPS; i++) {
                double distance = i - 15 - fraction, x = Math.PI * cutoff * distance;
                double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(x) / x;
                double window = .5 + .5 * Math.cos(Math.PI * distance / 16);
                weights[phase][i] = sinc * window * cutoff;
                sum += weights[phase][i];
            }
            for (int i = 0; i < TAPS; i++) weights[phase][i] /= sum;
        }
    }

    private int load() throws IOException {
        if (cursor >= limit) {
            limit = input.read(buffer, 0, buffer.length / frameSize * frameSize);
            cursor = 0;
            if (limit < 0) {
                ended = true;
                return -1;
            }
            if (limit == 0) return 0;
        }
        for (int c = 0; c < channels; c++) {
            int at = cursor + c * 2;
            ring[(int) (loaded & 63)][c] = (short) ((buffer[at] & 255) | (buffer[at + 1] << 8));
        }
        cursor += frameSize;
        loaded++;
        return 1;
    }

    public int read(byte[] out, int length, double value) throws IOException {
        double speed = Double.isFinite(value) && value > 0 ? value : 1;
        if (speed > 64 || speed < 1.0 / 64) throw new IllegalArgumentException("Unsupported sample-rate ratio");
        prepare(speed);
        int written = 0;
        while (written + frameSize <= length) {
            long base = (long) position;
            while (!ended && loaded <= base + 16) if (load() == 0) return written;
            if (ended && position >= loaded) break;
            int phase = Math.min(PHASES - 1, (int) ((position - base) * PHASES));
            for (int c = 0; c < channels; c++) {
                double sample = 0;
                for (int i = 0; i < TAPS; i++) {
                    long frame = base + i - 15;
                    if (frame >= 0 && frame < loaded) sample += ring[(int) (frame & 63)][c] * weights[phase][i];
                }
                int pcm = Math.clamp(Math.round(sample), -32768, 32767);
                out[written++] = (byte) pcm;
                out[written++] = (byte) (pcm >> 8);
            }
            position += speed;
        }
        return written == 0 && ended ? -1 : written;
    }
}
