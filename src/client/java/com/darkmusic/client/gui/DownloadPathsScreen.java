package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.download.DownloadModels.Tools;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/**
 * Optional overrides; the normal setup requires no paths.
 */
public final class DownloadPathsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private ScrollBar scrollbar;
    private final String[] values;
    private int scroll, formX, formWidth;
    private final EditBox[] inputs = new EditBox[4];
    private final String[] keys = {"text.darkmusic.yt_dlp_executable", "text.darkmusic.ffmpeg_executable", "text.darkmusic.ffprobe_blank_alongside_ffmpeg_or_path", "text.darkmusic.deno_node_executable_optional"};

    public DownloadPathsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("tools.darkmusic.advanced"), parent);
        this.app = app;
        values = new String[]{app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath};
    }

    @Override
    protected void init() {
        backButton();
        formWidth = Math.min(680, width - 24);
        formX = (width - formWidth) / 2;
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            var input = new UiInput(font, formX + 8, 64 + i * 52, formWidth - 16, 20, Text.component(keys[i]));
            inputs[i] = input;
            input.setMaxLength(4096);
            input.setValue(values[i]);
            input.setHint(Text.component("tools.darkmusic.auto"));
            input.setResponder(value -> values[index] = value);
            addRenderableWidget(Help.tip(input, Text.get(keys[i]) + "\n" + Text.get("tools.darkmusic.paths_help")));
        }
        scrollbar = addRenderableWidget(new ScrollBar(() -> scroll, value -> {
            scroll = value;
            position();
        }));
        position();
    }

    private void position() {
        scroll = Math.clamp(scroll, 0, Math.max(0, 208 - (height - 60)));
        if (scrollbar != null) scrollbar.configure(formX + formWidth + 2, 48, height - 60, 208, height - 60);
        for (int i = 0; i < 4; i++) {
            inputs[i].setY(64 + i * 52 - scroll);
            inputs[i].visible = inputs[i].getY() >= 52 && inputs[i].getBottom() <= height - 12;
            if (!inputs[i].visible && getFocused() == inputs[i]) setFocused(null);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (super.mouseScrolled(x, y, dx, dy)) return true;
        if (x < formX || x > formX + formWidth || y < 48 || y > height - 12) return false;
        scroll -= (int) Math.round(dy * 26);
        position();
        return true;
    }

    @Override
    public void removed() {
        app.settings.ytDlpPath = values[0].strip();
        app.settings.ffmpegPath = values[1].strip();
        app.settings.ffprobePath = values[2].strip();
        app.settings.javascriptPath = values[3].strip();
        app.downloads.configure(new Tools(app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath));
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.TOOLS);
        UiTheme.panel(g, formX, 36, formWidth, height - 44);
        g.enableScissor(formX + 4, 40, formX + formWidth - 4, height - 12);
        for (int i = 0; i < keys.length; i++)
            longText.draw(g, font, keys[i], Text.get(keys[i]), formX + 8, 50 + i * 52 - scroll, formWidth - 16, UiTheme.SECONDARY);
        g.disableScissor();
        super.extractRenderState(g, x, y, delta);
    }
}
