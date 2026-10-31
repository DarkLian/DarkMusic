package com.darkmusic.smoke;

import com.darkmusic.client.gui.*;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.Locale;

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
        var font = mc.getResourceManager().getResource(Identifier.withDefaultNamespace("textures/font/ascii.png")).orElseThrow();
        if (!font.sourcePackId().equals("file/SmoothFont_1.21.zip"))
            throw new IllegalStateException("SmoothFont is not applied");
    }

    /**
     * Reads a private field that type declares. Name the declaring class, or for one smoke cannot name, its binary
     * name ("com.darkmusic.client.render.HorizontalText$Line"); SmokeReflectionTest checks each pair. A static field
     * takes a null owner.
     */
    static Object field(Object owner, Class<?> type, String name) {
        try {
            return declared(owner, type, name).get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object field(Object owner, String type, String name) {
        return field(owner, load(type), name);
    }

    /**
     * Writes a private field that type declares.
     */
    static void set(Object owner, Class<?> type, String name, Object value) {
        try {
            declared(owner, type, name).set(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Object owner, String type, String name, Object value) {
        set(owner, load(type), name, value);
    }

    private static Class<?> load(String type) {
        try {
            return Class.forName(type);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No class " + type, e);
        }
    }

    private static Field declared(Object owner, Class<?> type, String name) {
        if (owner != null && !type.isInstance(owner))
            throw new IllegalStateException(owner.getClass().getName() + " is not a " + type.getName() + ", which declares " + name);
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("No field " + name + " in " + type.getName(), e);
        }
    }

    /**
     * The screen's widget of this type with this label, found as a player finds it.
     */
    static <T extends AbstractWidget> T widget(Screen screen, Class<T> type, String label) {
        var found = screen.children().stream().filter(type::isInstance).map(type::cast)
            .filter(w -> w.getMessage().getString().equals(label)).toList();
        if (found.size() != 1)
            throw new IllegalStateException(found.size() + " " + type.getSimpleName() + " labelled " + label + "; labels: "
                + screen.children().stream().filter(type::isInstance).map(c -> type.cast(c).getMessage().getString()).toList());
        return found.getFirst();
    }

    /**
     * The player's seek bar: the slider that shows the position and length.
     */
    static ValueSlider seekBar(Screen screen) {
        var found = screen.children().stream().filter(c -> c instanceof ValueSlider s && s.getMessage().getString().matches("\\d+:\\d{2} / \\d+:\\d{2}"))
            .map(c -> (ValueSlider) c).toList();
        if (found.size() != 1) throw new IllegalStateException(found.size() + " seek bars");
        return found.getFirst();
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
        if (mc.gui.screen() instanceof MusicScreen s && !s.showingLibrary())
            s.keyPressed(new KeyEvent(256, 0, 0));
    }

    static void preview(Minecraft mc) {
        if (mc.gui.screen() instanceof SettingsScreen s && !(boolean) field(s, SettingsScreen.class, "previewVisible"))
            press(mc, find(mc, Text.get("phase2.preview")));
    }

    static void click(Minecraft mc, String label) {
        if (label.equals("Queue / Entries") && mc.gui.screen() instanceof DownloadScreen d)
            label = Text.get((boolean) d.view().queue() ? "phase2.entries" : "ui.darkmusic.queue");
        if (mc.gui.screen() instanceof MusicScreen && label.equals(Text.get("text.darkmusic.manage_playlists")) && find(mc, label) == null) {
            library(mc);
            for (int i = 0; i < 4 && find(mc, label) == null; i++) {
                String mode = ((MusicScreen) mc.gui.screen()).browser().mode().toString();
                var cycle = find(mc, Text.get("format.darkmusic.view", Text.get("view.darkmusic." + mode.toLowerCase(Locale.ROOT))));
                press(mc, cycle == null ? find(mc, Text.get("view.darkmusic.playlists")) : cycle);
            }
        }
        if (mc.gui.screen() instanceof SettingsScreen && label.equals(Text.get("text.darkmusic.edit_hud_layout")))
            preview(mc);
        var widget = find(mc, label);
        if (mc.gui.screen() instanceof SettingsScreen s && (widget == null || !widget.visible)) {
            if ((boolean) field(s, SettingsScreen.class, "previewOnly"))
                press(mc, find(mc, Text.get("phase2.settings_form")));
            var sectionKeys = (String[]) field(s, SettingsScreen.class, "SECTION_KEYS");
            outer:
            for (int section = 0; section < sectionKeys.length; section++) {
                for (int step = 0; step < sectionKeys.length && (int) field(s, SettingsScreen.class, "selectedSection") != section; step++) {
                    int current = (int) field(s, SettingsScreen.class, "selectedSection");
                    var next = find(mc, Text.get(sectionKeys[section]));
                    press(mc, next == null ? find(mc, Text.get(sectionKeys[current])) : next);
                }
                for (int scroll = 0; scroll < 80; scroll++) {
                    widget = find(mc, label);
                    if (widget != null && widget.visible) break outer;
                    s.mouseScrolled((int) field(s, SettingsScreen.class, "formX") + 10, s.height - 20, 0, scroll == 0 ? 100 : -1);
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
        if (widget == null)
            throw new IllegalStateException("Missing control \"" + label + "\" on " + (mc.gui.screen() == null ? "no screen" : mc.gui.screen().getClass().getSimpleName()));
        press(mc, widget);
    }
}
