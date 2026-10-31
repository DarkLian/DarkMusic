package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import java.nio.file.*;

/**
 * Offline GUI checks: synthetic spectrum previews only, no capture or network access.
 */
final class PipSpectrumSmoke {
    private int tick;
    private String musicBefore;
    private boolean hudWasEnabled;
    private double editorY;

    boolean tick(Minecraft mc, DarkMusicClient app) throws Exception {
        tick++;
        if (tick == 1) {
            SmokeUi.captureReady(mc);
            HudSettingsSmoke.check(mc, app);
            musicBefore = signature(app);
            app.services().settings().pip.audioSpectrum = true;
            var s = app.services().settings().pip.spectrum;
            for (int[] size : new int[][]{{320, 240}, {426, 240}, {960, 540}})
                for (var position : Settings.ArtworkSpectrumPosition.values()) {
                    s.position = position;
                    for (var style : Settings.SpectrumStyle.values()) {
                        s.style = style;
                        var screen = new PipSpectrumScreen(app.sources(), null);
                        screen.init(size[0], size[1]);
                        check(screen);
                        for (int i = 0; i < 24; i++) {
                            screen.keyPressed(new KeyEvent(258, 0, 0));
                            check(screen);
                        }
                    }
                }
            s.style = Settings.SpectrumStyle.ROUNDED_BARS;
            s.position = Settings.ArtworkSpectrumPosition.BELOW;
            mc.gui.setScreen(new PipSpectrumScreen(app.sources(), null));
        }
        if (tick == 10) capture(mc, "settings");
        if (tick == 12) {
            editorY = app.services().settings().pip.y;
            app.services().settings().pip.y = 0;
            var s = app.services().settings().pip.spectrum;
            s.style = Settings.SpectrumStyle.FILLED_WAVE;
            s.position = Settings.ArtworkSpectrumPosition.RIGHT;
            mc.gui.setScreen(new PipEditorScreen(app.sources(), new PipSpectrumScreen(app.sources(), null), false));
        }
        if (tick == 22) {
            var back = SmokeUi.find(mc, Text.get("phase2.back"));
            if (back == null || back.getY() != mc.gui.screen().height - 24)
                throw new IllegalStateException("PiP at the top must move the Back button away from the captured image");
            capture(mc, "layout");
        }
        if (tick == 24) {
            app.services().settings().pip.y = editorY;
            mc.gui.setScreen(new PipSpectrumScreen(app.sources(), null));
            for (int i = 0; i < 30; i++) {
                var colors = SmokeUi.find(mc, Text.get("colors.darkmusic.spectrum"));
                if (colors != null && colors.visible) {
                    SmokeUi.press(mc, colors);
                    break;
                }
                mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0, 300, 0, -1);
            }
            if (!(mc.gui.screen() instanceof ColorsScreen) || SmokeUi.field(mc.gui.screen(), ColorsScreen.class, "target") != app.services().settings().pip.spectrum.colors)
                throw new IllegalStateException("PiP colors did not open its own editor");
        }
        if (tick == 34) capture(mc, "colors");
        if (tick == 36) {
            hudWasEnabled = app.services().settings().hudEnabled;
            app.services().settings().hudEnabled = false;
            var screen = new SettingsScreen(app.sources(), null);
            mc.gui.setScreen(screen);
            SmokeUi.set(screen, SettingsScreen.class, "selectedSection", 4);
            screen.init(960, 540);
        }
        if (tick == 46) capture(mc, "hud-disabled");
        if (tick == 50) {
            app.services().settings().hudEnabled = hudWasEnabled;
            if (!musicBefore.equals(signature(app)))
                throw new IllegalStateException("PiP edited music spectrum preferences");
            app.services().save();
            System.out.println("PIP_SPECTRUM_UI_PASS:320x240,426x240,960x540;all_styles;four_positions;keyboard;independent_colors");
            return true;
        }
        return false;
    }

    private static String signature(DarkMusicClient app) {
        var s = app.services().settings();
        return s.spectrumStyle + ":" + s.spectrumColors.preset + ":" + s.spectrumColors.start + ":" + s.spectrumColors.end + ":" + s.spectrumOpacity + ":" + s.visualizerSensitivity + ":" + s.visualizerSmoothing + ":" + s.visualizerBars;
    }

    private static void check(Screen screen) {
        for (var child : screen.children())
            if (child instanceof AbstractWidget w && w.visible) {
                if (w.getX() < 0 || w.getY() < 0 || w.getX() + w.getWidth() > screen.width || w.getY() + w.getHeight() > screen.height)
                    throw new IllegalStateException("PiP widget out of bounds: " + w.getMessage().getString());
                if (w.getMessage().getString().contains("pip.spectrum."))
                    throw new IllegalStateException("Untranslated PiP label");
            }
    }

    private static void capture(Minecraft mc, String name) throws Exception {
        SmokeUi.captureReady(mc);
        var root = Path.of("../../docs/images_private/pip-spectrum").toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("screenshots"));
        Screenshot.grab(root.toFile(), name + "-" + mc.options.languageCode + ".png", mc.gameRenderer.mainRenderTarget(), 1, text -> {
        });
    }
}
