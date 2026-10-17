package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

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
        String paths = "bad\u0000entry" + java.io.File.pathSeparator + "\"" + valid + "\"";
        assertEquals(copy, Toolchain.executable("java", tools, null, paths));
    }
}
