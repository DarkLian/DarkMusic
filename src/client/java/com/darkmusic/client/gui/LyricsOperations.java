package com.darkmusic.client.gui;

import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.lyrics.LyricsProvider;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Changes to lyrics from the lyrics screens: the chosen lyrics and their offset, encoding and language, searches,
 * sidecar files next to the song, the online cache and the library lookup. Writing next to the song waits while a
 * media operation owns the files; the rest applies at once. Reads still go to the lyrics service.
 */
public interface LyricsOperations {
    CompletableFuture<Void> select(Track track, LyricsDocument selected);

    CompletableFuture<Void> offset(Track track, int offset);

    CompletableFuture<Void> encoding(Track track, String value);

    CompletableFuture<Void> language(Track track, String value);

    void automatic(Track track, boolean localAvailable);

    void search(Track track, LyricsProvider.Query query, boolean refresh);

    void cancel(Track track);

    /**
     * Reads a lyric file as this song's chosen lyrics; the file itself is not copied next to the song.
     */
    CompletableFuture<Void> importFile(Track track, Path path);

    /**
     * Writes the lyrics next to the song. Fails while a media operation owns the files.
     */
    CompletableFuture<Void> save(Track track, LyricsDocument document, boolean replace);

    /**
     * Keeps the lyric file next to the song and drops its recovery copy. Fails while a media operation owns the files.
     */
    CompletableFuture<Void> keepCurrentRecovery(Track track);

    /**
     * Puts the backed-up lyric file next to the song back. Fails while a media operation owns the files.
     */
    CompletableFuture<Void> restore(Track track);

    CompletableFuture<Void> clearCache();

    void startLibraryLookup();

    void cancelLibraryLookup();
}
