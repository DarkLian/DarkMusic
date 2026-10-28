package com.darkmusic.client.command;

import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;

/**
 * DarkMusic's menu colors for chat. Chat always draws on a dark backdrop, so only the accent follows the menu
 * settings, lightened until it stays readable there; text tones keep the default dark-theme values.
 */
public record ChatPalette(int accent, int text, int secondary, int muted, int success, int warning, int error) {
    public static final int DEFAULT_ACCENT = 0xB8A8FA;
    private static final int CHAT_BACKDROP = 0xFF101118;

    public static ChatPalette of(int menuAccent) {
        int accent = UiTheme.readableAccent(menuAccent | 0xFF000000, CHAT_BACKDROP, 0xFFFFFFFF) & 0xFFFFFF;
        return new ChatPalette(accent, 0xF0EFF9, 0xB0B5CE, 0x868DA7, 0x66DDB8, 0xEBC37C, 0xEF8798);
    }

    public MutableComponent plain(String text, int color) {
        return Component.literal(text).withColor(color);
    }

    public MutableComponent key(String key, int color, Object... args) {
        return Text.component(key, args).withColor(color);
    }

    /**
     * "━━━ ♪ DarkMusic v3.3.5 ━━━" with an optional subtitle in place of the version.
     */
    public MutableComponent title(String version, Component subtitle) {
        var line = plain("━━━ ", muted).append(plain("♪ DarkMusic", accent).withStyle(style -> style.withBold(true)));
        if (subtitle != null) line.append(plain(" · ", muted)).append(subtitle.copy().withColor(text));
        else if (version != null && !version.isEmpty()) line.append(plain(" v" + version, muted));
        return line.append(plain(" ━━━", muted));
    }

    public MutableComponent section(String key, Component hint) {
        var line = key(key, accent);
        return hint == null ? line : line.append(plain("  ", muted)).append(hint.copy().withColor(muted));
    }

    /**
     * A bracketed button that runs a command when clicked.
     */
    public MutableComponent button(String key, String command, int color) {
        return plain("[", muted).append(key(key, color)).append(plain("]", muted))
            .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command).withColor(secondary))));
    }

    public MutableComponent link(String key, String url) {
        return plain("[", muted).append(key(key, accent)).append(plain("]", muted))
            .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(url).withColor(secondary))));
    }

    public MutableComponent info(String key, Object... args) {
        return key(key, secondary, args);
    }

    public MutableComponent error(String key, Object... args) {
        return key(key, error, args);
    }

    /**
     * A message with one highlighted value, such as "Volume: 40%".
     */
    public MutableComponent value(String key, String value) {
        return key(key, secondary, plain(value, accent));
    }
}
