package com.darkmusic.client;

import com.darkmusic.client.command.LocalCommands;
import com.darkmusic.client.gui.HudEditorScreen;
import com.darkmusic.client.gui.LyricsScreen;
import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.gui.PipScreen;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

import java.util.concurrent.TimeUnit;

/**
 * Opening the player, and the screens chat commands ask for. A command runs while chat is still open, so its screen
 * is requested here and opened by a later client tick, in the same world, within five seconds. Client thread.
 */
public final class ScreenRequests {
    private enum RequestedScreen {PLAYER, HUD, LYRICS, PIP, KEYS}

    private final ClientServices services;
    private RequestedScreen requestedScreen;
    private Object requestedWorld;
    private long requestUntil;

    ScreenRequests(ClientServices services) {
        this.services = services;
    }

    <S extends MusicScreen.Sources> void open(S sources) {
        var client = Minecraft.getInstance();
        var screen = chatless(client.gui.screen());
        if (services.state.alive() && services.settings() != null && services.library() != null && client.gui.overlay() == null
            && client.gui.canInterruptScreen() && (screen == null || screen instanceof TitleScreen)
            && (client.player == null || client.player.isAlive()))
            client.gui.setScreen(new MusicScreen(sources, screen));
    }

    public void requestScreen(boolean layout) {
        requestedScreen = layout ? RequestedScreen.HUD : RequestedScreen.PLAYER;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    public String requestLyrics() {
        String problem = lyricsCommandProblem();
        if (!problem.isEmpty()) return problem;
        requestedScreen = RequestedScreen.LYRICS;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        return "";
    }

    public void requestPip() {
        requestedScreen = RequestedScreen.PIP;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    /**
     * Minecraft's Key Binds screen, where DarkMusic's shortcuts are changed.
     */
    public void requestKeyBinds() {
        requestedScreen = RequestedScreen.KEYS;
        requestedWorld = Minecraft.getInstance().level;
        requestUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    /**
     * Reset to Defaults drops a request that has not opened yet.
     */
    void clear() {
        requestedScreen = null;
        requestedWorld = null;
    }

    /**
     * A button clicked in chat leaves chat open. The requested screen replaces it, and closing returns to the game.
     */
    private static Screen chatless(Screen screen) {
        return screen instanceof ChatScreen ? null : screen;
    }

    private String lyricsCommandProblem() {
        var settings = services.settings();
        var playback = services.playback();
        return LocalCommands.lyricsProblem(
            services.state.idle() && settings != null && services.library() != null && playback != null,
            settings != null && settings.lyricsEnabled, playback == null ? null : playback.state());
    }

    <S extends MusicScreen.Sources & LyricsScreen.Sources & PipScreen.Sources & HudEditorScreen.Sources>
    void openRequested(Minecraft client, S sources) {
        if (requestedScreen == null) return;
        if (!services.state.alive() || client.level != requestedWorld || System.nanoTime() > requestUntil
            || client.player != null && !client.player.isAlive()) {
            requestedScreen = null;
            return;
        }
        var screen = chatless(client.gui.screen());
        if (services.settings() == null || services.library() == null || client.gui.overlay() != null || !client.gui.canInterruptScreen()
            || screen != null && !(screen instanceof TitleScreen)) return;
        var requested = requestedScreen;
        requestedScreen = null;
        if (requested == RequestedScreen.LYRICS) {
            String problem = lyricsCommandProblem();
            if (!problem.isEmpty()) services.warn(Text.get(problem));
            else client.gui.setScreen(new LyricsScreen(sources, screen));
        } else if (requested == RequestedScreen.PIP)
            client.gui.setScreen(new PipScreen(sources, screen));
        else if (requested == RequestedScreen.KEYS)
            client.gui.setScreen(new KeyBindsScreen(screen, client.options));
        else if (requested == RequestedScreen.HUD)
            client.gui.setScreen(new HudEditorScreen(sources, screen));
        else open(sources);
    }
}
