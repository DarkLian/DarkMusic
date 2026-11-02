package com.darkmusic.client.pip;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent two-dimensional coverage oracle, compared with the separable helper.
 */
class PipResamplerTest {
    @Test
    void helperMatchesIntegerReferenceForRandomCropsAndRatios() throws Exception {
        Assumptions.assumeTrue(PipNativeTools.supported());
        Assumptions.assumeTrue(PipNativeTools.class.getResource("/assets/darkmusic/pip/windows-x64/dependencies.json") != null);
        var exe = Path.of("build/pip-helper/DarkMusic.Pip.exe").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(exe));
        checkVectors(exe, false);
        checkVectors(exe, true);
        checkVectors(exe, false, true);
        if (Boolean.getBoolean("darkmusic.test.desktop")) checkVectors(exe, true, true);
    }

    private static void checkVectors(Path exe, boolean scalar, boolean... gpu) throws Exception {
        var builder = new ProcessBuilder(exe.toString(), gpu.length > 0 ? "--gpu-resample-vectors" : "--resample-vectors").redirectError(ProcessBuilder.Redirect.INHERIT);
        if (gpu.length > 0)
            builder.environment().put("DARKMUSIC_PIP_GPU_WARP", scalar || !Boolean.getBoolean("darkmusic.test.desktop") ? "1" : "0");
        else if (scalar) builder.environment().put("DOTNET_EnableHWIntrinsic", "0");
        var process = builder.start();
        try (var input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            smallCases(input, output);
            for (int[] size : new int[][]{{1266, 713}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {7680, 1080}, {1080, 7680}}) {
                var target = new PipTarget(0, 0, size[0], size[1], 576, Math.min(324, size[1]));
                verify(input, output, new Random(78123), size[0], size[1], target);
            }
        } finally {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        assertEquals(0, process.exitValue());
    }

    private static void smallCases(BufferedWriter input, BufferedReader output) throws IOException {
        var random = new Random(78123);
        for (int i = 0; i < 128; i++) {
            int width = 1 + random.nextInt(40), height = 1 + random.nextInt(40);
            int x = random.nextInt(width), y = random.nextInt(height);
            int w = 1 + random.nextInt(width - x), h = 1 + random.nextInt(height - y);
            verify(input, output, random, width, height, new PipTarget(x, y, w, h, 1 + random.nextInt(w), 1 + random.nextInt(h)));
        }
    }

    private static void verify(BufferedWriter input, BufferedReader output, Random random, int width, int height, PipTarget target) throws IOException {
        byte[] source = new byte[width * height * 4];
        random.nextBytes(source);
        var json = new JsonObject();
        json.addProperty("width", width);
        json.addProperty("height", height);
        json.addProperty("data", Base64.getEncoder().encodeToString(source));
        json.addProperty("target", target.command());
        input.write(json.toString());
        input.newLine();
        input.flush();
        var line = output.readLine();
        assertNotNull(line, "Helper exited before " + width + "x" + height);
        assertArrayEquals(reference(source, width, target), Base64.getDecoder().decode(line), width + "x" + height);
    }

    private static byte[] reference(byte[] data, int stride, PipTarget t) {
        var result = new byte[t.outWidth() * t.outHeight() * 4];
        long denominator = (long) t.width() * t.height();
        for (int y = 0; y < t.outHeight(); y++)
            for (int x = 0; x < t.outWidth(); x++) {
                for (int c = 0; c < 3; c++) {
                    long sum = 0;
                    for (int sy = y * t.height() / t.outHeight(); sy < ((y + 1) * t.height() + t.outHeight() - 1) / t.outHeight(); sy++)
                        for (int sx = x * t.width() / t.outWidth(); sx < ((x + 1) * t.width() + t.outWidth() - 1) / t.outWidth(); sx++) {
                            long wx = Math.max(0, Math.min((x + 1) * t.width(), (sx + 1) * t.outWidth()) - Math.max(x * t.width(), sx * t.outWidth()));
                            long wy = Math.max(0, Math.min((y + 1) * t.height(), (sy + 1) * t.outHeight()) - Math.max(y * t.height(), sy * t.outHeight()));
                            sum += wx * wy * Byte.toUnsignedInt(data[((sy + t.y()) * stride + sx + t.x()) * 4 + 2 - c]);
                        }
                    result[(y * t.outWidth() + x) * 4 + c] = (byte) ((sum + denominator / 2) / denominator);
                }
                result[(y * t.outWidth() + x) * 4 + 3] = (byte) 255;
            }
        return result;
    }
}
