package com.darkmusic.client.pip;

/**
 * Validated source pixel rectangle and desired physical output size.
 */
public record PipTarget(int x, int y, int width, int height, int outWidth, int outHeight) {
    public PipTarget {
        if (x < 0 || y < 0 || width < 1 || height < 1 || outWidth < 1 || outHeight < 1 || outWidth > width || outHeight > height)
            throw new IllegalArgumentException("Invalid PiP target");
    }

    public static PipTarget of(PipFrame f, double left, double top, double right, double bottom, int physicalWidth, int physicalHeight) {
        int x = (int) Math.floor(left * f.sourceWidth()), y = (int) Math.floor(top * f.sourceHeight());
        int w = Math.max(1, (int) Math.ceil(right * f.sourceWidth()) - x), h = Math.max(1, (int) Math.ceil(bottom * f.sourceHeight()) - y);
        return new PipTarget(x, y, w, h, Math.clamp(physicalWidth, 1, w), Math.clamp(physicalHeight, 1, h));
    }

    public PipTarget union(PipTarget b) {
        int l = Math.min(x, b.x), t = Math.min(y, b.y);
        int w = Math.max(x + width, b.x + b.width) - l, h = Math.max(y + height, b.y + b.height) - t;
        double sx = Math.max(outWidth / (double) width, b.outWidth / (double) b.width);
        double sy = Math.max(outHeight / (double) height, b.outHeight / (double) b.height);
        return new PipTarget(l, t, w, h, Math.min(w, (int) Math.ceil(w * sx)), Math.min(h, (int) Math.ceil(h * sy)));
    }

    public String command() {
        return "TARGET " + x + " " + y + " " + width + " " + height + " " + outWidth + " " + outHeight;
    }
}
