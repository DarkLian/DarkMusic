package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Saved lyric files keep their exact on-disk format, whichever Gson instance writes them.
 */
class LyricsJsonFormatTest {
    private static final LyricsDocument DOCUMENT = LyricsDocument.of("lrclib", "42", "Title", "Artist", "Album", 201.5,
        "ja", LyricsDocument.Variant.TRANSLATED, false, false, "[00:01.00]first\n[00:02.50]second");

    private record Holder(LyricsDocument document, List<LyricsDocument> documents, String note) {
    }

    @Test
    void documentsAndContainersSerializeAsBefore() throws IOException {
        assertEquals(golden("document.json"), ConfigStore.JSON.toJson(DOCUMENT));
        assertEquals(golden("choice.json"), ConfigStore.JSON.toJson(new LyricsLookup.Choice(DOCUMENT, -250, "ja", true)));
        assertEquals(golden("holder-null.json"), ConfigStore.JSON.toJson(new Holder(null, List.of(DOCUMENT), "n")));
        assertEquals(DOCUMENT, ConfigStore.JSON.fromJson(golden("document.json"), LyricsDocument.class));
        assertNull(ConfigStore.JSON.fromJson(golden("holder-null.json"), Holder.class).document());
    }

    @Test
    void savedSidecarBytesAreUnchanged(@TempDir Path folder) throws IOException {
        Path audio = Files.writeString(folder.resolve("song.mp3"), "audio");
        DOCUMENT.save(audio, false);
        var paths = LyricsFiles.companions(audio);
        assertEquals(DOCUMENT.text(), Files.readString(paths.getFirst()));
        assertEquals(golden("document.json"), Files.readString(paths.get(1)));
    }

    private static String golden(String name) throws IOException {
        try (InputStream in = LyricsJsonFormatTest.class.getResourceAsStream("/lyrics-json-golden/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
