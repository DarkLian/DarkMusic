package com.darkmusic.client.gui;

import net.minecraft.client.KeyMapping;

/**
 * DarkMusic's key bindings as the player screen shows and runs them.
 */
public interface ShortcutKeys {
    KeyMapping open();

    KeyMapping toggle();

    KeyMapping next();

    KeyMapping previous();

    KeyMapping faster();

    KeyMapping slower();

    KeyMapping normalSpeed();

    KeyMapping volumeUp();

    KeyMapping volumeDown();

    KeyMapping mute();

    KeyMapping seekBack();

    KeyMapping seekForward();

    KeyMapping stop();

    KeyMapping jump();

    KeyMapping[] bindings();

    /**
     * Whether Minecraft binds the same key to something else, so DarkMusic should not take it.
     */
    boolean conflicts(KeyMapping key);

    void perform(KeyMapping key);
}
