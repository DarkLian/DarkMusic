package com.darkmusic.client.gui;

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
        super(Component.literal("Download Tools"), parent);
        this.app = app;
        values = new String[]{app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath};
    }

    private EditBox input(String value, String label, int y) {
        var field = new EditBox(font, 12, y, width - 24, 20, Component.literal(label));
        field.setMaxLength(4096);
        field.setValue(value);
        field.setHint(Component.literal("Auto: instance tools directory, then PATH"));
        return addRenderableWidget(Help.tip(field, "Paste the executable file's full path. Leave blank for automatic lookup. No tools are downloaded, installed, or updated automatically."));
    }

    @Override
    protected void init() {
        yt = input(values[0], "yt-dlp Executable", 36);
        ffmpeg = input(values[1], "FFmpeg Executable", 76);
        probe = input(values[2], "FFprobe Executable", 116);
        javascript = input(values[3], "Deno/Node Executable (Optional)", 156);
        yt.setResponder(s -> values[0] = s);
        ffmpeg.setResponder(s -> values[1] = s);
        probe.setResponder(s -> values[2] = s);
        javascript.setResponder(s -> values[3] = s);
        javascript.setHint(Component.literal("Auto: Deno or Node on PATH; YouTube may require this"));
        addRenderableWidget(Button.builder(Component.literal("Check Tools"), b -> {
            persist();
            app.downloads.checkTools();
        }).bounds(12, height - 28, (width - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
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
        String[] labels = {"yt-dlp Executable", "FFmpeg Executable", "FFprobe (Blank: Alongside FFmpeg or PATH)", "Deno/Node Executable (Optional)"};
        for (int i = 0; i < labels.length; i++) g.text(font, labels[i], 12, 24 + i * 40, 0xFFCCCCDD);
        var s = app.downloads.snapshot();
        g.text(font, font.plainSubstrByWidth(s.toolStatus(), width - 24), 12, height - 62, 0xFFCCCCDD);
        g.text(font, font.plainSubstrByWidth(s.analyzing() ? "Checking…" : s.message(), width - 24), 12, height - 48, 0xFFFFCC88);
        super.extractRenderState(g, x, y, delta);
    }
}
