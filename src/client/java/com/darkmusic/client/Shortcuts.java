package com.darkmusic.client;

import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.gui.ShortcutKeys;
import com.darkmusic.client.text.Text;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DarkMusic's native Controls bindings: registration, saved keys, conflicts and the playback actions they run.
 */
public final class Shortcuts implements ShortcutKeys {
    private final ClientServices services;
    private final PlaybackCommands commands;
    // Opens the player for the jump key; the screen's sources are wired after the shortcuts.
    private final Runnable openPlayer;
    public KeyMapping open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute, seekBack, seekForward, stop, jump;
    // Unbound by default, so they never take a key another mod or Minecraft already uses.
    public KeyMapping hud, lyrics, favorite, shuffle, repeat;
    private long conflictWarning;

    Shortcuts(ClientServices services, PlaybackCommands commands, Runnable openPlayer) {
        this.services = services;
        this.commands = commands;
        this.openPlayer = openPlayer;
    }

    /**
     * Fabric accepts key mappings only while the client initializes, before saved settings load.
     */
    void register() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkmusic", "controls"));
        open = binding("open", GLFW.GLFW_KEY_B, category);
        toggle = binding("toggle", GLFW.GLFW_KEY_BACKSLASH, category);
        next = binding("next", GLFW.GLFW_KEY_RIGHT_BRACKET, category);
        previous = binding("previous", GLFW.GLFW_KEY_LEFT_BRACKET, category);
        faster = binding("faster", GLFW.GLFW_KEY_EQUAL, category);
        slower = binding("slower", GLFW.GLFW_KEY_MINUS, category);
        seekBack = binding("seek_back", GLFW.GLFW_KEY_LEFT, category);
        seekForward = binding("seek_forward", GLFW.GLFW_KEY_RIGHT, category);
        stop = binding("stop", GLFW.GLFW_KEY_P, category);
        jump = binding("jump", GLFW.GLFW_KEY_O, category);
        normalSpeed = binding("normal_speed", GLFW.GLFW_KEY_KP_0, category);
        volumeUp = binding("volume_up", GLFW.GLFW_KEY_UP, category);
        volumeDown = binding("volume_down", GLFW.GLFW_KEY_DOWN, category);
        mute = binding("mute", GLFW.GLFW_KEY_M, category);
        hud = binding("toggle_hud", GLFW.GLFW_KEY_UNKNOWN, category);
        lyrics = binding("toggle_lyrics", GLFW.GLFW_KEY_UNKNOWN, category);
        favorite = binding("favorite", GLFW.GLFW_KEY_UNKNOWN, category);
        shuffle = binding("shuffle", GLFW.GLFW_KEY_UNKNOWN, category);
        repeat = binding("repeat", GLFW.GLFW_KEY_UNKNOWN, category);
    }

    private KeyMapping binding(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkmusic." + name, InputConstants.Type.KEYSYM, key, category));
    }

    @Override
    public KeyMapping open() {
        return open;
    }

    @Override
    public KeyMapping toggle() {
        return toggle;
    }

    @Override
    public KeyMapping next() {
        return next;
    }

    @Override
    public KeyMapping previous() {
        return previous;
    }

    @Override
    public KeyMapping faster() {
        return faster;
    }

    @Override
    public KeyMapping slower() {
        return slower;
    }

    @Override
    public KeyMapping normalSpeed() {
        return normalSpeed;
    }

    @Override
    public KeyMapping volumeUp() {
        return volumeUp;
    }

    @Override
    public KeyMapping volumeDown() {
        return volumeDown;
    }

    @Override
    public KeyMapping mute() {
        return mute;
    }

    @Override
    public KeyMapping seekBack() {
        return seekBack;
    }

    @Override
    public KeyMapping seekForward() {
        return seekForward;
    }

    @Override
    public KeyMapping stop() {
        return stop;
    }

    @Override
    public KeyMapping jump() {
        return jump;
    }

    @Override
    public KeyMapping[] bindings() {
        return new KeyMapping[]{open, toggle, next, previous, faster, slower, normalSpeed, volumeUp, volumeDown, mute, seekBack, seekForward, stop, jump,
            hud, lyrics, favorite, shuffle, repeat};
    }

    /**
     * Applies keys saved by versions that stored them in DarkMusic's config instead of Minecraft's options.
     */
    void loadSaved() {
        var settings = services.settings();
        for (KeyMapping key : bindings()) {
            String saved = settings.nativeShortcutNames.contains(key.getName()) ? null : settings.shortcuts.get(key.getName());
            if (saved != null) try {
                key.setKey(InputConstants.getKey(saved));
            } catch (IllegalArgumentException e) {
                key.setKey(InputConstants.UNKNOWN);
                services.warn(Text.get("error.shortcut_invalid"));
            }
        }
        migrateSpeedBindings();
        if (settings.nativeShortcutNames.containsAll(Arrays.stream(bindings()).map(KeyMapping::getName).toList()))
            settings.shortcuts.clear();
        KeyMapping.resetMapping();
    }

    private void migrateSpeedBindings() {
        var settings = services.settings();
        if (settings.nativeShortcutNames.contains(seekBack.getName()) || settings.nativeShortcutNames.contains(seekForward.getName())
            || settings.shortcuts.containsKey(seekBack.getName()) || settings.shortcuts.containsKey(seekForward.getName())
            || !faster.saveString().equals("key.keyboard.right") || !slower.saveString().equals("key.keyboard.left"))
            return;
        faster.setKey(faster.getDefaultKey());
        slower.setKey(slower.getDefaultKey());
        settings.shortcuts.put(faster.getName(), faster.saveString());
        settings.shortcuts.put(slower.getName(), slower.saveString());
        var updated = Map.of("key_" + faster.getName(), faster.saveString(), "key_" + slower.getName(), slower.saveString());
        services.io().execute(() -> {
            try {
                services.config().resetShortcuts(updated);
            } catch (Exception failure) {
                LoggerFactory.getLogger("darkmusic").warn("Cannot migrate default speed bindings", failure);
                Minecraft.getInstance().execute(() -> services.warn(Text.get("error.shortcut_migrate")));
            }
        });
    }

    @Override
    public boolean conflicts(KeyMapping key) {
        if (key.isUnbound()) return false;
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings)
            if (other != key && key.same(other)) {
                if (Minecraft.getInstance().gui.screen() instanceof MusicScreen
                    && !other.getName().startsWith("key.darkmusic.")) continue;
                if (System.nanoTime() - conflictWarning > TimeUnit.SECONDS.toNanos(2)) {
                    conflictWarning = System.nanoTime();
                    services.warn(Text.get("error.shortcut_conflict"));
                }
                return true;
            }
        return false;
    }

    /**
     * Drains this tick's presses; presses while not allowed are discarded rather than replayed later.
     */
    void consume(boolean allowed) {
        // The client tick reads the open key itself, before this.
        for (KeyMapping key : bindings())
            if (key != open) while (key.consumeClick()) if (allowed) perform(key);
    }

    @Override
    public void perform(KeyMapping key) {
        if (!services.state.idle() || services.settings() == null || services.playback() == null || conflicts(key))
            return;
        var playback = services.playback();
        if (key == toggle) {
            if (playback.state().track() == null) commands.startPlayback();
            else playback.toggle();
        } else if (key == next) playback.next();
        else if (key == previous) playback.previous();
        else if (key == faster) speed(services.settings().speed + .1);
        else if (key == slower) speed(services.settings().speed - .1);
        else if (key == normalSpeed) speed(1);
        else if (key == volumeUp) volume(services.settings().volume + .05);
        else if (key == volumeDown) volume(services.settings().volume - .05);
        else if (key == mute) commands.toggleMute();
        else if (key == seekBack) commands.seekRelative(-5);
        else if (key == seekForward) commands.seekRelative(5);
        else if (key == stop) playback.stop();
        else if (key == jump) {
            openPlayer.run();
            if (Minecraft.getInstance().gui.screen() instanceof MusicScreen screen)
                screen.jumpToPlaying();
        } else if (key == hud) {
            services.update(s -> s.hudEnabled = !s.hudEnabled);
            notice(services.settings().hudEnabled ? "shortcut.darkmusic.hud_on" : "shortcut.darkmusic.hud_off");
        } else if (key == lyrics) {
            if (!services.settings().lyricsEnabled) notice("shortcut.darkmusic.lyrics_disabled");
            else {
                services.update(s -> s.lyricsHud = !s.lyricsHud);
                notice(services.settings().lyricsHud ? "shortcut.darkmusic.lyrics_on" : "shortcut.darkmusic.lyrics_off");
            }
        } else if (key == favorite) {
            var track = playback.state().track();
            if (track == null) notice("upnext.idle");
            else if (!services.playlistsReady()) notice("text.darkmusic.playlists_are_still_loading");
            else {
                boolean was = services.playlists().favorite(track);
                services.playlists().toggleFavorite(track);
                notice(was ? "shortcut.darkmusic.unfavorited" : "shortcut.darkmusic.favorited", track.title());
            }
        } else if (key == shuffle) {
            commands.shuffle(!playback.shuffle());
            notice(playback.shuffle() ? "text.darkmusic.shuffle_on" : "text.darkmusic.shuffle_off");
        } else if (key == repeat) {
            var values = Repeat.values();
            commands.repeat(values[(playback.repeat().ordinal() + 1) % values.length]);
            notice("command.darkmusic.repeat_now", Text.get("command.darkmusic.repeat_" + playback.repeat().name().toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * A short message above the hotbar, where Minecraft shows its own action-bar text.
     */
    private static void notice(String key, Object... args) {
        Minecraft.getInstance().gui.hud.setOverlayMessage(Text.component(key, args), false);
    }

    private void speed(double value) {
        commands.speed(value);
        services.speedFeedback().show(services.settings().speed);
    }

    private void volume(double value) {
        commands.volume(value);
        services.volumeFeedback().show(services.settings().volume);
    }

    /**
     * The options.txt entries that Reset to Defaults writes back.
     */
    Map<String, String> defaults() {
        var keys = new LinkedHashMap<String, String>();
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
