package com.darkmusic.client.gui;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.hud.HudLyricsLayout;
import com.darkmusic.client.hud.HudScale;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.SpectrumRenderer;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Settings screen's live preview: the HUD, the lyrics HUD or the player's spectrum, and below them the player's
 * progress bar while its section is open. Its scale is the preview's own; the HUD's scale is unchanged.
 */
final class SettingsPreview {
    private static final int PROGRESS_SPACE = 54;
    private final SettingsScreen.Sources app;
    private final SpectrumRenderer playerSpectrum = new SpectrumRenderer();
    private HudScale wheel = new HudScale();
    private double scale = 1;
    private long scaleChanged;
    private int x, y, width, height;
    private ValueSlider playerProgress;

    SettingsPreview(SettingsScreen.Sources app) {
        this.app = app;
    }

    /**
     * Another settings object was installed: the preview starts at full scale again.
     */
    void reset() {
        scale = 1;
        scaleChanged = 0;
        wheel = new HudScale();
    }

    void place(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        playerProgress = new ValueSlider(x + 12, y + height - 27, Math.max(1, width - 24), .4,
            v -> "", v -> {
        }, () -> {
        });
        PlayerProgress.configure(playerProgress, app, () -> {
            var track = app.playback().state().track();
            if (track != null) return track;
            var tracks = app.library().snapshot().tracks();
            return tracks.isEmpty() ? null : tracks.getFirst();
        });
    }

    int x() {
        return x;
    }

    int y() {
        return y;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    double scale() {
        return scale;
    }

    ValueSlider playerProgress() {
        return playerProgress;
    }

    /**
     * Scrolling over the drawn HUD (not over the progress bar below it) changes the preview's scale.
     */
    boolean zoom(double px, double py, double dy, boolean lyrics, boolean progress) {
        if (!(px >= x && px < x + width && py >= y + 20 && py < y + height - (progress ? PROGRESS_SPACE : 0) && dy != 0))
            return false;
        scale = wheel.scroll(scale, dy);
        fit(lyrics, progress);
        scaleChanged = System.nanoTime();
        return true;
    }

    private void fit(boolean lyrics, boolean progress) {
        var layout = NowPlayingHud.content(app.hud());
        int w = lyrics ? app.settings().lyricsHudWidth : layout.width();
        int h = lyrics ? HudLyricsLayout.height(app.settings().lyricsNextLine) : layout.height();
        double fit = Math.min((width - 16.0) / w, (height - 30.0 - (progress ? PROGRESS_SPACE : 0)) / h);
        scale = Math.min(scale, Math.max(.1, Math.floor(fit * 10) / 10));
    }

    void render(GuiGraphicsExtractor g, Font font, HorizontalText longText, float delta, boolean lyrics, boolean progress) {
        UiTheme.panel(g, x, y, width, height);
        g.text(font, Text.get(app.settings().hudEnabled || lyrics ? "hud.darkmusic.live_preview" : "settings.player_preview"), x + 6, y + 6, UiTheme.ACCENT);
        g.enableScissor(x + 2, y + 20, x + width - 2, y + height - 2);
        if (app.settings().hudEnabled || lyrics) fit(lyrics, progress);
        if (lyrics)
            LyricsHud.renderPreview(app.hud(), g, x + 8, y + 22, width - 16, height - 30, scale);
        else if (!app.settings().hudEnabled) {
            int available = height - 30 - (progress ? PROGRESS_SPACE : 0);
            int spectrumHeight = Math.max(1, Math.min(60, available));
            playerSpectrum.bars(app.settings(), app.playback(), g, SpectrumRenderer.Bars.at(x + 12, y + 22 + (available - spectrumHeight) / 2,
                width - 24, spectrumHeight, false, true));
        } else
            NowPlayingHud.renderPreview(app.hud(), g, x + 8, y + 22, width - 16, height - 30 - (progress ? PROGRESS_SPACE : 0), scale, longText);
        if (progress) {
            int divider = y + height - PROGRESS_SPACE;
            g.fill(x + 6, divider, x + width - 6, divider + 1, UiTheme.BORDER);
            longText.draw(g, font, "player_progress_preview", Text.get("progress.darkmusic.player_preview"), x + 10, divider + 7, width - 20, UiTheme.ACCENT);
            var state = app.playback().state();
            boolean actual = state.status() == AudioBackend.Status.PLAYING || state.status() == AudioBackend.Status.PAUSED;
            playerProgress.syncNow(actual && state.duration() > 0 ? state.position() / state.duration() : .4);
            playerProgress.extractWidgetRenderState(g, Integer.MIN_VALUE, Integer.MIN_VALUE, delta);
        }
        g.disableScissor();
        if (System.nanoTime() - scaleChanged < 2_000_000_000L) {
            String label = Text.get("hud.darkmusic.preview_scale", Math.round(scale * 100));
            g.text(font, label, x + width - font.width(label) - 8, y + 6, UiTheme.ACCENT);
        }
    }
}
