package com.darkmusic.client.pip;

import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class PipBundleTest {
    @TempDir
    Path temp;

    private static byte[] archive(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Path unpack(byte[] bytes) throws Exception {
        return PipNativeTools.unpack(bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), temp);
    }

    @Test
    void extractsAndVerifiesNestedFilesWithoutOverwritingChanges() throws Exception {
        var bytes = archive(Map.of("DarkMusic.Pip.exe", new byte[]{1, 2, 3}, "zh-Hant/Messages.dll", new byte[]{4}));
        var exe = unpack(bytes);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(exe));
        assertEquals(exe, unpack(bytes));
        Files.write(exe, new byte[]{9, 8, 7});
        assertThrows(IOException.class, () -> unpack(bytes));
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(exe));
    }

    @Test
    void rejectsTraversalAndWrongChecksums() throws Exception {
        var bytes = archive(Map.of("../escape.txt", new byte[]{1}, "DarkMusic.Pip.exe", new byte[]{2}));
        assertThrows(IOException.class, () -> unpack(bytes));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
        assertThrows(IOException.class, () -> PipNativeTools.unpack(bytes, "0".repeat(64), temp));
    }

    @Test
    void refusesUnexpectedFilesInAnExistingInstallation() throws Exception {
        var bytes = archive(Map.of("DarkMusic.Pip.exe", new byte[]{1}));
        var exe = unpack(bytes);
        Files.writeString(exe.resolveSibling("unexpected.dll"), "preserve me");
        assertThrows(IOException.class, () -> unpack(bytes));
        assertEquals("preserve me", Files.readString(exe.resolveSibling("unexpected.dll")));
    }

    @Test
    void mainJarContainsOnlyPipManifestAndNoNativePayloads() throws Exception {
        try (var jar = new ZipFile(System.getProperty("darkmusic.jar"))) {
            assertNull(jar.getEntry("assets/darkmusic/pip/windows-x64/helper.zip"));
            assertNull(jar.getEntry("assets/darkmusic/pip/windows-x64/helper.sha256"));
            for (var entry : Collections.list(jar.entries())) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib"), name);
                if (name.startsWith("assets/darkmusic/pip/"))
                    assertTrue(entry.isDirectory() || name.equals("assets/darkmusic/pip/windows-x64/dependencies.json"), name);
            }
        }
    }

    @Test
    void actualOptionalPackageExtractsHelperAndRuntimeWithLicensesWhenPresent() throws Exception {
        try (var manifest = PipNativeTools.class.getResourceAsStream("/assets/darkmusic/pip/windows-x64/dependencies.json")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(manifest != null, "Java-only build omits optional native manifest");
            var bundle = PipRuntime.Bundle.parse(new String(manifest.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var dependencies = Path.of(System.getProperty("darkmusic.test.pipDependencies"));
            bundle.verify(dependencies);
            var exe = PipNativeTools.unpack(dependencies, bundle.sha256(), temp);
            assertTrue(Files.size(exe) > 0);
            assertTrue(Files.size(exe.resolveSibling("DOTNET-LICENSE.TXT")) > 100);
            assertTrue(Files.size(exe.resolveSibling("licenses/CSWINRT-LICENSE.txt")) > 100);
            if (PipNativeTools.supported()) {
                var result = new ProcessRunner().run(java.util.List.of(exe.toString(), "--pacer-test"), exe.getParent(), new ProcessRunner.Token(), java.time.Duration.ofSeconds(10), 1024, line -> {
                });
                assertEquals(0, result.exit(), result.output());
                assertEquals("pass", result.output().strip());
                var reliability = new ProcessRunner().run(java.util.List.of(exe.toString(), "--reliability-test"), exe.getParent(), new ProcessRunner.Token(), java.time.Duration.ofSeconds(15), 2048, line -> {
                });
                assertEquals(0, reliability.exit(), reliability.output());
                assertEquals("pass", reliability.output().strip());
            }
        }
    }
}
