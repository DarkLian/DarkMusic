package com.darkmusic.client;

import com.darkmusic.client.gui.LyricsOperations;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.lyrics.LyricsProvider;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.text.Text;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Lyrics changes from the menus. Writing a lyric file next to a song waits until no media operation owns the files:
 * a metadata edit renames a song together with its lyric files, so a file written meanwhile would stay behind under
 * the old name. Choices, searches and the cache live in the config folder and apply at once. Client thread.
 */
final class LyricsActions implements LyricsOperations {
    private final Inputs services;
    private final ClientState state;

    interface Inputs {
        LyricsService lyrics();
    }

    LyricsActions(Inputs services, ClientState state) {
        this.services = services;
        this.state = state;
    }

    private CompletableFuture<Void> nextToTheSong(Supplier<CompletableFuture<Void>> write) {
        if (!state.idle())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("library.darkmusic.busy")));
        return write.get();
    }

    @Override
    public CompletableFuture<Void> select(Track track, LyricsDocument selected) {
        return services.lyrics().lookup.select(track, selected);
    }

    @Override
    public CompletableFuture<Void> offset(Track track, int offset) {
        return services.lyrics().lookup.offset(track, offset);
    }

    @Override
    public CompletableFuture<Void> encoding(Track track, String value) {
        return services.lyrics().lookup.encoding(track, value);
    }

    @Override
    public CompletableFuture<Void> language(Track track, String value) {
        return services.lyrics().lookup.language(track, value);
    }

    @Override
    public void automatic(Track track, boolean localAvailable) {
        services.lyrics().lookup.automatic(track, localAvailable);
    }

    @Override
    public void search(Track track, LyricsProvider.Query query, boolean refresh) {
        services.lyrics().lookup.search(track, query, refresh);
    }

    @Override
    public void cancel(Track track) {
        services.lyrics().lookup.cancel(track);
    }

    @Override
    public CompletableFuture<Void> importFile(Track track, Path path) {
        return services.lyrics().lookup.importFile(track, path);
    }

    @Override
    public CompletableFuture<Void> save(Track track, LyricsDocument document, boolean replace) {
        return nextToTheSong(() -> services.lyrics().lookup.save(track, document, replace));
    }

    @Override
    public CompletableFuture<Void> keepCurrentRecovery(Track track) {
        return nextToTheSong(() -> services.lyrics().lookup.keepCurrentRecovery(track));
    }

    @Override
    public CompletableFuture<Void> restore(Track track) {
        return nextToTheSong(() -> services.lyrics().lookup.restore(track));
    }

    @Override
    public CompletableFuture<Void> clearCache() {
        return services.lyrics().lookup.clearCache();
    }

    @Override
    public void startLibraryLookup() {
        services.lyrics().startLibraryLookup();
    }

    @Override
    public void cancelLibraryLookup() {
        services.lyrics().cancelLibraryLookup();
    }
}
