package com.darkmusic.client.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Lowercase hexadecimal SHA-256. The standalone UpdateInstaller and DesktopTrashApp keep their own JDK-only copies.
 */
public final class Hashes {
    private Hashes() {
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(Path file, LinkOption... options) throws IOException {
        return sha256(file, null, options);
    }

    /**
     * Streams the file; a non-null token is checked between buffers so long hashes can be cancelled.
     */
    public static String sha256(Path file, ProcessRunner.Token token, LinkOption... options) throws IOException {
        var digest = sha256();
        try (var input = Files.newInputStream(file, (OpenOption[]) options)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (token != null) token.check();
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
