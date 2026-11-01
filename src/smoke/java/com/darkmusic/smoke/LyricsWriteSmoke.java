package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.gui.ConfirmationScreen;
import com.darkmusic.client.gui.lyrics.LyricsOptionsScreen;
import com.darkmusic.client.library.LyricsFiles;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.MetadataReader;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Actual menu writes and media admission, exclusively on a disposable copy of private audio.
 */
final class LyricsWriteSmoke implements AutoCloseable {
    private final Path directory;
    private final Track copy;
    private final MediaFiles.Fields original;
    private final LyricsDocument first, second;
    private final CountDownLatch held = new CountDownLatch(2), release = new CountDownLatch(1);
    private CompletableFuture<Void> operation;
    private CompletableFuture<Track> edit;
    private List<byte[]> before;
    private int stage, ticks;

    LyricsWriteSmoke(DarkMusicClient app, Track source) throws Exception {
        Path scratch = Path.of(System.getProperty("darkmusic.verification")).resolve("deferred");
        Files.createDirectories(scratch);
        directory = Files.createTempDirectory(scratch, "lyrics-write-");
        String name = source.file().getFileName().toString();
        Path audio = directory.resolve("copy" + name.substring(name.lastIndexOf('.')));
        Files.copy(source.file(), audio);
        copy = new MetadataReader().read("lyrics-write-smoke", audio.getFileName().toString(), audio,
            Files.size(audio), Files.getLastModifiedTime(audio).toMillis());
        original = new MediaFiles().fields(copy);
        first = document("[00:01]Original disposable lyrics");
        second = document("[00:01]Replacement disposable lyrics");
        operation = app.sources().lyricsOperations().select(copy, first);
    }

    private LyricsDocument document(String text) {
        return LyricsDocument.of("import", "write-smoke", copy.title(), copy.artist(), copy.album(), copy.duration(), "und",
            LyricsDocument.Variant.ORIGINAL, false, false, text);
    }

    private void options(Minecraft mc, DarkMusicClient app) {
        mc.gui.setScreen(new LyricsOptionsScreen(app.sources(), null, copy));
        mc.gui.screen().keyPressed(new KeyEvent(269, 0, 0));
    }

    private void confirm(Minecraft mc, String key) {
        SmokeUi.click(mc, Text.get(key));
        check(mc.gui.screen() instanceof ConfirmationScreen, "Lyrics action asks for confirmation");
        SmokeUi.click(mc, Text.get(key));
    }

    private boolean contains(String text) throws Exception {
        Path sidecar = LyricsFiles.sidecar(copy.file());
        return Files.isRegularFile(sidecar) && Files.readString(sidecar).contains(text);
    }

    private List<byte[]> companions() throws Exception {
        var values = new ArrayList<byte[]>();
        for (Path file : LyricsFiles.companions(copy.file()))
            values.add(Files.exists(file) ? Files.readAllBytes(file) : new byte[0]);
        return values;
    }

    private void holdBrowse() {
        held.countDown();
        try {
            if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("Browse fence timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void refused(CompletableFuture<Void> result) {
        check(result.isCompletedExceptionally(), "Busy lyric operation is refused immediately");
        try {
            result.join();
            throw new AssertionError("Busy lyric operation succeeded");
        } catch (CompletionException e) {
            check(e.getCause() instanceof IllegalStateException && e.getCause().getMessage().equals(Text.get("library.darkmusic.busy")), "Original busy feedback");
        }
    }

    boolean tick(Minecraft mc, DarkMusicClient app) throws Exception {
        check(++ticks < 600, "Lyrics write smoke timeout at " + stage);
        if (operation != null) {
            if (!operation.isDone()) return false;
            operation.join();
            operation = null;
        }
        app.services().lyrics().request(copy);
        switch (stage) {
            case 0 -> {
                if (!app.services().lyrics().inspected(copy)) return false;
                options(mc, app);
                confirm(mc, "lyrics.save");
                stage++;
            }
            case 1 -> {
                if (!contains("Original disposable lyrics") || !(mc.gui.screen() instanceof LyricsOptionsScreen))
                    return false;
                operation = app.sources().lyricsOperations().select(copy, second);
                stage++;
            }
            case 2 -> {
                if (!app.services().lyrics().sidecarExists(copy)) return false;
                options(mc, app);
                confirm(mc, "lyrics.replace");
                stage++;
            }
            case 3 -> {
                if (!contains("Replacement disposable lyrics") || !app.services().lyrics().backupExists(copy)
                    || !(mc.gui.screen() instanceof LyricsOptionsScreen)) return false;
                options(mc, app);
                confirm(mc, "lyrics.restore");
                stage++;
            }
            case 4 -> {
                if (!contains("Original disposable lyrics") || !(mc.gui.screen() instanceof LyricsOptionsScreen))
                    return false;
                operation = app.sources().lyricsOperations().save(copy, second, true);
                stage++;
            }
            case 5 -> {
                before = companions();
                app.sources().browsing().submit(this::holdBrowse);
                app.sources().browsing().submit(this::holdBrowse);
                stage++;
            }
            case 6 -> {
                if (held.getCount() != 0) return false;
                edit = app.sources().editMetadata(copy, original, new MediaFiles.Fields(original.title(), original.artist(), original.album(), "renamed-copy", original.extension()));
                check(app.sources().mediaBusy() && !edit.isDone(), "Actual metadata edit owns admission while queued");
                refused(app.sources().lyricsOperations().save(copy, first, true));
                refused(app.sources().lyricsOperations().restore(copy));
                options(mc, app);
                confirm(mc, "lyrics.replace");
                stage++;
            }
            case 7 -> {
                check(mc.gui.screen() instanceof ConfirmationScreen && mc.gui.screen().shouldCloseOnEsc(), "Failed replace remains on its usable confirmation");
                mc.gui.screen().onClose();
                confirm(mc, "lyrics.restore");
                stage++;
            }
            case 8 -> {
                check(mc.gui.screen() instanceof ConfirmationScreen && mc.gui.screen().shouldCloseOnEsc(), "Failed restore remains on its usable confirmation");
                var after = companions();
                for (int i = 0; i < before.size(); i++)
                    check(Arrays.equals(before.get(i), after.get(i)), "Busy controls preserve every companion byte");
                release.countDown();
                stage++;
            }
            case 9 -> {
                if (!edit.isDone()) return false;
                var renamed = edit.join();
                check(Files.isRegularFile(renamed.file()) && !Files.exists(copy.file()), "Queued metadata rename completed on the copy");
                check(!app.sources().mediaBusy(), "Metadata edit released admission");
                System.out.println("DARKMUSIC_LYRICS_WRITE_PASS: actual Lyrics Options save/replace/restore, rejected save/restore during queued metadata edit, byte-identical companions and completed rename; disposable collection copy only");
                return true;
            }
            default -> throw new AssertionError(stage);
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override
    public void close() throws Exception {
        release.countDown();
        if (edit != null && !edit.isDone()) return; // Retain evidence rather than race an in-flight file operation.
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
