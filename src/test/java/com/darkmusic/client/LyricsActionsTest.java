package com.darkmusic.client;

import com.darkmusic.client.library.LyricsFiles;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A metadata edit renames a song with its lyric files, so a lyric file written next to the song while it runs would
 * stay behind under the old name. Writing next to the song therefore waits until no media operation owns the files.
 * The song is a temporary copy from the private collection.
 */
class LyricsActionsTest {
    @TempDir
    Path temp;
    private final ClientState state = new ClientState();
    private LyricsService lyrics;
    private LyricsActions actions;
    private Track track;

    @BeforeEach
    void open() throws Exception {
        Path source = TestMusic.featured();
        Path song = Files.copy(source, Files.createDirectories(temp.resolve("music")).resolve("song" + source.getFileName().toString().substring(source.getFileName().toString().lastIndexOf('.'))));
        track = new Track("r", song.getFileName().toString(), song, "Song", "Artist", "Album", 1, Files.size(song),
            Files.getLastModifiedTime(song).toMillis(), "");
        lyrics = new LyricsService(temp.resolve("lyrics"));
        actions = new LyricsActions(() -> lyrics, state);
    }

    @AfterEach
    void close() {
        lyrics.close();
    }

    private LyricsDocument document() {
        return LyricsDocument.of("test", "", "Song", "Artist", "Album", 1, "und", LyricsDocument.Variant.UNKNOWN, false, false, "[00:00.00]First line");
    }

    private List<Path> sidecars() {
        return LyricsFiles.companions(track.file()).stream().filter(Files::exists).toList();
    }

    @Test
    void aLyricFileIsNotWrittenNextToTheSongWhileAMediaOperationRuns() throws Exception {
        state.mediaBusy = true;
        var save = actions.save(track, document(), false);
        assertThrows(ExecutionException.class, () -> save.get(10, TimeUnit.SECONDS));
        assertTrue(actions.restore(track).isCompletedExceptionally());
        assertTrue(actions.keepCurrentRecovery(track).isCompletedExceptionally());
        assertTrue(sidecars().isEmpty());
    }

    @Test
    void resetAndShutdownRefuseTooAndAnIdleClientWrites() throws Exception {
        state.resetting = true;
        assertTrue(actions.save(track, document(), false).isCompletedExceptionally());
        state.resetting = false;
        actions.save(track, document(), false).get(10, TimeUnit.SECONDS);
        assertFalse(sidecars().isEmpty());
    }

    @Test
    void choicesStillApplyWhileBusy() throws Exception {
        state.mediaBusy = true;
        actions.offset(track, 250).get(10, TimeUnit.SECONDS);
        assertEquals(250, lyrics.lookup.choice(track).offset());
    }
}
