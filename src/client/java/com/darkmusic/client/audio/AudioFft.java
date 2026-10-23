package com.darkmusic.client.audio;

/**
 * In-place radix-two transform. Plans are immutable and shared by worker-owned buffers.
 */
public final class AudioFft {
    private final int n;
    private final int[] reverse;
    private final double[] cos, sin;

    public AudioFft(int size) {
        if (Integer.bitCount(size) != 1) throw new IllegalArgumentException("FFT size");
        n = size;
        reverse = new int[n];
        cos = new double[n / 2];
        sin = new double[n / 2];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) reverse[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < n / 2; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / n);
            sin[i] = Math.sin(2 * Math.PI * i / n);
        }
    }

    public void transform(double[] real, double[] imaginary, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = reverse[i];
            if (j > i) {
                double v = real[i];
                real[i] = real[j];
                real[j] = v;
                v = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = v;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            int half = length / 2, stride = n / length;
            for (int start = 0; start < n; start += length)
                for (int j = 0; j < half; j++) {
                    int p = j * stride, a = start + j, b = a + half;
                    double s = inverse ? sin[p] : -sin[p];
                    double r = real[b] * cos[p] - imaginary[b] * s;
                    double im = real[b] * s + imaginary[b] * cos[p];
                    real[b] = real[a] - r;
                    imaginary[b] = imaginary[a] - im;
                    real[a] += r;
                    imaginary[a] += im;
                }
        }
        if (inverse) for (int i = 0; i < n; i++) {
            real[i] /= n;
            imaginary[i] /= n;
        }
    }
}
