package com.darkmusic.client.gui;

import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.tools.ToolPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * Changes to downloads from the download screens: analysis, queueing, cancelling, retrying and clearing, the tools,
 * and duplicate choices. The screens decide when a control is offered; each change then applies as the download
 * service accepts it. Reads still go to the service.
 */
public interface DownloadOperations {
    /**
     * Keeps the URL being typed, so the screen shows it again when reopened.
     */
    void rememberInput(String value);

    void analyze(String url, int page);

    void enqueueVideo(String url, Path destination);

    void enqueueRange(DownloadModels.Preview expected, int first, int last, Path destination);

    void enqueueSelected(List<DownloadModels.Entry> entries, Path destination);

    void cancel(long id);

    void cancelAll();

    void retry(long id);

    void retryFailed();

    void clearFinished();

    void clearFailed();

    void installTools();

    void checkTools();

    void cancelTools();

    void configure(ToolPaths paths);

    void resolveDuplicateChoice(long id, boolean skip);

    void cancelDuplicateChoice(long id);
}
