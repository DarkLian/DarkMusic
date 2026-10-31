package com.darkmusic.client.testing;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.Arrays;

/**
 * Concrete consumer avoids proxy/reflection costs. Optional raw recording is used only by exactness checks.
 */
public final class Vertices implements VertexConsumer {
    private final boolean record;
    private int[] values = new int[4096];
    private int count;
    private long sum;

    public Vertices(boolean record) {
        this.record = record;
    }

    public void reset() {
        count = 0;
        sum = 0;
    }

    public long checksum() {
        return sum;
    }

    public int[] values() {
        return Arrays.copyOf(values, count);
    }

    private void add(int value) {
        sum = sum * 31 + value;
        if (!record) return;
        if (count == values.length) values = Arrays.copyOf(values, values.length * 2);
        values[count++] = value;
    }

    public VertexConsumer addVertex(float x, float y, float z) {
        add(Float.floatToRawIntBits(x));
        add(Float.floatToRawIntBits(y));
        add(Float.floatToRawIntBits(z));
        return this;
    }

    public VertexConsumer setColor(int color) {
        add(color);
        return this;
    }

    public VertexConsumer setColor(int r, int g, int b, int a) {
        return setColor(a << 24 | r << 16 | g << 8 | b);
    }

    public VertexConsumer setUv(float u, float v) {
        throw new AssertionError("Unexpected textured vertex");
    }

    public VertexConsumer setUv1(int u, int v) {
        throw new AssertionError("Unexpected overlay");
    }

    public VertexConsumer setUv2(int u, int v) {
        throw new AssertionError("Unexpected lighting");
    }

    public VertexConsumer setNormal(float x, float y, float z) {
        throw new AssertionError("Unexpected normal");
    }

    public VertexConsumer setLineWidth(float width) {
        throw new AssertionError("Unexpected line width");
    }
}
