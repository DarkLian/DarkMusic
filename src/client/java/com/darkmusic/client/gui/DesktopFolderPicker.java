package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The desktop modal loop lives in an isolated JDK helper, never Minecraft's render thread.
 */
public final class DesktopFolderPicker {
    private static final AtomicBoolean OPEN = new AtomicBoolean(), CANCELLED = new AtomicBoolean();
    private static volatile Process active;

    private DesktopFolderPicker() {
    }

    public static boolean isOpen() {
        return OPEN.get();
    }

    public static CompletableFuture<Path> choose(Path initial, String title) {
        return choose(initial, title, "folder");
    }

    public static CompletableFuture<Path> choose(Path initial, String title, String mode) {
        if (!OPEN.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        CANCELLED.set(false);
        return CompletableFuture.supplyAsync(() -> {
            Path result = null, log = null;
            try {
                result = Files.createTempFile("darkmusic-folder-", ".txt");
                log = Files.createTempFile("darkmusic-folder-", ".log");
                String binary = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
                Path java = Path.of(System.getProperty("java.home"), "bin", binary);
                Path classes = Path.of(DesktopFolderDialog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                active = new ProcessBuilder(List.of(java.toString(), "-Djava.awt.headless=false", "-cp", classes.toString(), DesktopFolderDialog.class.getName(), initial.toString(), title, result.toString(), Long.toString(ProcessHandle.current().pid()), mode, Text.get("backup.overwrite")))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                active.getOutputStream().close();
                while (!active.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (CANCELLED.get()) {
                        active.destroy();
                        if (!active.waitFor(2, TimeUnit.SECONDS)) active.destroyForcibly();
                    }
                }
                if (CANCELLED.get()) return null;
                if (active.exitValue() != 0)
                    throw new java.io.IOException("Desktop dialog failed: " + Files.readString(log));
                String selected = Files.readString(result);
                return selected.isBlank() ? null : Path.of(selected);
            } catch (Exception e) {
                throw new CompletionException(e);
            } finally {
                var process = active;
                if (process != null && process.isAlive()) process.destroyForcibly();
                active = null;
                for (Path file : new Path[]{result, log})
                    if (file != null) try {
                        Files.deleteIfExists(file);
                    } catch (java.io.IOException ignored) {
                    }
                OPEN.set(false);
            }
        }, task -> Thread.ofVirtual().name("darkmusic-folder-dialog").start(task));
    }

    public static void cancel() {
        CANCELLED.set(true);
    }

    public static void open(DarkMusicClient app, java.util.function.BooleanSupplier current) {
        if (OPEN.get()) return;
        var mc = Minecraft.getInstance();
        boolean fullscreen = mc.getWindow().isFullscreen();
        if (fullscreen) mc.getWindow().toggleFullScreen();
        choose(app.library.root(), Text.get("text.darkmusic.choose_music_folder")).whenComplete((path, error) -> mc.execute(() -> {
            if (fullscreen && !mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
            if (!current.getAsBoolean()) return;
            if (error != null) app.warn(Text.get("folder.darkmusic.failed", Text.failure(error, "error.folder")));
            else if (path != null) app.chooseDirectory(path, () -> {
            });
        }));
    }
}
