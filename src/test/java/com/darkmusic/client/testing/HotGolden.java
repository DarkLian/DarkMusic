package com.darkmusic.client.testing;

import org.junit.jupiter.api.Assumptions;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Only hashes and counts are persisted; all audio buffers stay in memory.
 */
public final class HotGolden {
    private HotGolden() {
    }

    public static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static String hash(float[] values) throws Exception {
        var bytes = ByteBuffer.allocate(values.length * 4);
        for (float value : values) bytes.putInt(Float.floatToRawIntBits(value));
        return hash(bytes.array());
    }

    public static String hash(double[] values) throws Exception {
        var bytes = ByteBuffer.allocate(values.length * 8);
        for (double value : values) bytes.putLong(Double.doubleToRawLongBits(value));
        return hash(bytes.array());
    }

    public static String hash(int[] values) throws Exception {
        var bytes = ByteBuffer.allocate(values.length * 4);
        for (int value : values) bytes.putInt(value);
        return hash(bytes.array());
    }

    public static void check(String name, Map<String, String> results) throws Exception {
        var actual = new Properties();
        actual.putAll(results);
        if ("true".equals(System.getenv("DARKMUSIC_HOT_RECORD"))) {
            Path path = Path.of(".local-tools/verification/remaining/" + name + ".properties");
            try (var output = Files.newOutputStream(path)) {
                actual.store(output, "Before hot-loop refactors: hashes only");
            }
            return;
        }
        var expected = new Properties();
        try (var input = HotGolden.class.getResourceAsStream("/" + name + ".properties")) {
            assertNotNull(input, "Record the pre-split golden first");
            expected.load(input);
        }
        if (actual.containsKey("clip")) Assumptions.assumeTrue(actual.get("clip").equals(expected.get("clip")),
            "Private clip differs; repeated exactness was still checked, fixed golden comparison unavailable");
        assertEquals(expected, actual);
    }
}
