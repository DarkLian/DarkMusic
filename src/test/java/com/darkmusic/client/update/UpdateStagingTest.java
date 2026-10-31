package com.darkmusic.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UpdateStagingTest {
    @TempDir
    Path temp;

    private byte[] jar(String id, String version, String dependencies) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"depends\":" + dependencies + "}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private GitHubRelease release(byte[] bytes) throws Exception {
        return new GitHubRelease("9.0.0", "darkmusic-9.0.0.jar", URI.create("https://github.com/DarkLian/DarkMusic/releases/download/v9.0.0/darkmusic-9.0.0.jar"),
            bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void approvedStageContainsVerifiedJarOldIdentityAndJdkOnlyInstaller() throws Exception {
        Path installed = Files.writeString(temp.resolve("installed.jar"), "installed fixture");
        String original = GitHubRelease.hash(installed);
        byte[] bytes = jar("darkmusic", "9.0.0", "{}");
        var selected = release(bytes);
        Path job = Files.createDirectory(temp.resolve("job"));
        Path manifest = UpdateService.stage(job, installed, selected, bytes);
        assertEquals(job.resolve("install.properties"), manifest);
        var properties = new Properties();
        try (var input = Files.newInputStream(manifest)) {
            properties.load(input);
        }
        assertEquals(installed.toString(), properties.getProperty("old"));
        assertEquals(original, properties.getProperty("oldHash"));
        assertEquals(selected.name(), properties.getProperty("name"));
        assertEquals(selected.sha256(), properties.getProperty("sha256"));
        assertArrayEquals(bytes, Files.readAllBytes(job.resolve("download.jar")));
        String helper = UpdateInstaller.class.getName().replace('.', '/') + ".class";
        try (var input = UpdateInstaller.class.getClassLoader().getResourceAsStream(helper)) {
            assertArrayEquals(input.readAllBytes(), Files.readAllBytes(job.resolve(helper)));
        }
        assertEquals(original, GitHubRelease.hash(installed));
    }

    @Test
    void verificationFailuresPrecedeManifestAndHelperAndPreserveInstalledJar() throws Exception {
        Path installed = Files.writeString(temp.resolve("installed.jar"), "installed fixture");
        String original = GitHubRelease.hash(installed);
        byte[] valid = jar("darkmusic", "9.0.0", "{}");
        byte[][] invalid = {jar("other", "9.0.0", "{}"), jar("darkmusic", "8.0.0", "{}"),
            jar("darkmusic", "9.0.0", "{\"missing-dependency\":\"*\"}"), new byte[]{1, 2, 3}};
        int index = 0;
        for (byte[] bytes : invalid) {
            Path job = Files.createDirectory(temp.resolve("bad-" + index++));
            assertThrows(Exception.class, () -> UpdateService.stage(job, installed, release(bytes), bytes));
            assertFalse(Files.exists(job.resolve("install.properties")));
            assertFalse(Files.exists(job.resolve("com")), "No installer before verification");
            assertEquals(original, GitHubRelease.hash(installed));
        }
        Path job = Files.createDirectory(temp.resolve("checksum"));
        var selected = release(valid);
        valid[0] ^= 1;
        assertThrows(Exception.class, () -> UpdateService.stage(job, installed, selected, valid));
        assertFalse(Files.exists(job.resolve("install.properties")));
        assertEquals(original, GitHubRelease.hash(installed));
    }
}
