package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.download.DownloadModels.Tools;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DownloadToolsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private EditBox yt, ffmpeg, probe, javascript;
    private String[] values;

    public DownloadToolsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.download_tools"), parent);
        this.app = app;
        values = new String[]{app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath};
    }

    private EditBox input(String value, String label, int y) {
        var field = new EditBox(font, 12, y, width - 24, 20, Component.literal(label));
        field.setMaxLength(4096);
        field.setValue(value);
        field.setHint(Text.component("text.darkmusic.auto_instance_tools_directory_then_path"));
        return addRenderableWidget(Help.tip(field, Text.get("text.darkmusic.paste_the_executable_file_s_full_path_leave_blank_for_automatic_lookup_no_tools_ar")));
    }

    @Override
    protected void init() {
        yt = input(values[0], Text.get("text.darkmusic.yt_dlp_executable"), 36);
        ffmpeg = input(values[1], Text.get("text.darkmusic.ffmpeg_executable"), 76);
        probe = input(values[2], Text.get("text.darkmusic.ffprobe_executable"), 116);
        javascript = input(values[3], Text.get("text.darkmusic.deno_node_executable_optional"), 156);
        yt.setResponder(s -> values[0] = s);
        ffmpeg.setResponder(s -> values[1] = s);
        probe.setResponder(s -> values[2] = s);
        javascript.setResponder(s -> values[3] = s);
        javascript.setHint(Text.component("text.darkmusic.auto_deno_or_node_on_path_youtube_may_require_this"));
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.check_tools"), b -> {
            persist();
            app.downloads.checkTools();
        }).bounds(12, height - 28, (width - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.save"), b -> {
            persist();
            onClose();
        }).bounds(16 + (width - 28) / 2, height - 28, (width - 28) / 2, 20).build());
    }

    private void persist() {
        if (yt == null) return;
        values = new String[]{yt.getValue().strip(), ffmpeg.getValue().strip(), probe.getValue().strip(), javascript.getValue().strip()};
        app.settings.ytDlpPath = values[0];
        app.settings.ffmpegPath = values[1];
        app.settings.ffprobePath = values[2];
        app.settings.javascriptPath = values[3];
        app.downloads.configure(new Tools(values[0], values[1], values[2], values[3]));
        app.save();
    }

    @Override
    public void removed() {
        persist();
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 10, 0xFFB5B0FF);
        String[] labels = {Text.get("text.darkmusic.yt_dlp_executable"), Text.get("text.darkmusic.ffmpeg_executable"), Text.get("text.darkmusic.ffprobe_blank_alongside_ffmpeg_or_path"), Text.get("text.darkmusic.deno_node_executable_optional")};
        for (int i = 0; i < labels.length; i++) g.text(font, labels[i], 12, 24 + i * 40, 0xFFCCCCDD);
        var s = app.downloads.snapshot();
        g.text(font, font.plainSubstrByWidth(s.toolStatus(), width - 24), 12, height - 62, 0xFFCCCCDD);
        g.text(font, font.plainSubstrByWidth(s.analyzing() ? Text.get("text.darkmusic.checking") : s.message(), width - 24), 12, height - 48, 0xFFFFCC88);
        super.extractRenderState(g, x, y, delta);
    }
}
