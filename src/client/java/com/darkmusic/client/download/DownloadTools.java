package com.darkmusic.client.download;

import com.darkmusic.client.tools.DownloadDiagnostics;
import com.darkmusic.client.tools.ManagedTools;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import com.darkmusic.client.tools.Toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The download service's yt-dlp and FFmpeg: resolving them for the configured paths, once per change of paths, and
 * installing the managed ones. Resolution is serialized; the service is told the versions under its own lock.
 */
final class DownloadTools {
    private final Path data;
    private final ProcessRunner runner;
    private final Object resolution = new Object();
    private volatile Cache cache;

    DownloadTools(Path data, ProcessRunner runner) {
        this.data = data;
        this.runner = runner;
    }

    /**
     * The toolchain for selected, resolved again only when the paths changed. versions receives the resolved
     * versions while the resolution lock is still held.
     */
    Toolchain resolve(ToolPaths selected, ProcessRunner.Token token, Consumer<String> versions) throws Exception {
        synchronized (resolution) {
            Cache cached = cache;
            token.check();
            if (cached != null && selected.equals(cached.settings())) return cached.tools();
            Files.createDirectories(data);
            Toolchain result = DownloadDiagnostics.at(DownloadDiagnostics.Stage.TOOLS, DownloadDiagnostics.Tool.TOOLS,
                () -> Toolchain.resolve(selected, data, runner, token));
            token.check();
            cache = new Cache(selected, result);
            versions.accept(result.versions());
            return result;
        }
    }

    /**
     * The next resolution checks the tools again.
     */
    void forget() {
        cache = null;
    }

    /**
     * The versions resolved for current, or none when the tools were last resolved for other paths.
     */
    List<String> versions(ToolPaths current) {
        Cache resolved = cache;
        return resolved == null || !resolved.settings().equals(current) ? List.of() : List.of(resolved.tools().versions().split(" \\| ", -1));
    }

    void install(ProcessRunner.Token token, Consumer<String> status) throws Exception {
        new ManagedTools(data).install(runner, token, status::accept);
    }

    private record Cache(ToolPaths settings, Toolchain tools) {
    }
}
