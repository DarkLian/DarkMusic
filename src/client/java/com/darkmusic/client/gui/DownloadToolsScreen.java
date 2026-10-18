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
    private ToolCards tools;
    private java.util.List<String> displayed = java.util.List.of();

    public DownloadToolsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.download_tools"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        int half = (width - 28) / 2;
        install = addRenderableWidget(Help.tip(UiButton.builder(Text.component("tools.darkmusic.install"), b -> {
            persist();
            app.downloads.installTools();
        }).bounds(12, 34, half, 20).build(), Text.get("tools.darkmusic.install_help")));
        check = addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.check_tools"), b -> {
            persist();
            app.downloads.checkTools();
        }).bounds(16 + half, 34, half, 20).build());
        advanced = addRenderableWidget(UiButton.builder(Text.component("tools.darkmusic.advanced"), b -> {
            persist();
            show(new DownloadPathsScreen(app, this));
        }).bounds(12, 60, half, 20).build());
        cancel = addRenderableWidget(Help.tip(UiButton.builder(Text.component("tools.darkmusic.cancel_operation"), b -> app.downloads.cancelTools()).bounds(16 + half, 60, half, 20).build(), Text.get("tools.darkmusic.cancel_help")));
        tools = addRenderableWidget(new ToolCards(12, 110, width - 24, height - 156));
        UiButton.style(install, UiButton.Style.PRIMARY);
        UiButton.style(advanced, UiButton.Style.SUBTLE);
        UiButton.style(cancel, UiButton.Style.DESTRUCTIVE);
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
            tools.versions(versions);
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
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.TOOLS);
        UiTheme.panel(g, 12, height - 40, width - 24, 32);
        UiTheme.section(g, font, Text.get("ui.darkmusic.dependencies"), 12, 86, width - 24);
        var status = app.downloads.snapshot();
        String summary = status.toolStatus();
        longText.draw(g, font, "tools", summary, 12, height - 34, width - 24, UiTheme.SECONDARY);
        longText.draw(g, font, "tools_message", status.message(), 12, height - 20, width - 24, UiTheme.WARNING);
        super.extractRenderState(g, x, y, delta);
    }
}
