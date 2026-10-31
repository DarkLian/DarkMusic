package com.darkmusic.client.testing;

import com.darkmusic.client.tools.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Answers yt-dlp and FFmpeg version, help and encoder probes without running them; counts version probes.
 */
public class ToolsRunner extends ProcessRunner {
    public final AtomicInteger versions = new AtomicInteger();
    public volatile boolean fail;

    @Override
    public Result run(List<String> args, Path dir, Token token, Duration timeout, int max, Consumer<String> line) {
        token.check();
        if (args.contains("--version") || args.contains("-version")) {
            int n = versions.incrementAndGet();
            return new Result(fail ? 1 : 0, "tool version 2026.1." + n);
        }
        if (args.contains("--help"))
            return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --no-plugin-dirs");
        return new Result(0, "libmp3lame");
    }
}
