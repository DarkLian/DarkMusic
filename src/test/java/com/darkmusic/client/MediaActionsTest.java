package com.darkmusic.client;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.cleanup.DuplicateScanner;
import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.library.AudioDeletion;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.FakeAudio;
import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Imports, edits and deletions hold the media lock from start until their result reaches the client thread, on
 * every outcome. The browse pool runs each task at once and the client thread is a queue drained by hand. Every file
 * is a temporary copy of a song from the private collection; deletion is permanent, on those copies only, and
 * never uses the desktop Trash.
 */
class MediaActionsTest {
    @TempDir
    Path temp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Runnable> client = new ArrayDeque<>();
    private final List<String> warnings = new ArrayList<>();
    private final ClientState state = new ClientState();
    private final FakeAudio audio = new FakeAudio();
    private final PlaybackController playback = new PlaybackController(audio, text -> {
    });
    private final ArrayDeque<Runnable> browsing = new ArrayDeque<>();
    private boolean ready = true, refuseBrowse, deferBrowse;
    private int autoplayCancelled, copies;
    private Path root;
    private MusicLibrary library;
    private PlaylistStore playlists;
    private ImportHistory imports;
    private ListeningHistory history;
    private LyricsService lyrics;
    private MediaActions media;

    @BeforeEach
    void open() throws Exception {
        root = Files.createDirectories(temp.resolve("music"));
        Files.createDirectories(root.resolve("new"));
        library = new MusicLibrary(temp.resolve("data"));
        playlists = new PlaylistStore(temp.resolve("data"), io, warnings::add);
        playlists.loaded(List.of());
        imports = new ImportHistory(temp.resolve("data"), io, warnings::add);
        history = new ListeningHistory(temp.resolve("data"), Runnable::run, warnings::add);
        history.load();
        lyrics = new LyricsService(temp.resolve("lyrics"));
        media = new MediaActions(new MediaActions.Inputs() {
            public MusicLibrary library() {
                return library;
            }

            public PlaybackController playback() {
                return playback;
            }

            public PlaylistStore playlists() {
                return playlists;
            }

            public boolean playlistsReady() {
                return ready;
            }

            public ImportHistory importHistory() {
                return imports;
            }

            public ListeningHistory listeningHistory() {
                return history;
            }

            public LyricsService lyrics() {
                return lyrics;
            }

            public DuplicateScanner openCleanup() {
                return null;
            }

            public DuplicateReviewStore openCleanupReviews() {
                return null;
            }

            public LibrarySourceIndex openSourceIndex() {
                return null;
            }

            public Future<?> browse(Runnable task) {
                if (refuseBrowse) return null;
                if (deferBrowse) browsing.add(task);
                else task.run();
                return CompletableFuture.completedFuture(null);
            }

            public void warn(String text) {
                warnings.add(text);
            }
        }, state, client::add, () -> autoplayCancelled++);
    }

    @AfterEach
    void close() {
        library.close();
        lyrics.close();
        playback.close();
        io.shutdownNow();
    }

    private void drain() {
        while (!client.isEmpty()) client.poll().run();
    }

    /**
     * Temporary copies of real songs, outside the library.
     */
    private List<Path> incoming(int count) throws Exception {
        var files = TestMusic.files();
        Path folder = Files.createDirectories(temp.resolve("incoming"));
        var copies = new ArrayList<Path>();
        for (int i = 0; i < count; i++) {
            Path source = files.get(i % files.size());
            copies.add(Files.copy(source, folder.resolve(this.copies++ + "-" + source.getFileName())));
        }
        return copies;
    }

    private List<Track> scanned(int count) throws Exception {
        for (Path file : incoming(count)) Files.copy(file, root.resolve(file.getFileName()));
        library.setRoot(root, "r");
        Await.of(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == count);
        return library.snapshot().tracks();
    }

    @Test
    void anImportWaitsForIdleAndLoadedPlaylists() throws Exception {
        library.setRoot(root, "r");
        state.mediaBusy = true;
        media.importAudio(List.of(temp.resolve("absent.mp3")), "", "");
        state.mediaBusy = false;
        ready = false;
        media.importAudio(List.of(temp.resolve("absent.mp3")), "", "");
        assertEquals(List.of(Text.get("library.darkmusic.busy"), Text.get("library.darkmusic.busy")), warnings);
        assertFalse(media.importing());
        assertTrue(client.isEmpty());
    }

    @Test
    void anImportHoldsTheLockUntilItsResultArrivesAndFillsThePlaylist() throws Exception {
        var sources = incoming(2);
        library.setRoot(root, "r");
        String mix = playlists.create("Mix");
        media.importAudio(sources, "@r/new", mix);
        assertTrue(state.mediaBusy, "held until the client thread takes the result");
        assertTrue(media.importing());
        assertEquals("Mix", media.importDestination(), "an import into a playlist names the playlist");
        drain();
        assertFalse(state.mediaBusy);
        assertFalse(media.importing());
        for (Path source : sources) assertTrue(Files.exists(root.resolve("new").resolve(source.getFileName())));
        assertEquals(2, playlists.get(mix).entries().size());
        assertEquals(Text.get("library.darkmusic.import_result", 2, 0), warnings.getLast());
        assertTrue(sources.stream().allMatch(Files::exists), "the originals stay");
        media.importAudio(incoming(1), "@r/new", "");
        assertEquals("new", media.importDestination(), "otherwise the folder");
        drain();
    }

    @Test
    void anUnknownRootOrAFullBatchNeverTakesTheLock() throws Exception {
        library.setRoot(root, "r");
        media.importAudio(List.of(temp.resolve("absent.mp3")), "@gone/x", "");
        assertEquals(Text.get("library.status.unavailable"), warnings.getLast());
        media.importAudio(Collections.nCopies(1001, temp.resolve("absent.mp3")), "", "");
        assertEquals(Text.get("library.darkmusic.import_limit"), warnings.getLast());
        assertFalse(state.mediaBusy);
        assertFalse(media.importing());
    }

    @Test
    void aCancelledImportReleasesTheLockAndCopiesNothing() throws Exception {
        var sources = incoming(1);
        library.setRoot(root, "r");
        deferBrowse = true;
        media.importAudio(sources, "", "");
        assertTrue(state.mediaBusy);
        // Minecraft closing while the import waits for the browse pool.
        media.cancel();
        browsing.poll().run();
        drain();
        assertFalse(state.mediaBusy);
        assertFalse(media.importing());
        assertTrue(Files.notExists(root.resolve(sources.getFirst().getFileName())));
        assertTrue(Files.exists(sources.getFirst()));
    }

    @Test
    void aRefusedBrowseSubmissionReleasesTheLock() throws Exception {
        library.setRoot(root, "r");
        refuseBrowse = true;
        media.importAudio(incoming(1), "", "");
        assertFalse(state.mediaBusy);
        assertFalse(media.importing());
        var tracks = List.of(new Track("r", "x.mp3", root.resolve("x.mp3"), "X", "", "", 1, 1, 1, ""));
        var edit = media.editMetadata(tracks.getFirst(), new MediaFiles.Fields("X", "", "", "x", ".mp3"), new MediaFiles.Fields("Y", "", "", "x", ".mp3"));
        assertTrue(edit.isCompletedExceptionally());
        assertFalse(state.mediaBusy);
        var plan = new AudioDeletion.Plan(library.roots(), List.of(), false, List.of(), false);
        var deletion = media.deleteAudio(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), p -> {
        });
        assertTrue(deletion.isCompletedExceptionally());
        assertFalse(state.mediaBusy);
    }

    @Test
    void aFailedEditReleasesTheLockAndKeepsTheTrack() throws Exception {
        library.setRoot(root, "r");
        var missing = new Track("r", "missing.mp3", root.resolve("missing.mp3"), "Missing", "", "", 1, 1, 1, "");
        state.mediaBusy = true;
        assertTrue(media.editMetadata(missing, new MediaFiles.Fields("Missing", "", "", "missing", ".mp3"), new MediaFiles.Fields("Edited", "", "", "missing", ".mp3")).isCompletedExceptionally());
        state.mediaBusy = false;
        var result = media.editMetadata(missing, new MediaFiles.Fields("Missing", "", "", "missing", ".mp3"), new MediaFiles.Fields("Edited", "", "", "missing", ".mp3"));
        assertTrue(state.mediaBusy);
        drain();
        assertFalse(state.mediaBusy);
        assertThrows(ExecutionException.class, result::get);
    }

    private static AudioDeletion.Item item(Track track) throws Exception {
        var attributes = Files.readAttributes(track.file(), BasicFileAttributes.class);
        return new AudioDeletion.Item(track, track.file(), attributes.fileKey(), attributes.fileKey() == null ? SourceLinks.contentHash(track.file()) : "");
    }

    @Test
    void aDeletionWaitsForAcceptingWorkAndTheSameRoots() throws Exception {
        var tracks = scanned(1);
        var plan = new AudioDeletion.Plan(library.roots(), List.of(item(tracks.getFirst())), false, List.of(), false);
        for (int blocked = 0; blocked < 3; blocked++) {
            state.mediaBusy = blocked == 0;
            state.outputChanging = blocked == 1;
            ready = blocked != 2;
            assertTrue(media.deleteAudio(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), p -> {
            }).isCompletedExceptionally());
        }
        state.mediaBusy = state.outputChanging = false;
        ready = true;
        var other = new AudioDeletion.Plan(Map.of(temp, "other"), plan.items(), false, List.of(), false);
        assertTrue(media.deleteAudio(other, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), p -> {
        }).isCompletedExceptionally(), "a plan for other folders is refused");
        assertEquals(0, autoplayCancelled);
        assertTrue(Files.exists(tracks.getFirst().file()));
    }

    @Test
    void aDeletionCancelsAutoplayHoldsTheLockAndPassesTheResultThrough() throws Exception {
        var tracks = scanned(2);
        var doomed = tracks.getFirst();
        String mix = playlists.create("Mix");
        playlists.addAll(mix, tracks);
        var plan = new AudioDeletion.Plan(library.roots(), List.of(item(doomed)), false, List.of(), false);
        var progress = new ArrayList<AudioDeletion.Progress>();
        var result = media.deleteAudio(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), progress::add);
        assertEquals(1, autoplayCancelled);
        assertTrue(state.mediaBusy);
        drain();
        assertFalse(state.mediaBusy);
        var deleted = result.get();
        assertEquals(List.of(doomed), deleted.removed());
        assertTrue(deleted.failed().isEmpty());
        assertFalse(progress.isEmpty());
        assertTrue(Files.notExists(doomed.file()));
        assertTrue(Files.exists(tracks.get(1).file()));
        assertEquals(List.of(tracks.get(1)), playlists.resolve(mix, tracks));
    }
}
