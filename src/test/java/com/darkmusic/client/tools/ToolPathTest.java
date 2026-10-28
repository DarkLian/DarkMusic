package com.darkmusic.client.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPathTest {
    @TempDir
    Path temp;

    @Test
    void malformedAndQuotedEntriesDoNotHideValidLaterExecutables() throws Exception {
        Path tools = Files.createDirectories(temp.resolve("tools"));
        Path valid = Files.createDirectories(temp.resolve("valid directory"));
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path actual = Path.of(System.getProperty("java.home"), "bin", binary);
        Path copy = valid.resolve(binary);
        Files.copy(actual, copy);
        assertTrue(copy.toFile().setExecutable(true));
        String paths = "bad\u0000entry" + File.pathSeparator + "\"" + valid + "\"";
        assertEquals(copy, Toolchain.executable("java", tools, null, paths));
    }
}
