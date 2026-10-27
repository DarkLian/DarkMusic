package com.darkmusic.client;

import com.darkmusic.client.download.DownloadPipeline;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.gui.DesktopWindowGuard;
import com.darkmusic.client.library.AudioDeletion;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

/**
 * Changes to audio files: import, metadata edits and deletion. Each holds the app's media lock while it runs.
 * Work captures the playback, library and playlist objects it started with; when Reset to Defaults replaces them,
 * the identity check on completion discards the stale result.
 */
public final class MediaActions {
    private final DarkMusicClient app;

    MediaActions(DarkMusicClient app) {
        this.app = app;
    }

    private static IllegalStateException busy() {
        return new IllegalStateException(Text.get("library.darkmusic.busy"));
    }

    public void importAudio(java.util.List<java.nio.file.Path> paths, String folder, String playlistId) {
        if (app.mediaBusy() || app.resetting() || !app.playlistsReady) {
            app.warn(Text.get("library.darkmusic.busy"));
            return;
        }
        var library = app.library;
        var root = library.root();
        String rootId = library.rootId();
        if (folder.startsWith("@")) {
            int slash = folder.indexOf('/');
            rootId = folder.substring(1, slash < 0 ? folder.length() : slash);
            root = library.directory(rootId);
            folder = slash < 0 ? "" : folder.substring(slash + 1);
            if (root == null) {
                app.warn(Text.get("library.status.unavailable"));
                return;
            }
        }
        final var destinationRoot = root;
        final var destinationId = rootId;
        final var destinationFolder = folder;
        var store = app.playlists;
        var scanned = app.library;
        String target = store.get(playlistId) != null ? store.displayName(store.get(playlistId)) : folder.isBlank() ? Text.get("text.darkmusic.all_tracks") : folder;
        String destination = root.toString() + " | " + target;
        long started = System.currentTimeMillis();
        app.mediaBusy = true;
        var token = app.mediaToken = new ProcessRunner.Token();
        var accepted = java.util.List.copyOf(paths);
        if (accepted.size() > 1000) {
            app.mediaBusy = false;
            app.warn(Text.get("library.darkmusic.import_limit"));
            return;
        }
        app.importing = true;
        app.importDestination = target;
        var task = app.browse(() -> {
            try {
                var imported = new MediaFiles().importFiles(destinationRoot, destinationId, destinationFolder, accepted, token);
                if (app.importHistory != null)
                    app.importHistory.record(new ImportHistory.Batch(started, destination, accepted.size(), imported.entries(), ""));
                Minecraft.getInstance().execute(() -> {
                    app.importing = false;
                    app.mediaBusy = false;
                    if (app.closed() || app.resetting() || app.playlists != store) return;
                    if (store.get(playlistId) != null) store.addAll(playlistId, imported.tracks());
                    scanned.requestRescan();
                    app.warn(Text.get("library.darkmusic.import_result", imported.tracks().size(), imported.errors().size()));
                    if (!imported.errors().isEmpty())
                        org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Audio import: {}", imported.errors());
                });
            } catch (Exception failure) {
                if (app.importHistory != null)
                    app.importHistory.record(new ImportHistory.Batch(started, destination, accepted.size(), java.util.List.of(), String.valueOf(failure.getMessage())));
                Minecraft.getInstance().execute(() -> {
                    app.importing = false;
                    app.mediaBusy = false;
                    app.warn(Text.failure(failure, "library.darkmusic.import_failed"));
                });
            }
        });
        if (task == null) app.importing = app.mediaBusy = false;
    }

    public CompletableFuture<MediaFiles.Fields> readMetadata(Track track) {
        var result = new CompletableFuture<MediaFiles.Fields>();
        if (app.browse(() -> {
            try {
                result.complete(new MediaFiles().fields(track));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }) == null)
            result.completeExceptionally(busy());
        return result;
    }

    public CompletableFuture<Track> editMetadata(Track track, MediaFiles.Fields original, MediaFiles.Fields fields) {
        if (app.mediaBusy() || app.resetting())
            return CompletableFuture.failedFuture(busy());
        app.mediaBusy = true;
        var token = app.mediaToken = new ProcessRunner.Token();
        var player = app.playback;
        var store = app.playlists;
        var scanned = app.library;
        var resume = player.suspendForEdit();
        var result = new CompletableFuture<Track>();
        var task = app.browse(() -> {
            Track replacement = track;
            Exception problem = null;
            try {
                if (!player.awaitIdle(10))
                    throw new java.io.IOException(Text.get("library.darkmusic.file_busy"));
                replacement = new MediaFiles().edit(track, original, fields, token);
            } catch (Exception e) {
                problem = e;
            }
            var edited = replacement;
            var failure = problem;
            Minecraft.getInstance().execute(() -> {
                app.mediaBusy = false;
                if (app.closed() || app.resetting() || app.playback != player) {
                    result.completeExceptionally(busy());
                    return;
                }
                if (failure == null) {
                    store.replaceTrack(track, edited);
                    app.lyrics.lookup.renamed(track, edited);
                    app.listeningHistory.rename(track, edited);
                    scanned.requestRescan();
                }
                player.finishEdit(resume, track, edited);
                if (failure == null) result.complete(edited);
                else result.completeExceptionally(failure);
            });
        });
        if (task == null) {
            app.mediaBusy = false;
            player.finishEdit(resume, track, track);
            result.completeExceptionally(busy());
        }
        return result;
    }

    public CompletableFuture<AudioDeletion.Plan> prepareAudioDeletion(java.util.List<Track> tracks) {
        var result = new CompletableFuture<AudioDeletion.Plan>();
        var window = new DesktopWindowGuard();
        result.whenComplete((value, failure) -> Minecraft.getInstance().execute(window::close));
        var roots = app.library.roots();
        var selected = java.util.List.copyOf(tracks);
        if (app.browse(() -> {
            try {
                result.complete(new AudioDeletion().prepare(roots, selected));
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        }) == null)
            result.completeExceptionally(busy());
        return result;
    }

    public CompletableFuture<AudioDeletion.Result> deleteAudio(AudioDeletion.Plan plan, AudioDeletion.Mode mode,
                                                               java.util.function.Consumer<AudioDeletion.Progress> progress) {
        return deleteAudio(plan, mode, new ProcessRunner.Token(), progress);
    }

    public CompletableFuture<AudioDeletion.Result> deleteAudio(AudioDeletion.Plan plan, AudioDeletion.Mode mode,
                                                               ProcessRunner.Token token,
                                                               java.util.function.Consumer<AudioDeletion.Progress> progress) {
        java.util.Objects.requireNonNull(mode);
        java.util.Objects.requireNonNull(token);
        if (app.closed() || app.mediaBusy() || app.resetting() || app.outputChanging || !app.playlistsReady || !plan.roots().equals(app.library.roots()))
            return CompletableFuture.failedFuture(busy());
        app.mediaBusy = true;
        app.autoplayPending = false;
        app.mediaToken = token;
        var player = app.playback;
        var store = app.playlists;
        var scanned = app.library;
        var resume = player.suspendForEdit();
        var result = new CompletableFuture<AudioDeletion.Result>();
        var window = mode == AudioDeletion.Mode.TRASH || plan.trashCompanions() ? new DesktopWindowGuard() : null;
        result.whenComplete((value, failure) -> {
            if (window != null) Minecraft.getInstance().execute(window::close);
        });
        var task = app.browse(() -> {
            AudioDeletion.Result deleted = null;
            Exception problem = null;
            try {
                if (!player.awaitIdle(10))
                    throw new java.io.IOException(Text.get("library.darkmusic.file_busy"));
                deleted = new AudioDeletion().delete(plan, mode, token, progress);
                for (var track : deleted.removed()) {
                    try {
                        SourceLinks.forget(track.file());
                        DownloadPipeline.forgetRecorded(track.file());
                    } catch (java.io.IOException cleanupFailure) {
                        org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Could not remove deleted-file provenance", cleanupFailure);
                    }
                }
            } catch (Exception failure) {
                problem = failure;
            }
            var completed = deleted;
            var failure = problem;
            Minecraft.getInstance().execute(() -> {
                app.mediaBusy = false;
                var removed = completed == null ? java.util.Set.<String>of() : completed.removed().stream().map(Track::id).collect(java.util.stream.Collectors.toSet());
                try {
                    store.removeTracks(removed);
                } catch (RuntimeException storageClosed) {
                    org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Deleted files require playlist reconciliation on restart", storageClosed);
                }
                if (app.closed() || app.resetting() || app.playback != player) {
                    if (completed != null) result.complete(completed);
                    else result.completeExceptionally(failure != null ? failure : busy());
                    return;
                }
                app.lyrics.lookup.deleted(removed);
                player.finishDeletion(resume, removed);
                // Read the raw fields: the lazy getters would create a scanner or index just to notify it.
                if (app.cleanupScanner != null) app.cleanupScanner.deleted(removed);
                if (app.cleanupReviews != null) app.cleanupReviews.deleted(removed);
                if (app.sourceIndex != null) app.sourceIndex.deleted(removed);
                if (!removed.isEmpty()) scanned.rescan();
                if (failure == null) result.complete(completed);
                else result.completeExceptionally(failure);
            });
        });
        if (task == null) {
            app.mediaBusy = false;
            player.finishDeletion(resume, java.util.Set.of());
            result.completeExceptionally(busy());
        }
        return result;
    }
}
