package com.darkmusic.client.testing;

import com.darkmusic.client.tools.ToolPaths;

import java.nio.file.Path;

/**
 * This JVM's executable, a real program that stands in for yt-dlp, FFmpeg and FFprobe in tool tests.
 */
public final class JavaTools {
    private JavaTools() {
    }

    public static ToolPaths paths() {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new ToolPaths(java, java, java, "");
    }
}
