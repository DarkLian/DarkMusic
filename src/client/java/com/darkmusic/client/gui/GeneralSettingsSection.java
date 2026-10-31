package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.systemmedia.MediaControlsPackage;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The General section of Settings: the music folder, startup, updates, Discord, Media Controls, backup and history.
 */
final class GeneralSettingsSection {
    /**
     * Where the music folder's path is drawn, its lines, and the Media Controls state the rows were built for.
     */
    record Built(int folderY, String folderPath, List<FormattedCharSequence> folderLines, String media) {
    }

    private GeneralSettingsSection() {
    }

    static Built build(SettingsLayout layout, SettingsScreen.Sources app, Settings s, Font font) {
        var screen = layout.screen();
        layout.section("text.darkmusic.general_settings");
        layout.action("folders.title", () -> screen.show(app.navigation().musicFolders(screen)), "folders.scope");
        int folderY = layout.cursor;
        String folderPath = app.library().root().toString();
        var folderLines = font.split(Component.literal(folderPath), layout.formWidth - 14);
        layout.cursor += folderLines.size() * 10 + 4;
        layout.toggle("text.darkmusic.autoplay_on_launch", () -> s.autoplay, v -> s.autoplay = v, "text.darkmusic.automatically_start_the_library_after_minecraft_launches_disabled_by_default");
        layout.toggle("resume.darkmusic.enabled", () -> s.resumeLast, v -> s.resumeLast = v, "resume.darkmusic.enabled_help");
        layout.toggle("ui.darkmusic.show_hover_info", () -> s.showHoverInfo, v -> s.showHoverInfo = v, "ui.darkmusic.show_hover_info_help");
        layout.toggle("update.enabled", () -> s.updateNotifications, v -> s.updateNotifications = v, "update.enabled_help");
        layout.action("update.check", () -> screen.show(app.navigation().updates(screen)), "update.check_help");
        layout.put(new SettingRow(12, 0, layout.formWidth, Text.get("discord.darkmusic.status"), () -> s.discordStatus, () -> {
            s.discordStatus = !s.discordStatus;
            app.save();
            layout.rebuild();
        }), Text.get("discord.darkmusic.status_help") + (s.discordStatus ? " " + Text.get(app.discord().state()) : ""));
        if (s.discordStatus) {
            layout.toggle("discord.darkmusic.show_title", () -> s.discordShowTitle, v -> {
                s.discordShowTitle = v;
                layout.rebuild();
            }, "discord.darkmusic.show_title_help");
            if (s.discordShowTitle)
                layout.toggle("discord.darkmusic.artwork", () -> s.discordArtwork, v -> s.discordArtwork = v, "discord.darkmusic.artwork_help");
        }
        String media = mediaControlsRows(layout, app, s);
        layout.action("backup.title", () -> screen.show(app.navigation().backup(screen)), "backup.scope");
        layout.action("history.clear", () -> screen.show(new ConfirmationScreen(screen, Text.component("history.clear"), Text.component("history.clear_help"), Text.component("history.clear"), () -> {
            app.listeningHistory().replace(Map.of());
            app.listeningHistory().flush(true);
            return CompletableFuture.completedFuture(null);
        })), "history.clear_help").destructive();
        return new Built(folderY, folderPath, folderLines, media);
    }

    /**
     * What the Media Controls row shows: installation progress and the connection. The screen rebuilds when it changes.
     */
    static String mediaSignature(SettingsScreen.Sources app) {
        var media = app.mediaPackage();
        return media.state() + ":" + (media.size() > 0 ? media.downloaded() * 20 / media.size() : 0) + ":" + app.mediaControls().connected();
    }

    /**
     * Windows x64 only: Install while missing, progress while installing, then an on/off switch with its status.
     * Returns the Media Controls state these rows show.
     */
    private static String mediaControlsRows(SettingsLayout layout, SettingsScreen.Sources app, Settings s) {
        var screen = layout.screen();
        var media = app.mediaPackage();
        String shown = mediaSignature(app);
        if (!media.available()) return shown;
        long megabytes = Math.max(1, Math.round(media.size() / 1_048_576.0));
        switch (media.state()) {
            case READY -> {
                layout.put(new SettingRow(12, 0, layout.formWidth, Text.get("media.darkmusic.enabled"), () -> s.mediaControls, () -> {
                    s.mediaControls = !s.mediaControls;
                    app.save();
                    layout.rebuild();
                }), Text.get("media.darkmusic.enabled_help") + (s.mediaControls
                    ? " " + Text.get(app.mediaControls().connected() ? "media.darkmusic.state.connected" : "media.darkmusic.state.starting") : ""));
                layout.action("media.darkmusic.remove", () -> screen.show(new ConfirmationScreen(screen, Text.component("media.darkmusic.remove"),
                    Text.component("media.darkmusic.remove_body"), Text.component("media.darkmusic.remove"), () -> {
                    // Stop the helper first so its files can be deleted.
                    s.mediaControls = false;
                    app.save();
                    app.mediaControls().update(null, null, System.nanoTime());
                    return media.remove();
                })), "media.darkmusic.remove_help").destructive();
            }
            case INSTALLING -> {
                var row = layout.action("media.darkmusic.install", () -> {
                }, "media.darkmusic.install_help");
                row.active = false;
                row.setMessage(Component.literal(Text.get("media.darkmusic.installing",
                    media.size() > 0 ? Math.round(media.downloaded() * 100.0 / media.size()) : 0)));
            }
            default -> {
                var row = layout.action("media.darkmusic.install", () -> screen.show(new ConfirmationScreen(screen, Text.component("media.darkmusic.install"),
                    Text.component("media.darkmusic.install_body", megabytes), Text.component("media.darkmusic.install_confirm"),
                    () -> media.install().thenRun(() -> Minecraft.getInstance().execute(() -> {
                        if (media.state() == MediaControlsPackage.State.READY) {
                            s.mediaControls = true;
                            app.save();
                        }
                    })))), "media.darkmusic.install_help");
                if (media.state() == MediaControlsPackage.State.FAILED)
                    Help.tip(row, Text.get("media.darkmusic.install_help") + " " + Text.get(media.failure()));
            }
        }
        return shown;
    }
}
