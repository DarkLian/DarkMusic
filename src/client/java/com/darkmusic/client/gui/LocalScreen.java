package com.darkmusic.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Prevents delayed local UI work from restoring a screen after death or a world transition.
 */
public abstract class LocalScreen extends Screen {
    private final Screen returnTo;
    private final Object openedIn;

    protected LocalScreen(Component title, Screen parent) {
        super(title);
        returnTo = parent;
        openedIn = minecraft.level;
    }

    protected final boolean isCurrent() {
        return minecraft.gui.screen() == this && minecraft.level == openedIn && (minecraft.player == null || minecraft.player.isAlive());
    }

    protected final void show(Screen next) {
        if (isCurrent()) minecraft.gui.setScreen(next);
    }

    @Override
    public void removed() {
        var app = com.darkmusic.client.DarkMusicClient.INSTANCE;
        if (app != null) app.save();
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    @Override
    public final void onClose() {
        if (isCurrent()) minecraft.gui.setScreen(returnTo);
    }
}
