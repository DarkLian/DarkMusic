package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.hud.HudScale;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * One layout editor and shared wheel behavior for the combined HUD.
 */
public class HudEditorScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final HudScale wheel = new HudScale();
    private boolean dragging;
    private double dragX, dragY;
    private long scaleChanged;

    public HudEditorScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("hud.darkmusic.layout"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        addRenderableWidget(Help.tip(Button.builder(Text.component("hud.darkmusic.reset_selected"), b -> {
            app.settings.hudX = app.settings.hudY = 0.0;
            app.settings.hudScale = 1;
            scaleChanged = System.nanoTime();
            app.save();
        }).bounds(width - 144, 8, 106, 20).build(), Text.get("hud.darkmusic.help")));
        addRenderableWidget(Help.tip(Button.builder(net.minecraft.network.chat.Component.literal("?"), b -> {
        }).bounds(width - 32, 8, 20, 20).build(), Text.get("hud.darkmusic.help")));
    }

    private HudLayout.Bounds bounds() {
        return NowPlayingHud.bounds(app, width, height);
    }

    private boolean target(double x, double y) {
        return bounds().contains(x, y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (super.mouseClicked(event, twice)) return true;
        if (event.button() != 0 || !target(event.x(), event.y())) return false;
        var b = bounds();
        dragging = true;
        dragX = event.x() - b.x();
        dragY = event.y() - b.y();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (dragging) return true;
        if (HorizontalText.horizontalIntent(dx, dy)) return super.mouseScrolled(x, y, dx, dy);
        if (dy == 0 || !target(x, y)) return super.mouseScrolled(x, y, dx, dy);
        app.settings.hudScale = wheel.scroll(app.settings.hudScale, dy);
        scaleChanged = System.nanoTime();
        app.save();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) return super.mouseDragged(event, dx, dy);
        var b = bounds();
        double x = HudLayout.normalized(event.x() - dragX, width, b.width());
        double y = HudLayout.normalized(event.y() - dragY, height, b.height());
        app.settings.hudX = x;
        app.settings.hudY = y;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        app.save();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xCC111420);
        NowPlayingHud.render(app, g, longText);
        var b = bounds();
        g.outline(b.x(), b.y(), b.width(), b.height(), 0xFFB5B0FF);
        if (System.nanoTime() - scaleChanged < 2_000_000_000L)
            g.centeredText(font, Text.get("hud.darkmusic.scale", Math.round(app.settings.hudScale * 100)), width / 2, 36, 0xFFB5B0FF);
        super.extractRenderState(g, x, y, delta);
    }
}
