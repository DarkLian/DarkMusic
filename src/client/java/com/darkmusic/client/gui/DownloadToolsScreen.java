package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.download.DownloadModels.Tools;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

public final class DownloadToolsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private Button install, check, advanced, cancel;
    private RowList tools;
    private java.util.List<String> displayed = java.util.List.of();

    public DownloadToolsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.download_tools"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        int half = (width - 28) / 2;
        install = addRenderableWidget(Help.tip(Button.builder(Text.component("tools.darkmusic.install"), b -> {
            persist();
            app.downloads.installTools();
        }).bounds(12, 34, half, 20).build(), Text.get("tools.darkmusic.install_help")));
        check = addRenderableWidget(Button.builder(Text.component("text.darkmusic.check_tools"), b -> {
            persist();
            app.downloads.checkTools();
        }).bounds(16 + half, 34, half, 20).build());
        advanced = addRenderableWidget(Button.builder(Text.component("tools.darkmusic.advanced"), b -> {
            persist();
            show(new DownloadPathsScreen(app, this));
        }).bounds(12, 60, half, 20).build());
        cancel = addRenderableWidget(Help.tip(Button.builder(Text.component("tools.darkmusic.cancel_operation"), b -> app.downloads.cancelTools()).bounds(16 + half, 60, half, 20).build(), Text.get("tools.darkmusic.cancel_help")));
        tools = addRenderableWidget(new RowList(12, 90, width - 24, Math.max(28, height - 132), 28, Text.get("text.darkmusic.download_tools"), (i, activated) -> {
        }));
        displayed = null;
        tick();
    }

    private void persist() {
        app.downloads.configure(new Tools(app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath));
    }

    @Override
    public void tick() {
        boolean busy = app.downloads.snapshot().analyzing();
        var versions = app.downloads.toolVersions();
        if (!versions.equals(displayed)) {
            displayed = versions;
            String pending = Text.get("download.darkmusic.tools_unchecked");
            String[] names = {"yt-dlp", "FFmpeg", "FFprobe", "Deno / Node.js"};
            var rows = new java.util.ArrayList<RowList.Row>();
            for (int i = 0; i < names.length; i++)
                rows.add(new RowList.Row(names[i], i < versions.size() ? versions.get(i) : pending));
            tools.rows(rows);
        }
        install.active = check.active = !busy;
        cancel.visible = cancel.active = app.downloads.toolsBusy();
        advanced.setWidth(cancel.visible ? (width - 28) / 2 : width - 24);
    }

    @Override
    public void removed() {
        persist();
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 12, 0xFFB5B0FF);
        var status = app.downloads.snapshot();
        String summary = app.downloads.toolVersions().isEmpty() || status.analyzing() ? status.toolStatus() : Text.get("download.darkmusic.tools_ready");
        longText.draw(g, font, "tools", summary, 12, height - 34, width - 24, 0xFFCCCCDD);
        longText.draw(g, font, "tools_message", status.message(), 12, height - 20, width - 24, 0xFFFFCC88);
        super.extractRenderState(g, x, y, delta);
    }
}
