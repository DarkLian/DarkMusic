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
    private final String[] values;
    private final String[] keys = {"text.darkmusic.yt_dlp_executable", "text.darkmusic.ffmpeg_executable", "text.darkmusic.ffprobe_blank_alongside_ffmpeg_or_path", "text.darkmusic.deno_node_executable_optional"};

    public DownloadPathsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("tools.darkmusic.advanced"), parent);
        this.app = app;
        values = new String[]{app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath};
    }

    @Override
    protected void init() {
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            var input = new EditBox(font, 12, 40 + i * 40, width - 24, 20, Text.component(keys[i]));
            input.setMaxLength(4096);
            input.setValue(values[i]);
            input.setHint(Text.component("tools.darkmusic.auto"));
            input.setResponder(value -> values[index] = value);
            addRenderableWidget(Help.tip(input, Text.get("tools.darkmusic.paths_help")));
        }
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
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 12, 0xFFB5B0FF);
        for (int i = 0; i < keys.length; i++)
            longText.draw(g, font, keys[i], Text.get(keys[i]), 12, 28 + i * 40, width - 24, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, delta);
    }
}
