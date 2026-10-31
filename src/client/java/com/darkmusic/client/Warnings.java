package com.darkmusic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * The warning line the player shows for ten seconds, logged and read aloud by the narrator when it changes. Any thread
 * may warn; the client tick expires it.
 */
public final class Warnings {
    private final ClientState state;
    private volatile String warning = "";
    private volatile long warningUntil;

    Warnings(ClientState state) {
        this.state = state;
    }

    public String current() {
        return warning;
    }

    public void warn(String text) {
        if (!text.equals(warning)) announce(text);
        warning = text;
        warningUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        LoggerFactory.getLogger("darkmusic").warn(text);
    }

    public void announce(String text) {
        var client = Minecraft.getInstance();
        if (client != null) client.execute(() -> {
            if (!state.closed && client.getNarrator().isActive())
                client.getNarrator().saySystemQueued(Component.literal(text));
        });
    }

    void tick() {
        if (!warning.isEmpty() && System.nanoTime() >= warningUntil) warning = "";
    }

    /**
     * Removes the current warning at once, as Reset to Defaults does.
     */
    public void clear() {
        warning = "";
        warningUntil = 0;
    }
}
