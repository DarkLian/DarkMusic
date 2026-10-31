package com.darkmusic.client;

import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.gui.DownloadOperations;
import com.darkmusic.client.tools.ToolPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * Download changes from the menus. The download service admits each change itself (a full queue, a running analysis,
 * missing tools); the menus add no condition of their own. Client thread.
 */
final class DownloadActions implements DownloadOperations {
    private final Inputs services;

    interface Inputs {
        DownloadService downloads();
    }

    DownloadActions(Inputs services) {
        this.services = services;
    }

    @Override
    public void rememberInput(String value) {
        services.downloads().rememberInput(value);
    }

    @Override
    public void analyze(String url, int page) {
        services.downloads().analyze(url, page);
    }

    @Override
    public void enqueueVideo(String url, Path destination) {
        services.downloads().enqueueVideo(url, destination);
    }

    @Override
    public void enqueueRange(DownloadModels.Preview expected, int first, int last, Path destination) {
        services.downloads().enqueueRange(expected, first, last, destination);
    }

    @Override
    public void enqueueSelected(List<DownloadModels.Entry> entries, Path destination) {
        services.downloads().enqueueSelected(entries, destination);
    }

    @Override
    public void cancel(long id) {
        services.downloads().cancel(id);
    }

    @Override
    public void cancelAll() {
        services.downloads().cancelAll();
    }

    @Override
    public void retry(long id) {
        services.downloads().retry(id);
    }

    @Override
    public void retryFailed() {
        services.downloads().retryFailed();
    }

    @Override
    public void clearFinished() {
        services.downloads().clearFinished();
    }

    @Override
    public void clearFailed() {
        services.downloads().clearFailed();
    }

    @Override
    public void installTools() {
        services.downloads().installTools();
    }

    @Override
    public void checkTools() {
        services.downloads().checkTools();
    }

    @Override
    public void cancelTools() {
        services.downloads().cancelTools();
    }

    @Override
    public void configure(ToolPaths paths) {
        services.downloads().configure(paths);
    }

    @Override
    public void resolveDuplicateChoice(long id, boolean skip) {
        services.downloads().resolveDuplicateChoice(id, skip);
    }

    @Override
    public void cancelDuplicateChoice(long id) {
        services.downloads().cancelDuplicateChoice(id);
    }
}
