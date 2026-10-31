package com.darkmusic.client.gui;

import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ToolPaths;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

public final class DownloadToolsScreen extends LocalScreen {
    /**
     * The download tools and their diagnostics; the tools and diagnostic screens open each other, so they share this.
     */
    public interface Sources extends AudioPage.Sources {
        Navigation navigation();

        DownloadService downloads();

        DownloadOperations downloadOperations();

    }

    private final Sources app;
    private Button install, check, advanced, cancel;
    private Button details;
    private ToolCards tools;
    private List<String> displayed = List.of();

    public DownloadToolsScreen(Sources app, Screen parent) {
        super(Text.component("text.darkmusic.download_tools"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        backButton();
        int half = (width - 28) / 2;
        install = addRenderableWidget(Help.tip(UiButton.builder(Text.component("tools.darkmusic.install"), b -> {
            persist();
            app.downloadOperations().installTools();
        }).bounds(12, 34, half, 20).build(), Text.get("tools.darkmusic.install_help")));
        check = addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.check_tools"), b -> {
            persist();
            app.downloadOperations().checkTools();
        }).bounds(16 + half, 34, half, 20).build());
        advanced = addRenderableWidget(UiButton.builder(Text.component("tools.darkmusic.advanced"), b -> {
            persist();
            show(app.navigation().downloadPaths(this));
        }).bounds(12, 60, half, 20).build());
        cancel = addRenderableWidget(Help.tip(UiButton.builder(Text.component("tools.darkmusic.cancel_operation"), b -> app.downloadOperations().cancelTools()).bounds(16 + half, 60, half, 20).build(), Text.get("tools.darkmusic.cancel_help")));
        tools = addRenderableWidget(new ToolCards(12, 110, width - 24, height - 156));
        UiButton.style(install, UiButton.Style.PRIMARY);
        UiButton.style(advanced, UiButton.Style.SUBTLE);
        UiButton.style(cancel, UiButton.Style.DESTRUCTIVE);
        details = addRenderableWidget(UiButton.builder(Text.component("download.diag.title"), b -> {
            var diagnostic = app.downloads().toolDiagnostic();
            if (diagnostic != null)
                show(new DownloadDiagnosticScreen(app, this, diagnostic, app.downloadOperations()::checkTools));
        }).bounds(width - 108, height - 36, 96, 20).build());
        displayed = null;
        tick();
    }

    private void persist() {
        app.downloadOperations().configure(new ToolPaths(app.settings().ytDlpPath, app.settings().ffmpegPath, app.settings().ffprobePath, app.settings().javascriptPath));
    }

    @Override
    public void tick() {
        boolean busy = app.downloads().snapshot().analyzing();
        var versions = app.downloads().toolVersions();
        if (!versions.equals(displayed)) {
            displayed = versions;
            tools.versions(versions);
        }
        install.active = check.active = !busy;
        cancel.visible = cancel.active = app.downloads().toolsBusy();
        advanced.setWidth(cancel.visible ? (width - 28) / 2 : width - 24);
        details.visible = details.active = app.downloads().toolDiagnostic() != null;
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
        var status = app.downloads().snapshot();
        String summary = status.toolStatus();
        longText.draw(g, font, "tools", summary, 12, height - 34, width - (details.visible ? 128 : 24), UiStatus.color(app.downloads().toolDiagnostic() != null ? UiStatus.Tone.ERROR : app.downloads().toolsBusy() || app.downloads().toolVersions().isEmpty() ? UiStatus.Tone.INFO : UiStatus.Tone.SUCCESS));
        longText.draw(g, font, "tools_message", status.message(), 12, height - 20, width - (details.visible ? 128 : 24), UiStatus.color(app.downloads().toolDiagnostic() != null ? UiStatus.Tone.ERROR : UiStatus.Tone.INFO));
        super.extractRenderState(g, x, y, delta);
    }
}
