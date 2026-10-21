package com.darkmusic.client.update;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * Only stable, checksum-bearing assets from this project's releases are accepted.
 */
public record GitHubRelease(String version, String name, URI url, long size, String sha256) {
    public static final String API = "https://api.github.com/repos/DarkLian/DarkMusic/releases/latest";
    public static final long MAX_JAR = 64L * 1024 * 1024;

    public static Optional<GitHubRelease> parse(String json, String current) throws Exception {
        var release = JsonParser.parseString(json).getAsJsonObject();
        if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) return Optional.empty();
        String tag = release.get("tag_name").getAsString();
        if (!tag.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+")) return Optional.empty();
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (Version.parse(version).compareTo(Version.parse(current)) <= 0) return Optional.empty();
        String name = "darkmusic-" + version + ".jar";
        for (var item : release.getAsJsonArray("assets")) {
            var asset = item.getAsJsonObject();
            if (!name.equals(asset.get("name").getAsString())) continue;
            long size = asset.get("size").getAsLong();
            String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
            URI url = URI.create(asset.get("browser_download_url").getAsString());
            if (size <= 0 || size > MAX_JAR || !digest.matches("sha256:[0-9a-fA-F]{64}") ||
                !url.toString().equals("https://github.com/DarkLian/DarkMusic/releases/download/" + tag + "/" + name))
                throw new IOException("Invalid release asset or missing checksum");
            return Optional.of(new GitHubRelease(version, name, url, size, digest.substring(7).toLowerCase(java.util.Locale.ROOT)));
        }
        return Optional.empty();
    }

    public static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public void verify(Path jar) throws Exception {
        if (Files.size(jar) != size || !hash(jar).equals(sha256)) throw new IOException("Release checksum mismatch");
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null || entry.getSize() > 65536) throw new IOException("Missing mod metadata");
            try (var input = zip.getInputStream(entry)) {
                var metadata = JsonParser.parseString(new String(input.readNBytes(65537), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (!"darkmusic".equals(metadata.get("id").getAsString()) || !version.equals(metadata.get("version").getAsString()))
                    throw new IOException("Unexpected mod identity");
                var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
                for (var dependency : metadata.getAsJsonObject("depends").entrySet()) {
                    var installed = loader.getModContainer(dependency.getKey()).orElseThrow(() -> new IOException("Missing dependency: " + dependency.getKey()));
                    var ranges = dependency.getValue().isJsonArray() ? dependency.getValue().getAsJsonArray() : new com.google.gson.JsonArray();
                    if (!dependency.getValue().isJsonArray()) ranges.add(dependency.getValue());
                    boolean compatible = false;
                    for (var range : ranges)
                        compatible |= net.fabricmc.loader.api.metadata.version.VersionPredicate.parse(range.getAsString()).test(installed.getMetadata().getVersion());
                    if (!compatible) throw new IOException("Incompatible dependency: " + dependency.getKey());
                }
            }
        }
    }
}
