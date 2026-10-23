package com.darkmusic.smoke;

import com.darkmusic.client.gui.*;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.*;

/**
 * Test navigation follows the actual compact panels and section buttons, never service shortcuts.
 */
final class SmokeUi {
    static void captureReady(Minecraft mc) {
        displayReady(mc);
        requireSmoothFont(mc);
    }

    static void displayReady(Minecraft mc) {
        if (!mc.getWindow().isFullscreen() || mc.options.guiScale().get() != 2 || mc.getWindow().getGuiScale() != 2
            || mc.getWindow().getWidth() != 1920 || mc.getWindow().getHeight() != 1080)
            throw new IllegalStateException("Captures require fullscreen 1920x1080 at GUI scale 2");
        if (Boolean.getBoolean("darkmusic.visualSmoke") || Boolean.getBoolean("darkmusic.smoothFontAvailable"))
            requireSmoothFont(mc);
    }

    private static void requireSmoothFont(Minecraft mc) {
        var font = mc.getResourceManager().getResource(net.minecraft.resources.Identifier.withDefaultNamespace("textures/font/ascii.png")).orElseThrow();
        if (!font.sourcePackId().equals("file/SmoothFont_1.21.zip"))
            throw new IllegalStateException("SmoothFont is not applied");
    }

    static Object field(Object owner, String name) {
        try {
            var f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static AbstractWidget find(Minecraft mc, String label) {
        return mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals(label))
            .map(c -> (AbstractWidget) c).findFirst().orElse(null);
    }

    static void press(Minecraft mc, AbstractWidget widget) {
        if (widget == null || !widget.visible || !widget.active)
            throw new IllegalStateException("Unreachable control: " + (widget == null ? "missing" : widget.getMessage().getString()));
        mc.gui.screen().mouseClicked(new MouseButtonEvent(widget.getX() + 3, widget.getY() + 3, new MouseButtonInfo(0, 0)), false);
    }

    static void library(Minecraft mc) {
        if (mc.gui.screen() instanceof MusicScreen s && !field(s, "pane").toString().equals("LIBRARY"))
            s.keyPressed(new KeyEvent(256, 0, 0));
    }

    static void preview(Minecraft mc) {
        if (mc.gui.screen() instanceof SettingsScreen s && !(boolean) field(s, "previewVisible"))
            press(mc, find(mc, Text.get("phase2.preview")));
    }

    static void click(Minecraft mc, String label) {
        if (label.equals("Queue / Entries") && mc.gui.screen() instanceof DownloadScreen d)
            label = Text.get((boolean) field(d, "queueView") ? "phase2.entries" : "ui.darkmusic.queue");
        if (mc.gui.screen() instanceof MusicScreen && label.equals(Text.get("text.darkmusic.manage_playlists")) && find(mc, label) == null) {
            library(mc);
            for (int i = 0; i < 4 && find(mc, label) == null; i++) {
                String mode = field(mc.gui.screen(), "mode").toString();
                var cycle = find(mc, Text.get("format.darkmusic.view", Text.get("view.darkmusic." + mode.toLowerCase(java.util.Locale.ROOT))));
                press(mc, cycle == null ? find(mc, Text.get("view.darkmusic.playlists")) : cycle);
            }
        }
        if (mc.gui.screen() instanceof SettingsScreen && label.equals(Text.get("text.darkmusic.edit_hud_layout")))
            preview(mc);
        var widget = find(mc, label);
        if (mc.gui.screen() instanceof SettingsScreen s && (widget == null || !widget.visible)) {
            if ((boolean) field(s, "previewOnly")) press(mc, find(mc, Text.get("phase2.settings_form")));
            outer:
            for (int section = 0; section < 6; section++) {
                for (int step = 0; step < 6 && (int) field(s, "selectedSection") != section; step++) {
                    int current = (int) field(s, "selectedSection");
                    var next = find(mc, Text.get("phase2.section." + section));
                    press(mc, next == null ? find(mc, Text.get("phase2.section." + current)) : next);
                }
                for (int scroll = 0; scroll < 80; scroll++) {
                    widget = find(mc, label);
                    if (widget != null && widget.visible) break outer;
                    s.mouseScrolled((int) field(s, "formX") + 10, s.height - 20, 0, scroll == 0 ? 100 : -1);
                }
            }
        }
        widget = find(mc, label);
        if (mc.gui.screen() instanceof MusicScreen && (widget == null || !widget.visible)) {
            library(mc);
            widget = find(mc, label);
            if (widget == null || !widget.visible) press(mc, find(mc, Text.get("phase2.controls")));
            widget = find(mc, label);
        }
        press(mc, widget);
    }
}
