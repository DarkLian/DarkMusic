package com.darkmusic.client.command;

import com.darkmusic.client.DarkMusicClient;
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
            () -> app.requestScreen(false), () -> app.requestScreen(true), () -> app.updates.requestUpdate()));
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable player, Runnable layout) {
        register(dispatcher, player, layout, () -> {
        });
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable player, Runnable layout, Runnable update) {
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
                .then(ClientCommands.literal("help").executes(context -> {
                    help(context.getSource());
                    return 1;
                }))
                .then(ClientCommands.literal("update").executes(context -> {
                    update.run();
                    return 1;
                })));
    }

    private static void help(FabricClientCommandSource source) {
        source.sendFeedback(Text.component("command.darkmusic.help").withStyle(ChatFormatting.LIGHT_PURPLE));
        source.sendFeedback(Text.component("command.darkmusic.tips"));
        source.sendFeedback(link("command.darkmusic.guide", "https://github.com/DarkLian/DarkMusic/blob/main/docs/USER_GUIDE.md")
            .append(Component.literal(" | ")).append(link("command.darkmusic.downloads", "https://github.com/DarkLian/DarkMusic/releases/latest"))
            .append(Component.literal(" | ")).append(link("command.darkmusic.issues", "https://github.com/DarkLian/DarkMusic/issues")));
    }

    private static net.minecraft.network.chat.MutableComponent link(String key, String url) {
        return Text.component(key).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
    }
}
