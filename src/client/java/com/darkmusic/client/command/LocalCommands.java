package com.darkmusic.client.command;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.text.Text;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;

/**
 * Fabric consumes these commands locally. Feedback never sends chat or command packets.
 */
public final class LocalCommands {
    private LocalCommands() {
    }

    public static void register(DarkMusicClient app) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> register(dispatcher,
            () -> app.requestScreen(false), () -> app.requestScreen(true), () -> app.updates.requestUpdate(), app::requestLyrics, app::requestPip));
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable player, Runnable layout) {
        register(dispatcher, player, layout, () -> {
        });
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable player, Runnable layout, Runnable update) {
        register(dispatcher, player, layout, update, () -> "command.darkmusic.unavailable");
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable player, Runnable layout, Runnable update,
                                java.util.function.Supplier<String> lyrics) {
        register(dispatcher, player, layout, update, lyrics, () -> {
        });
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable player, Runnable layout, Runnable update,
                                java.util.function.Supplier<String> lyrics, Runnable pip) {
        for (String name : new String[]{"da", "darkmusic"})
            dispatcher.register(ClientCommands.literal(name).requires(FabricClientCommandSource::attended)
                .executes(context -> {
                    player.run();
                    return 1;
                })
                .then(ClientCommands.literal("gui").executes(context -> {
                    layout.run();
                    return 1;
                }))
                .then(ClientCommands.literal("pip").executes(context -> {
                    pip.run();
                    return 1;
                }))
                .then(ClientCommands.literal("lyrics").executes(context -> {
                    String problem = lyrics.get();
                    if (problem != null && !problem.isEmpty()) {
                        context.getSource().sendFeedback(Text.component(problem).withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("help").executes(context -> {
                    help(context.getSource());
                    return 1;
                }))
                .then(ClientCommands.literal("update").executes(context -> {
                    update.run();
                    return 1;
                })));
    }

    public static String lyricsProblem(boolean ready, boolean enabled, AudioBackend.State state) {
        if (!ready) return "command.darkmusic.unavailable";
        if (!enabled) return "lyrics.darkmusic.disabled";
        if (state == null || state.track() == null || state.status() == AudioBackend.Status.STOPPED
            || state.status() == AudioBackend.Status.ERROR) return "upnext.idle";
        return "";
    }

    private static void help(FabricClientCommandSource source) {
        source.sendFeedback(Text.component("command.darkmusic.help").withStyle(ChatFormatting.LIGHT_PURPLE));
        source.sendFeedback(Text.component("command.darkmusic.section.commands").withStyle(ChatFormatting.GOLD));
        source.sendFeedback(command("/da", "command.darkmusic.player"));
        source.sendFeedback(command("/da gui", "command.darkmusic.layout"));
        source.sendFeedback(command("/da lyrics", "command.darkmusic.lyrics"));
        source.sendFeedback(command("/da pip", "pip.title"));
        source.sendFeedback(command("/da update", "command.darkmusic.update"));
        source.sendFeedback(command("/da help", "command.darkmusic.show_help"));
        source.sendFeedback(Text.component("command.darkmusic.section.shortcuts").withStyle(ChatFormatting.GOLD));
        source.sendFeedback(Text.component("command.darkmusic.tips").withStyle(ChatFormatting.GRAY));
        source.sendFeedback(Text.component("command.darkmusic.section.links").withStyle(ChatFormatting.GOLD));
        source.sendFeedback(link("command.darkmusic.guide", "https://github.com/DarkLian/DarkMusic/blob/main/docs/USER_GUIDE.md")
            .append(Component.literal(" | ")).append(link("command.darkmusic.downloads", "https://github.com/DarkLian/DarkMusic/releases/latest"))
            .append(Component.literal(" | ")).append(link("command.darkmusic.issues", "https://github.com/DarkLian/DarkMusic/issues")));
    }

    private static Component command(String command, String description) {
        return Component.literal(command).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Text.component("command.darkmusic.insert"))))
            .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Text.component(description).withStyle(ChatFormatting.GRAY));
    }

    private static net.minecraft.network.chat.MutableComponent link(String key, String url) {
        return Text.component(key).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
    }
}
