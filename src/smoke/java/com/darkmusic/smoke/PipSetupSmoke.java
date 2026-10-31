package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.gui.PipScreen;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Isolated UI acceptance check. No network, window enumeration or capture is approved by this test.
 */
final class PipSetupSmoke {
    private int stage;
    private CompletableFuture<Void> staged;
    private final long deadline = System.nanoTime() + 120_000_000_000L;

    boolean tick(Minecraft mc, DarkMusicClient app) throws Exception {
        if (System.nanoTime() > deadline) throw new IllegalStateException("PiP setup smoke timed out at " + stage);
        var setup = app.services().pip().setup();
        var s = app.services().settings().pip;
        switch (stage) {
            case 0 -> {
                if (!(mc.gui.screen() instanceof PipScreen)) return false;
                if (s.enabled || s.audioAccess || setup.ready() || app.services().pip().active())
                    throw new IllegalStateException("Access must default off");
                if (SmokeUi.find(mc, Text.get("pip.choose")) != null)
                    throw new IllegalStateException("Picker accessible before consent");
                SmokeUi.click(mc, Text.get("pip.setup.check"));
                stage++;
            }
            case 1 -> {
                if (setup.busy()) return false;
                if (!setup.compatible() || setup.ready())
                    throw new IllegalStateException("Expected compatible device without installed dependencies: " + setup.status());
                SmokeUi.click(mc, Text.get("pip.setup.install"));
                stage++;
            }
            case 2 -> {
                mc.gui.screen().onClose();
                stage++;
            }
            case 3 -> {
                if (setup.busy() || setup.ready())
                    throw new IllegalStateException("Canceling consent must not install");
                // Model an offline download cache, using only this build's verified runtime archive.
                staged = CompletableFuture.runAsync(() -> {
                    try (var input = getClass().getResourceAsStream("/assets/darkmusic/pip/windows-x64/dependencies.json")) {
                        var manifest = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        var root = app.services().config().directory().resolve("pip-native");
                        Files.createDirectories(root);
                        Files.copy(Path.of(System.getProperty("darkmusic.pipDependencies")), root.resolve("dependencies-" + manifest.get("sha256").getAsString() + ".zip"));
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
                stage++;
            }
            case 4 -> {
                if (!staged.isDone()) return false;
                staged.join();
                SmokeUi.click(mc, Text.get("pip.setup.check"));
                stage++;
            }
            case 5 -> {
                if (setup.busy()) return false;
                if (!setup.ready() || s.enabled)
                    throw new IllegalStateException("Installation must not grant access: " + setup.status());
                SmokeUi.click(mc, Text.get("pip.setup.enable"));
                stage++;
            }
            case 6 -> {
                mc.gui.screen().onClose();
                stage++;
            }
            case 7 -> {
                if (s.enabled) throw new IllegalStateException("Canceled access was granted");
                SmokeUi.click(mc, Text.get("pip.setup.enable"));
                stage++;
            }
            case 8 -> {
                SmokeUi.click(mc, Text.get("pip.setup.enable"));
                stage++;
            }
            case 9 -> {
                if (!s.enabled || app.services().pip().active() || app.services().pip().source() != null || s.audioAccess)
                    throw new IllegalStateException("Consent must not start capture/audio");
                SmokeUi.click(mc, Text.get("pip.audio_spectrum"));
                stage++;
            }
            case 10 -> {
                mc.gui.screen().onClose();
                stage++;
            }
            case 11 -> {
                if (s.audioAccess) throw new IllegalStateException("Canceled audio access was granted");
                SmokeUi.click(mc, Text.get("pip.setup.disable"));
                stage++;
            }
            case 12 -> {
                if (s.enabled || app.services().pip().active()) throw new IllegalStateException("Disable failed");
                return true;
            }
        }
        return false;
    }
}
