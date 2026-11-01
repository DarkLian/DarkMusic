package com.darkmusic.client.library;

import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ProcessRunner;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MediaFilesFaultTest {
    @TempDir
    Path temp;

    private Track track(Path path) throws Exception {
        return new MetadataReader().read("r", path.getFileName().toString(), path,
            Files.size(path), Files.getLastModifiedTime(path).toMillis());
    }

    @Test
    void eachStagedValidationRejectsCorruptionBeforeTheSourceOrCompanionsChange() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            Path directory = Files.createDirectory(temp.resolve("validation-" + variant));
            Path source = Files.copy(TestMusic.featured(), directory.resolve("original.mp3"));
            byte[] originalBytes = Files.readAllBytes(source);
            Path lyrics = Files.writeString(LyricsFiles.sidecar(source), "private disposable lyrics");
            var before = track(source);
            var ordinary = new MediaFiles();
            var fields = ordinary.fields(before);
            int scenario = variant;
            var editor = new MediaFiles(() -> fail("commit must not start"), new MediaFiles.FileOps() {
            }, staged -> {
                if (scenario == 2) TestMusic.writeWav(staged);
                else {
                    var audio = AudioFileIO.read(staged.toFile());
                    if (scenario == 0) audio.getTag().setField(FieldKey.TITLE, "corrupted staged title");
                    else {
                        assertNotNull(new MetadataReader().artwork(staged));
                        audio.getTag().deleteArtworkField();
                    }
                    audio.commit();
                }
            });
            var requested = new MediaFiles.Fields(variant == 2 ? fields.title() : "requested title", fields.artist(),
                fields.album(), "renamed", fields.extension());
            var failure = assertThrows(IOException.class,
                () -> editor.edit(before, fields, requested, new ProcessRunner.Token()));
            String key = switch (variant) {
                case 0 -> "library.darkmusic.tags_not_saved";
                case 1 -> "library.darkmusic.cover_not_preserved";
                default -> "library.darkmusic.format_changed";
            };
            assertEquals(Text.get(key), failure.getMessage());
            assertArrayEquals(originalBytes, Files.readAllBytes(source));
            assertEquals("private disposable lyrics", Files.readString(lyrics));
            assertFalse(Files.exists(directory.resolve("renamed.mp3")));
            try (var children = Files.list(directory)) {
                assertFalse(children.anyMatch(p -> p.getFileName().toString().startsWith(".darkmusic-work-")));
            }
        }
    }

    @Test
    void independentCompanionRollbackRefusalIsSuppressedAndRetainsRecoveryWork() throws Exception {
        Path source = Files.writeString(temp.resolve("original.bin"), "original identity fixture");
        Path lyrics = Files.writeString(temp.resolve("original.lrc"), "lyrics");
        Path text = Files.writeString(temp.resolve("original.lrc.darkmusic.json"), "companion identity fixture");
        var originalFailure = new IOException("second companion move denied");
        var rollbackFailure = new IOException("first companion rollback denied");
        var cleanup = new AtomicBoolean();
        var files = new MediaFiles.FileOps() {
            public void move(Path from, Path to) throws IOException {
                if (from.equals(text)) throw originalFailure;
                if (from.equals(temp.resolve("renamed.lrc"))) throw rollbackFailure;
                MediaFiles.FileOps.super.move(from, to);
            }

            public void deleteTree(Path path) throws IOException {
                cleanup.set(true);
                MediaFiles.FileOps.super.deleteTree(path);
            }
        };
        var before = track(source);
        var fields = new MediaFiles.Fields("", "", "", "original", ".bin");
        var requested = new MediaFiles.Fields("", "", "", "renamed", ".bin");
        var failure = assertThrows(IOException.class,
            () -> new MediaFiles(() -> {
            }, files, staged -> {
            }).edit(before, fields, requested, new ProcessRunner.Token()));
        assertSame(originalFailure, failure);
        assertArrayEquals(new Throwable[]{rollbackFailure}, failure.getSuppressed());
        assertEquals("original identity fixture", Files.readString(source));
        assertEquals("companion identity fixture", Files.readString(text));
        assertFalse(Files.exists(lyrics));
        assertEquals("lyrics", Files.readString(temp.resolve("renamed.lrc")));
        assertFalse(Files.exists(temp.resolve("renamed.bin")));
        assertFalse(cleanup.get(), "retained recovery work must not be offered for cleanup");
        try (var children = Files.list(temp)) {
            Path work = children.filter(p -> p.getFileName().toString().startsWith(".darkmusic-work-")).findFirst().orElseThrow();
            assertEquals("original identity fixture", Files.readString(work.resolve("retained.bin")));
            assertTrue(Files.exists(work.resolve("edited.bin")));
        }
    }

    @Test
    void cleanupRefusalKeepsTheSuccessfulEditAndLeavesOnlyItsDisposableWork() throws Exception {
        Path source = Files.copy(TestMusic.featured(), temp.resolve("original.mp3"));
        var reached = new AtomicBoolean();
        var operations = new MediaFiles.FileOps() {
            public void deleteTree(Path work) throws IOException {
                reached.set(true);
                throw new IOException("metadata scratch cleanup denied");
            }
        };
        var editor = new MediaFiles(() -> {
        }, operations, staged -> {
        });
        var before = track(source);
        var fields = editor.fields(before);
        var updated = editor.edit(before, fields,
            new MediaFiles.Fields("updated title", fields.artist(), fields.album(), "renamed", fields.extension()), new ProcessRunner.Token());
        assertTrue(reached.get());
        assertEquals("updated title", editor.fields(updated).title());
        assertTrue(Files.exists(updated.file()));
        assertFalse(Files.exists(source));
        try (var children = Files.list(temp)) {
            assertEquals(1, children.filter(p -> p.getFileName().toString().startsWith(".darkmusic-work-")).count());
        }
    }
}
