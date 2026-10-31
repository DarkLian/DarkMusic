package com.darkmusic.client.download;

import com.darkmusic.client.tools.DownloadDiagnostics;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.Toolchain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.darkmusic.client.download.DownloadModels.Preview;
import static com.darkmusic.client.download.DownloadModels.url;

/**
 * The Downloads screen's URL and analysis: what was typed, what was analyzed, and the resulting preview. A revision
 * counter drops an analysis that finishes after the preview was cleared or replaced. Guarded by the download service's
 * monitor, like the rest of its state; inspect runs on the analysis thread.
 */
final class DownloadPreview {
    private static final Logger LOG = LoggerFactory.getLogger("darkmusic-download");
    private final ProcessRunner runner;
    private final Path data;
    Preview preview;
    String lastAnalyzedUrl = "", inputUrl = "";
    long revision;
    ProcessRunner.Token token;

    DownloadPreview(ProcessRunner runner, Path data) {
        this.runner = runner;
        this.data = data;
    }

    /**
     * One page of a URL's entries, as yt-dlp lists them without downloading.
     */
    Preview inspect(String value, int first, int last, Toolchain tools, ProcessRunner.Token token, DownloadDiagnostics.Stage stage) throws Exception {
        var args = tools.base();
        args.addAll(List.of("--flat-playlist", "--dump-single-json", "--skip-download", "--ignore-errors", "--yes-playlist", "--playlist-items", first + ":" + last, "--", url(value)));
        var r = DownloadDiagnostics.at(stage, DownloadDiagnostics.Tool.YT_DLP, () -> runner.run(args, data, token, Duration.ofMinutes(3), 16_000_000, s -> {
            if (!s.startsWith("{") && !s.isBlank()) LOG.debug("Analysis: {}", s);
        }));
        if (r.exit() != 0)
            throw new DownloadDiagnostics.Failure(stage, DownloadDiagnostics.Tool.YT_DLP, r.exit(), r.output()).versions(tools.versions());
        return DownloadDiagnostics.at(stage, DownloadDiagnostics.Tool.YT_DLP, () -> DownloadJson.preview(value, first, DownloadJson.object(r.output())));
    }
}
