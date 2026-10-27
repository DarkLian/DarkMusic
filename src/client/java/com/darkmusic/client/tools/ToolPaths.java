package com.darkmusic.client.tools;

/**
 * User-configured executable paths; blank entries use the managed or system tools.
 */
public record ToolPaths(String ytDlp, String ffmpeg, String ffprobe, String javascript) {
}
