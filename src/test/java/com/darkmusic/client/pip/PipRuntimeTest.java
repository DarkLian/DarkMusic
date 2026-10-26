package com.darkmusic.client.pip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class PipRuntimeTest {
    @TempDir
    Path temp;

    @Test
    void publishedDependenciesInstallWithTheProductionTransport() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("darkmusic.test.pipNetwork") && Boolean.getBoolean("darkmusic.test.desktop") && PipNativeTools.supported(), "Explicit live PiP runtime installation check");
        String releaseJar = System.getProperty("darkmusic.test.pipReleaseJar", "");
        assertFalse(releaseJar.isBlank(), "Supply -PpipRuntimeReleaseJar pointing to the downloaded release JAR");
        // CI can use a different .NET patch. Verify the runtime pinned by the actual release, not a local rebuild.
        try (var jar = new ZipFile(Path.of(releaseJar).toFile()); var runtime = new PipRuntime(temp, (name, max) -> {
            var entry = jar.getEntry(PipRuntime.BASE.substring(1) + name);
            if (entry == null) throw new IOException("Missing published PiP resource");
            try (var input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(max + 1);
                if (bytes.length > max) throw new IOException("Oversized published PiP resource");
                return bytes;
            }
        }, com.darkmusic.client.download.ManagedTools::download, null, PipRuntime::graphics)) {
            runtime.check().get(30, TimeUnit.SECONDS);
            assertTrue(runtime.compatible(), runtime.status());
            assertFalse(runtime.ready());
            runtime.install().get(180, TimeUnit.SECONDS);
            assertTrue(runtime.ready(), runtime.status());
            assertTrue(Files.isRegularFile(runtime.executable()));
        }
    }

    @BeforeEach
    void canonicalFixtureDirectory() throws IOException {
        // macOS exposes its temporary directory through /var -> /private/var.
        // Successful-install fixtures must not accidentally exercise linked-path rejection.
        temp = temp.toRealPath();
    }

    @Test
    void linkedInstallationDirectoryRemainsRejected() throws Exception {
        Path target = Files.createDirectory(temp.resolve("target"));
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, target);
        } catch (UnsupportedOperationException | FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic-link creation unavailable: " + unavailable.getClass().getSimpleName());
        }
        byte[] code = zip(Map.of("DarkMusic.Pip.exe", new byte[]{1})), deps = zip(Map.of("runtime.dll", new byte[]{2}));
        try (var runtime = new PipRuntime(alias, resources(code, deps), (u, p, m, t, c) -> fail("No download through a linked directory"), t -> 26300, (e, t) -> true)) {
            runtime.check().get(5, TimeUnit.SECONDS);
            assertEquals("pip.bundle_damaged", runtime.status());
            assertFalse(runtime.ready());
            assertFalse(Files.exists(target.resolve("pip-native")));
        }
    }

    @Test
    void realWindowsRequirementsAndInstalledRuntimeWithoutNetwork() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("darkmusic.test.desktop") && PipNativeTools.supported(), "Optional Windows desktop runtime check");
        var payload = Path.of(System.getProperty("darkmusic.test.pipDependencies"));
        try (var runtime = new PipRuntime(temp, PipRuntime::resource, (uri, out, max, token, progress) -> {
            token.check();
            Files.copy(payload, out, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(Files.size(out));
        }, null, PipRuntime::graphics)) {
            runtime.check().get(30, TimeUnit.SECONDS);
            assertTrue(runtime.compatible(), runtime.status());
            assertFalse(runtime.ready());
            runtime.install().get(60, TimeUnit.SECONDS);
            assertTrue(runtime.ready(), runtime.status());
            assertTrue(Files.isRegularFile(runtime.executable()));
        }
    }

    static byte[] zip(Map<String, byte[]> files) throws IOException {
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

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String manifest(byte[] deps) throws Exception {
        return "{\"schema\":1,\"version\":\"3.0.0\",\"name\":\"darkmusic-pip-dependencies-3.0.0-windows-x64.zip\",\"size\":" + deps.length + ",\"sha256\":\"" + hash(deps) + "\"}";
    }

    static PipRuntime.Resources resources(byte[] code, byte[] deps) throws Exception {
        var data = Map.of("dependencies.json", manifest(deps).getBytes(StandardCharsets.UTF_8), "helper.zip", code, "helper.sha256", hash(code).getBytes(StandardCharsets.US_ASCII));
        return (name, max) -> data.get(name);
    }

    @Test
    void developmentInstallUsesMatchingLocalArchiveAndKeepsChecksumVerification() throws Exception {
        byte[] code = zip(Map.of("DarkMusic.Pip.exe", new byte[]{1})), deps = zip(Map.of("runtime.dll", new byte[]{2}));
        Path local = Files.write(temp.resolve("local.zip"), deps);
        var transfer = PipRuntime.developmentDownload(true, local.toString(), (u, p, m, t, c) -> fail("No release download in development"));
        try (var runtime = new PipRuntime(temp.resolve("valid"), resources(code, deps), transfer, t -> 26300, (e, t) -> true)) {
            runtime.check().get();
            assertFalse(runtime.ready());
            runtime.install().get();
            assertTrue(runtime.ready(), runtime.status());
            assertEquals(deps.length, runtime.downloaded());
        }
        byte[] changed = deps.clone();
        changed[0] ^= 1;
        Files.write(local, changed);
        try (var runtime = new PipRuntime(temp.resolve("changed"), resources(code, deps), transfer, t -> 26300, (e, t) -> fail("Reject altered archive before execution"))) {
            runtime.check().get();
            runtime.install().get();
            assertFalse(runtime.ready());
            assertEquals("pip.bundle_damaged", runtime.status());
        }
        var request = new com.darkmusic.client.download.ProcessRunner.Token();
        request.cancel();
        assertThrows(Exception.class, () -> transfer.fetch(java.net.URI.create("https://example.invalid"), temp.resolve("cancelled"), deps.length, request, n -> {
        }));
    }

    @Test
    void productionIgnoresDevelopmentArchiveProperty() {
        PipRuntime.Download network = (u, p, m, t, c) -> {
        };
        assertSame(network, PipRuntime.developmentDownload(false, "private/local.zip", network));
        assertSame(network, PipRuntime.developmentDownload(true, "", network));
    }

    @Test
    void constructionAndCheckingNeverDownloadOrGrantAccess() throws Exception {
        byte[] code = zip(Map.of("DarkMusic.Pip.exe", new byte[]{1})), deps = zip(Map.of("runtime.dll", new byte[]{2}));
        var downloads = new AtomicInteger();
        var checks = new AtomicInteger();
        try (var runtime = new PipRuntime(temp, resources(code, deps), (u, p, m, t, progress) -> downloads.incrementAndGet(), t -> {
            checks.incrementAndGet();
            return 26300;
        }, (e, t) -> true)) {
            assertFalse(Files.exists(temp.resolve("pip-native")));
            assertEquals(0, checks.get());
            assertFalse(runtime.ready());
            runtime.check().get(5, TimeUnit.SECONDS);
            assertEquals(1, checks.get());
            assertEquals(0, downloads.get());
            assertTrue(runtime.compatible());
            assertFalse(runtime.ready());
            assertEquals("pip.setup.required", runtime.status());
        }
    }

    @Test
    void explicitInstallVerifiesAndReusesDependenciesAcrossRestart() throws Exception {
        byte[] code = zip(Map.of("DarkMusic.Pip.exe", new byte[]{1})), deps = zip(Map.of("runtime.dll", new byte[]{2}));
        var downloads = new AtomicInteger();
        var resources = resources(code, deps);
        PipRuntime.Download fetch = (uri, out, max, token, progress) -> {
            assertEquals("https://github.com/DarkLian/DarkMusic/releases/download/v3.0.0/darkmusic-pip-dependencies-3.0.0-windows-x64.zip", uri.toString());
            downloads.incrementAndGet();
            Files.write(out, deps);
            progress.accept((long) deps.length);
        };
        try (var runtime = new PipRuntime(temp, resources, fetch, t -> 26300, (e, t) -> Files.exists(e.resolveSibling("runtime.dll")))) {
            runtime.install().get();
            assertEquals(0, downloads.get()); // OS checks required first.
            runtime.check().get();
            runtime.install().get();
            assertTrue(runtime.ready(), runtime.status());
            assertTrue(runtime.audioSupported());
            assertEquals(1, downloads.get());
        }
        try (var runtime = new PipRuntime(temp, resources, fetch, t -> 19041, (e, t) -> true)) {
            assertFalse(runtime.ready());
            runtime.check().get();
            assertTrue(runtime.ready());
            assertFalse(runtime.audioSupported());
            assertEquals(1, downloads.get());
        }
    }

    @Test
    void cancellationAndBadDownloadsNeverActivateRuntime() throws Exception {
        byte[] code = zip(Map.of("DarkMusic.Pip.exe", new byte[]{1})), deps = zip(Map.of("runtime.dll", new byte[]{2}));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var probes = new AtomicInteger();
        try (var runtime = new PipRuntime(temp, resources(code, deps), (u, p, m, t, progress) -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            t.check();
            Files.write(p, deps);
        }, t -> 26300, (e, t) -> {
            probes.incrementAndGet();
            return true;
        })) {
            runtime.check().get();
            var install = runtime.install();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            runtime.cancel();
            release.countDown();
            install.get();
            assertFalse(runtime.ready());
            assertEquals("pip.setup.cancelled", runtime.status());
            assertEquals(0, probes.get());
        } finally {
            release.countDown();
        }
        try (var runtime = new PipRuntime(temp, resources(code, deps), (u, p, m, t, progress) -> Files.write(p, new byte[]{9}), t -> 26300, (e, t) -> true)) {
            runtime.check().get();
            runtime.install().get();
            assertFalse(runtime.ready());
            assertEquals("pip.bundle_damaged", runtime.status());
        }
    }

    @Test
    void unsupportedSystemsNeverReadOrInstallPayloads() throws Exception {
        try (var runtime = new PipRuntime(temp, (n, max) -> {
            fail("No payload access");
            return null;
        }, (u, p, m, t, c) -> fail("No download"), t -> 0, (e, t) -> false)) {
            runtime.check().get();
            runtime.install().get();
            assertFalse(runtime.compatible());
            assertFalse(Files.exists(temp.resolve("pip-native")));
        }
    }

    @Test
    void splitArchivesRejectCollisionsAndTraversal() throws Exception {
        byte[] code = zip(Map.of("DarkMusic.Pip.exe", new byte[]{1}));
        for (var entry : List.of("DarkMusic.Pip.exe", "../escape.dll")) {
            byte[] deps = zip(Map.of(entry, new byte[]{2}));
            Path archive = Files.createTempFile(temp, "deps-", ".zip");
            Files.write(archive, deps);
            assertThrows(IOException.class, () -> PipNativeTools.unpack(code, hash(code), archive, hash(deps), temp.resolve(UUID.randomUUID().toString())));
        }
        assertFalse(Files.exists(temp.resolve("escape.dll")));
    }
}
