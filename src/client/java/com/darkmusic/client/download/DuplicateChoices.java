package com.darkmusic.client.download;

import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.config.DownloadFormat;
import com.darkmusic.client.tools.ToolPaths;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.darkmusic.client.download.DownloadModels.Entry;

/**
 * The one download request waiting for the player to decide about songs the library already has. Guarded by the
 * download service's monitor, like the rest of its state.
 */
final class DuplicateChoices {
    private DownloadService.DuplicateChoice pending;
    private long ids;

    DownloadService.DuplicateChoice current() {
        return pending;
    }

    /**
     * The newest request replaces an unanswered review; keeping the old one would drop this batch.
     */
    void offer(List<Entry> entries, Path destination, ToolPaths tools, DownloadFormat format,
               Map<String, List<LibrarySourceIndex.Existing>> matches) {
        pending = new DownloadService.DuplicateChoice(++ids, List.copyOf(entries), destination, tools, format, Map.copyOf(matches));
    }

    /**
     * The review with this id, now answered, or null when it is no longer the one waiting.
     */
    DownloadService.DuplicateChoice take(long id) {
        var choice = pending;
        if (choice == null || choice.id() != id) return null;
        pending = null;
        return choice;
    }

    void clear() {
        pending = null;
    }
}
