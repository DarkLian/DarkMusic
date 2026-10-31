package com.darkmusic.client;

import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.gui.DesktopWindowGuard;
import com.darkmusic.client.library.AudioDeletion;
import com.darkmusic.client.library.DownloadArchive;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Changes to audio files: import, metadata edits and deletion. Each holds the app's media lock while it runs.
 * Work captures the playback, library and playlist objects it started with; when Reset to Defaults replaces them,
 * the identity check on completion discards the stale result.
 */
public final class MediaActions {
    private final ClientServices services;
    // Deleting audio files supersedes a pending autoplay, which would otherwise start a song being removed.
    private final Runnable cancelAutoplay;
    private boolean importing;
    private String importDestination = "";
    private ProcessRunner.Token mediaToken;

    MediaActions(ClientServices services, Runnable cancelAutoplay) {
        this.services = services;
        this.cancelAutoplay = cancelAutoplay;
    }

    boolean importing() {
        return importing;
    }

    String importDestination() {
        return importDestination;
    }

    /**
     * Minecraft is closing: stops the running import, edit or deletion.
     */
    void cancel() {
        if (mediaToken != null) mediaToken.cancel();
    }

    private static IllegalStateException busy() {
        return new IllegalStateException(Text.get("library.darkmusic.busy"));
    }

    public void importAudio(List<Path> paths, String folder, String playlistId) {
        if (!services.state.idle() || !services.playlistsReady()) {
            services.warn(Text.get("library.darkmusic.busy"));
            return;
        }
        var library = services.library();
        var root = library.root();
        String rootId = library.rootId();
        if (folder.startsWith("@")) {
            int slash = folder.indexOf('/');
            rootId = folder.substring(1, slash < 0 ? folder.length() : slash);
            root = library.directory(rootId);
            folder = slash < 0 ? "" : folder.substring(slash + 1);
            if (root == null) {
                services.warn(Text.get("library.status.unavailable"));
                return;
            }
        }
        final var destinationRoot = root;
        final var destinationId = rootId;
        final var destinationFolder = folder;
        var store = services.playlists();
        var scanned = services.library();
        String target = store.get(playlistId) != null ? store.displayName(store.get(playlistId)) : folder.isBlank() ? Text.get("text.darkmusic.all_tracks") : folder;
        String destination = root.toString() + " | " + target;
        long started = System.currentTimeMillis();
        services.state.mediaBusy = true;
        var token = mediaToken = new ProcessRunner.Token();
        var accepted = List.copyOf(paths);
        if (accepted.size() > 1000) {
            services.state.mediaBusy = false;
            services.warn(Text.get("library.darkmusic.import_limit"));
            return;
        }
        importing = true;
        importDestination = target;
        var task = services.browse(() -> {
            try {
                var imported = new MediaFiles().importFiles(destinationRoot, destinationId, destinationFolder, accepted, token);
                if (services.importHistory() != null)
                    services.importHistory().record(new ImportHistory.Batch(started, destination, accepted.size(), imported.entries(), ""));
                Minecraft.getInstance().execute(() -> {
                    importing = false;
                    services.state.mediaBusy = false;
                    if (!services.state.alive() || services.playlists() != store) return;
                    if (store.get(playlistId) != null) store.addAll(playlistId, imported.tracks());
                    scanned.requestRescan();
                    services.warn(Text.get("library.darkmusic.import_result", imported.tracks().size(), imported.errors().size()));
                    if (!imported.errors().isEmpty())
                        LoggerFactory.getLogger("darkmusic").warn("Audio import: {}", imported.errors());
                });
            } catch (Exception failure) {
                if (services.importHistory() != null)
                    services.importHistory().record(new ImportHistory.Batch(started, destination, accepted.size(), List.of(), String.valueOf(failure.getMessage())));
                Minecraft.getInstance().execute(() -> {
                    importing = false;
                    services.state.mediaBusy = false;
                    services.warn(Text.failure(failure, "library.darkmusic.import_failed"));
                });
            }
        });
        if (task == null) importing = services.state.mediaBusy = false;
    }

    public CompletableFuture<MediaFiles.Fields> readMetadata(Track track) {
        var result = new CompletableFuture<MediaFiles.Fields>();
        if (services.browse(() -> {
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
        if (!services.state.idle())
            return CompletableFuture.failedFuture(busy());
        services.state.mediaBusy = true;
        var token = mediaToken = new ProcessRunner.Token();
        var player = services.playback();
        var store = services.playlists();
        var scanned = services.library();
        var resume = player.suspendForEdit();
        var result = new CompletableFuture<Track>();
        var task = services.browse(() -> {
            Track replacement = track;
            Exception problem = null;
            try {
                if (!player.awaitIdle(10))
                    throw new IOException(Text.get("library.darkmusic.file_busy"));
                replacement = new MediaFiles().edit(track, original, fields, token);
            } catch (Exception e) {
                problem = e;
            }
            var edited = replacement;
            var failure = problem;
            Minecraft.getInstance().execute(() -> {
                services.state.mediaBusy = false;
                if (!services.state.alive() || services.playback() != player) {
                    result.completeExceptionally(busy());
                    return;
                }
                if (failure == null) {
                    store.replaceTrack(track, edited);
                    services.lyrics().lookup.renamed(track, edited);
                    services.listeningHistory().rename(track, edited);
                    scanned.requestRescan();
                }
                player.finishEdit(resume, track, edited);
                if (failure == null) result.complete(edited);
                else result.completeExceptionally(failure);
            });
        });
        if (task == null) {
            services.state.mediaBusy = false;
            player.finishEdit(resume, track, track);
            result.completeExceptionally(busy());
        }
        return result;
    }

    public CompletableFuture<AudioDeletion.Plan> prepareAudioDeletion(List<Track> tracks) {
        var result = new CompletableFuture<AudioDeletion.Plan>();
        var window = new DesktopWindowGuard();
        result.whenComplete((value, failure) -> Minecraft.getInstance().execute(window::close));
        var roots = services.library().roots();
        var selected = List.copyOf(tracks);
        if (services.browse(() -> {
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
                                                               ProcessRunner.Token token,
                                                               Consumer<AudioDeletion.Progress> progress) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(token);
        if (!services.state.accepting() || !services.playlistsReady() || !plan.roots().equals(services.library().roots()))
            return CompletableFuture.failedFuture(busy());
        services.state.mediaBusy = true;
        cancelAutoplay.run();
        mediaToken = token;
        var player = services.playback();
        var store = services.playlists();
        var scanned = services.library();
        var resume = player.suspendForEdit();
        var result = new CompletableFuture<AudioDeletion.Result>();
        var window = mode == AudioDeletion.Mode.TRASH || plan.trashCompanions() ? new DesktopWindowGuard() : null;
        result.whenComplete((value, failure) -> {
            if (window != null) Minecraft.getInstance().execute(window::close);
        });
        var task = services.browse(() -> {
            AudioDeletion.Result deleted = null;
            Exception problem = null;
            try {
                if (!player.awaitIdle(10))
                    throw new IOException(Text.get("library.darkmusic.file_busy"));
                deleted = new AudioDeletion().delete(plan, mode, token, progress);
                for (var track : deleted.removed()) {
                    try {
                        SourceLinks.forget(track.file());
                        DownloadArchive.forgetRecorded(track.file());
                    } catch (IOException cleanupFailure) {
                        LoggerFactory.getLogger("darkmusic").warn("Could not remove deleted-file provenance", cleanupFailure);
                    }
                }
            } catch (Exception failure) {
                problem = failure;
            }
            var completed = deleted;
            var failure = problem;
            Minecraft.getInstance().execute(() -> {
                services.state.mediaBusy = false;
                var removed = completed == null ? Set.<String>of() : completed.removed().stream().map(Track::id).collect(Collectors.toSet());
                try {
                    store.removeTracks(removed);
                } catch (RuntimeException storageClosed) {
                    LoggerFactory.getLogger("darkmusic").warn("Deleted files require playlist reconciliation on restart", storageClosed);
                }
                if (!services.state.alive() || services.playback() != player) {
                    if (completed != null) result.complete(completed);
                    else result.completeExceptionally(failure);
                    return;
                }
                services.lyrics().lookup.deleted(removed);
                player.finishDeletion(resume, removed);
                // Read the raw fields: the lazy getters would create a scanner or index just to notify it.
                if (services.openCleanup() != null) services.openCleanup().deleted(removed);
                if (services.openCleanupReviews() != null) services.openCleanupReviews().deleted(removed);
                if (services.openSourceIndex() != null) services.openSourceIndex().deleted(removed);
                if (!removed.isEmpty()) scanned.rescan();
                if (failure == null) result.complete(completed);
                else result.completeExceptionally(failure);
            });
        });
        if (task == null) {
            services.state.mediaBusy = false;
            player.finishDeletion(resume, Set.of());
            result.completeExceptionally(busy());
        }
        return result;
    }
}
