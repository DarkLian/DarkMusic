package com.darkmusic.client.gui;

import com.darkmusic.client.pip.PipNativeTools;
import com.darkmusic.client.pip.PipPreview;
import com.darkmusic.client.pip.PipSource;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ProcessRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.*;

public final class PipSourcesScreen extends LocalScreen implements PipPreview {
    private final PipScreen.Sources app;
    private List<PipSource> sources = List.of();
    private ProcessRunner.Token token;
    private RowList list;
    private Button choose;
    private int selected = -1;
    private String status = "pip.windows_loading";

    public PipSourcesScreen(PipScreen.Sources app, Screen parent) {
        super(Text.component("pip.choose"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        backButton();
        list = addRenderableWidget(new RowList(12, 64, width - 24, Math.max(32, height - 102), 32, Text.get("pip.choose"), (i, activate) -> {
            selected = i;
            choose.active = i >= 0 && i < sources.size();
            if (activate) select();
        }));
        choose = addRenderableWidget(UiButton.builder(Text.component("pip.select"), b -> select()).bounds(width / 2 + 2, height - 30, width / 2 - 14, 20).build());
        addRenderableWidget(UiButton.builder(Text.component("pip.refresh"), b -> refresh()).bounds(12, height - 30, width / 2 - 14, 20).build());
        fill();
        if (token == null) refresh();
    }

    private void fill() {
        list.rows(sources.size(), i -> {
            var s = sources.get(i);
            return new RowList.Row(s.Title(), s.App() + (s.Minimized() ? " · " + Text.get("pip.minimized") : ""));
        });
        choose.active = selected >= 0 && selected < sources.size();
    }

    private void select() {
        if (selected < 0 || selected >= sources.size()) return;
        app.pip().select(sources.get(selected));
        onClose();
    }

    private void refresh() {
        if (token != null) token.cancel();
        var request = new ProcessRunner.Token();
        token = request;
        status = "pip.windows_loading";
        choose.active = false;
        app.pip().windows(request).whenComplete((windows, error) -> Minecraft.getInstance().execute(() -> {
            if (!isCurrent() || request != token || request.cancelled()) return;
            if (error != null) {
                status = PipNativeTools.failure(error);
                if (status.equals("pip.capture_error")) status = "pip.windows_error";
                return;
            }
            sources = windows;
            selected = -1;
            status = sources.isEmpty() ? "pip.windows_empty" : "pip.windows_help";
            fill();
        }));
    }

    @Override
    public void removed() {
        if (token != null) token.cancel();
        token = null;
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.PREVIEW);
        longText.begin();
        longText.drawUpdating(g, font, "status", Text.get(status), 12, 42, width - 24, UiStatus.color(UiStatus.Tone.INFO));
        super.extractRenderState(g, x, y, delta);
    }
}
