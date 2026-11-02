package com.darkmusic.client.pip;

/**
 * Source-space intersection mapped into texture and destination coordinates without whole-texel truncation.
 */
public record PipMapping(float u0, float v0, float u1, float v1, double left, double top, double right, double bottom) {
    public static PipMapping of(PipFrame f, double left, double top, double right, double bottom) {
        double x = left * f.sourceWidth(), y = top * f.sourceHeight();
        double w = (right - left) * f.sourceWidth(), h = (bottom - top) * f.sourceHeight();
        double x0 = Math.max(x, f.cropX()), y0 = Math.max(y, f.cropY());
        double x1 = Math.min(x + w, f.cropX() + f.cropWidth()), y1 = Math.min(y + h, f.cropY() + f.cropHeight());
        if (w <= 0 || h <= 0 || x1 <= x0 || y1 <= y0) return null;
        return new PipMapping((float) ((x0 - f.cropX()) / f.cropWidth()), (float) ((y0 - f.cropY()) / f.cropHeight()),
            (float) ((x1 - f.cropX()) / f.cropWidth()), (float) ((y1 - f.cropY()) / f.cropHeight()),
            (x0 - x) / w, (y0 - y) / h, (x1 - x) / w, (y1 - y) / h);
    }
}
