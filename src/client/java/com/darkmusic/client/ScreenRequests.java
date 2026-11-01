package com.darkmusic.client;

import com.darkmusic.client.command.LocalCommands;
import com.darkmusic.client.gui.lyrics.LyricsScreen;
import com.darkmusic.client.gui.pip.PipScreen;
import com.darkmusic.client.gui.player.MusicScreen;
import com.darkmusic.client.gui.settings.HudEditorScreen;
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
    enum RequestedScreen {PLAYER, HUD, LYRICS, PIP, KEYS}

    private final ClientServices services;
    private ScreenRequest request;

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
        request = request(layout ? RequestedScreen.HUD : RequestedScreen.PLAYER, Minecraft.getInstance().level, System.nanoTime());
    }

    public String requestLyrics() {
        String problem = lyricsCommandProblem();
        if (!problem.isEmpty()) return problem;
        request = request(RequestedScreen.LYRICS, Minecraft.getInstance().level, System.nanoTime());
        return "";
    }

    public void requestPip() {
        request = request(RequestedScreen.PIP, Minecraft.getInstance().level, System.nanoTime());
    }

    /**
     * Minecraft's Key Binds screen, where DarkMusic's shortcuts are changed.
     */
    public void requestKeyBinds() {
        request = request(RequestedScreen.KEYS, Minecraft.getInstance().level, System.nanoTime());
    }

    /**
     * Reset to Defaults drops a request that has not opened yet.
     */
    void clear() {
        request = null;
    }

    /**
     * A button clicked in chat leaves chat open. The requested screen replaces it, and closing returns to the game.
     */
    static ScreenRequest request(RequestedScreen kind, Object world, long now) {
        return new ScreenRequest(kind, world, now + TimeUnit.SECONDS.toNanos(5));
    }

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
        if (request == null) return;
        if (!request.due(System.nanoTime(), client.level, services.state.alive(), client.player == null || client.player.isAlive())) {
            request = null;
            return;
        }
        var screen = chatless(client.gui.screen());
        if (services.settings() == null || services.library() == null || client.gui.overlay() != null || !client.gui.canInterruptScreen()
            || screen != null && !(screen instanceof TitleScreen)) return;
        var requested = request.kind();
        request = null;
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
