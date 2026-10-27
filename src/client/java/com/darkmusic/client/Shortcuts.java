package com.darkmusic.client;

import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.text.Text;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.concurrent.TimeUnit;

/**
 * DarkMusic's native Controls bindings: registration, saved keys, conflicts and the playback actions they run.
 */
public final class Shortcuts {
    private final DarkMusicClient app;
    public KeyMapping open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute, seekBack, seekForward, stop, jump;
    private long conflictWarning;

    Shortcuts(DarkMusicClient app) {
        this.app = app;
    }

    /**
     * Fabric accepts key mappings only while the client initializes, before saved settings load.
     */
    void register() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkmusic", "controls"));
        open = binding("open", org.lwjgl.glfw.GLFW.GLFW_KEY_B, category);
        toggle = binding("toggle", org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH, category);
        next = binding("next", org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET, category);
        previous = binding("previous", org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET, category);
        faster = binding("faster", org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, category);
        slower = binding("slower", org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, category);
        seekBack = binding("seek_back", org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, category);
        seekForward = binding("seek_forward", org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, category);
        stop = binding("stop", org.lwjgl.glfw.GLFW.GLFW_KEY_P, category);
        jump = binding("jump", org.lwjgl.glfw.GLFW.GLFW_KEY_O, category);
        normalSpeed = binding("normal_speed", org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0, category);
        volumeUp = binding("volume_up", org.lwjgl.glfw.GLFW.GLFW_KEY_UP, category);
        volumeDown = binding("volume_down", org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, category);
        mute = binding("mute", org.lwjgl.glfw.GLFW.GLFW_KEY_M, category);
    }

    private KeyMapping binding(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkmusic." + name, InputConstants.Type.KEYSYM, key, category));
    }

    public KeyMapping[] bindings() {
        return new KeyMapping[]{open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute, seekBack, seekForward, stop, jump};
    }

    /**
     * Applies keys saved by versions that stored them in DarkMusic's config instead of Minecraft's options.
     */
    void loadSaved() {
        var settings = app.settings;
        for (KeyMapping key : bindings()) {
            String saved = settings.nativeShortcutNames.contains(key.getName()) ? null : settings.shortcuts.get(key.getName());
            if (saved != null) try {
                key.setKey(InputConstants.getKey(saved));
            } catch (IllegalArgumentException e) {
                key.setKey(InputConstants.UNKNOWN);
                app.warn(Text.get("error.shortcut_invalid"));
            }
        }
        migrateSpeedBindings();
        if (settings.nativeShortcutNames.containsAll(java.util.Arrays.stream(bindings()).map(KeyMapping::getName).toList()))
            settings.shortcuts.clear();
        KeyMapping.resetMapping();
    }

    private void migrateSpeedBindings() {
        var settings = app.settings;
        if (settings.nativeShortcutNames.contains(seekBack.getName()) || settings.nativeShortcutNames.contains(seekForward.getName())
            || settings.shortcuts.containsKey(seekBack.getName()) || settings.shortcuts.containsKey(seekForward.getName())
            || !faster.saveString().equals("key.keyboard.right") || !slower.saveString().equals("key.keyboard.left"))
            return;
        faster.setKey(faster.getDefaultKey());
        slower.setKey(slower.getDefaultKey());
        settings.shortcuts.put(faster.getName(), faster.saveString());
        settings.shortcuts.put(slower.getName(), slower.saveString());
        var updated = java.util.Map.of("key_" + faster.getName(), faster.saveString(), "key_" + slower.getName(), slower.saveString());
        app.io.execute(() -> {
            try {
                app.config.resetShortcuts(updated);
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Cannot migrate default speed bindings", failure);
                Minecraft.getInstance().execute(() -> app.warn(Text.get("error.shortcut_migrate")));
            }
        });
    }

    public boolean conflicts(KeyMapping key) {
        if (key.isUnbound()) return false;
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings)
            if (other != key && key.same(other)) {
                if (Minecraft.getInstance().gui.screen() instanceof MusicScreen
                    && !other.getName().startsWith("key.darkmusic.")) continue;
                if (System.nanoTime() - conflictWarning > TimeUnit.SECONDS.toNanos(2)) {
                    conflictWarning = System.nanoTime();
                    app.warn(Text.get("error.shortcut_conflict"));
                }
                return true;
            }
        return false;
    }

    /**
     * Drains this tick's presses; presses while not allowed are discarded rather than replayed later.
     */
    void consume(boolean allowed) {
        while (toggle.consumeClick()) if (allowed) perform(toggle);
        while (next.consumeClick()) if (allowed) perform(next);
        while (previous.consumeClick()) if (allowed) perform(previous);
        while (faster.consumeClick()) if (allowed) perform(faster);
        while (slower.consumeClick()) if (allowed) perform(slower);
        while (normalSpeed.consumeClick()) if (allowed) perform(normalSpeed);
        while (volumeUp.consumeClick()) if (allowed) perform(volumeUp);
        while (volumeDown.consumeClick()) if (allowed) perform(volumeDown);
        while (mute.consumeClick()) if (allowed) perform(mute);
        while (seekBack.consumeClick()) if (allowed) perform(seekBack);
        while (seekForward.consumeClick()) if (allowed) perform(seekForward);
        while (stop.consumeClick()) if (allowed) perform(stop);
        while (jump.consumeClick()) if (allowed) perform(jump);
    }

    public void perform(KeyMapping key) {
        if (app.resetting() || app.mediaBusy() || app.settings == null || app.playback == null || conflicts(key))
            return;
        var playback = app.playback;
        var library = app.library;
        if (key == toggle) {
            if (playback.state().track() == null && !library.snapshot().tracks().isEmpty())
                playback.play(library.snapshot().tracks(), library.snapshot().tracks().getFirst());
            else playback.toggle();
        } else if (key == next) playback.next();
        else if (key == previous) playback.previous();
        else if (key == faster) speed(app.settings.speed + .1);
        else if (key == slower) speed(app.settings.speed - .1);
        else if (key == normalSpeed) speed(1);
        else if (key == volumeUp) volume(app.settings.volume + .05);
        else if (key == volumeDown) volume(app.settings.volume - .05);
        else if (key == mute) app.toggleMute();
        else if (key == seekBack) app.seekRelative(-5);
        else if (key == seekForward) app.seekRelative(5);
        else if (key == stop) playback.stop();
        else if (key == jump) {
            app.open();
            if (Minecraft.getInstance().gui.screen() instanceof MusicScreen screen)
                screen.jumpToPlaying();
        }
    }

    private void speed(double value) {
        app.speed(value);
        app.speedFeedback.show(app.settings.speed);
    }

    private void volume(double value) {
        app.volume(value);
        app.volumeFeedback.show(app.settings.volume);
    }

    /**
     * The options.txt entries that Reset to Defaults writes back.
     */
    java.util.Map<String, String> defaults() {
        var keys = new java.util.LinkedHashMap<String, String>();
        for (KeyMapping key : bindings()) keys.put("key_" + key.getName(), key.getDefaultKey().getName());
        return keys;
    }

    void restoreDefaults() {
        conflictWarning = 0;
        for (KeyMapping key : bindings()) {
            key.setKey(key.getDefaultKey());
            key.setDown(false);
            while (key.consumeClick()) {
            }
        }
        KeyMapping.resetMapping();
    }
}
