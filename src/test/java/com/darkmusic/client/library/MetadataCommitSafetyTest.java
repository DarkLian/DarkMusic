package com.darkmusic.client.library;

import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Disposable documents test pathname transactions; these are not audio/codec fixtures.
 */
class MetadataCommitSafetyTest {
    @TempDir
    Path temp;

    private Track identity(Path file) throws Exception {
        return new Track("r", file.getFileName().toString(), file, "", "", "", 0, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
    }

    @Test
    void externalReplacementAfterValidationSurvivesRenameAndInPlaceCommit() throws Exception {
        for (boolean rename : List.of(false, true)) {
            Path directory = Files.createDirectory(temp.resolve("case-" + rename));
            Path source = Files.writeString(directory.resolve("original.txt"), "Original retained bytes");
            var track = identity(source);
            var editor = new MediaFiles(() -> {
                try {
                    Path external = Files.writeString(directory.resolve("external.txt"), "External replacement must survive");
                    Files.move(external, source, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            var original = new MediaFiles.Fields("", "", "", "original", ".txt");
            var requested = new MediaFiles.Fields("", "", "", rename ? "renamed" : "original", ".txt");
            assertThrows(AudioReplacement.RecoveryRequired.class, () -> editor.edit(track, original, requested, new ProcessRunner.Token()));
            assertEquals("External replacement must survive", Files.readString(source));
            assertFalse(Files.exists(directory.resolve("renamed.txt")));
            try (var work = Files.list(directory)) {
                Path retained = work.filter(p -> p.getFileName().toString().startsWith(".darkmusic-work-")).findFirst().orElseThrow().resolve("retained.txt");
                assertEquals("Original retained bytes", Files.readString(retained));
            }
        }
    }

    @Test
    void rollbackDoesNotOverwriteAnExternallyReplacedPublishedFile() throws Exception {
        Path source = Files.writeString(temp.resolve("source.txt"), "Original");
        Path staged = Files.writeString(temp.resolve("staged.txt"), "Edited");
        Path held = temp.resolve("held.txt");
        var edit = new AudioReplacement.Edit(source, source, held, AudioReplacement.Identity.read(source));
        edit.claim();
        edit.install(staged);
        Path external = Files.writeString(temp.resolve("external.txt"), "External");
        Files.move(external, source, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(AudioReplacement.RecoveryRequired.class, edit::rollback);
        assertEquals("External", Files.readString(source));
        assertEquals("Original", Files.readString(held));
    }

    @Test
    void renamingOneFormatCopiesSharedLyricsAndAttribution() throws Exception {
        Path mp3 = Files.writeString(temp.resolve("song.mp3"), "Disposable original identity");
        Path flac = Files.writeString(temp.resolve("song.flac"), "Disposable sibling identity");
        var companions = LyricsFiles.companions(mp3);
        for (Path companion : companions) Files.writeString(companion, "User-authored " + companion.getFileName());
        var original = new MediaFiles.Fields("", "", "", "song", ".mp3");
        new MediaFiles().edit(identity(mp3), original, new MediaFiles.Fields("", "", "", "renamed", ".mp3"), new ProcessRunner.Token());
        var renamed = LyricsFiles.companions(temp.resolve("renamed.mp3"));
        for (int i = 0; i < companions.size(); i++)
            assertEquals(Files.readString(companions.get(i)), Files.readString(renamed.get(i)));
        assertTrue(Files.exists(flac));
    }
}
