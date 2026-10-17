package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.hud.NowPlayingHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class HudEditorScreen extends LocalScreen {
    private final DarkMusicClient app;
    private boolean dragging;
    private double dragX, dragY;

    public HudEditorScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("Edit HUD Layout"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        addRenderableWidget(Help.tip(new ValueSlider(12, height - 28, width - 140, (app.settings.hudScale - .5) / 1.5,
            v -> "HUD Scale " + Math.round(app.settings.hudScale * 100) + "%", v -> app.settings.hudScale = Math.round((.5 + v * 1.5) * 10) / 10.0, app::save, 15), "Scale the entire HUD from 50% to 200%. Drag the preview to move it. Escape saves and returns."));
        addRenderableWidget(Help.tip(Button.builder(Component.literal("Reset Layout"), b -> {
            app.settings.hudX = app.settings.hudY = 0.0;
            app.settings.hudScale = 1;
            app.save();
            rebuildWidgets();
        }).bounds(width - 120, height - 28, 108, 20).build(), "Restore top-left placement and 100% scale."));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (super.mouseClicked(event, twice)) return true;
        var bounds = NowPlayingHud.bounds(app, width, height);
        if (event.button() == 0 && bounds.contains(event.x(), event.y())) {
            dragging = true;
            dragX = event.x() - bounds.x();
            dragY = event.y() - bounds.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) return super.mouseDragged(event, dx, dy);
        var bounds = NowPlayingHud.bounds(app, width, height);
        app.settings.hudX = HudLayout.normalized(event.x() - dragX, width, bounds.width());
        app.settings.hudY = HudLayout.normalized(event.y() - dragY, height, bounds.height());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            app.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xCC111420);
        g.text(font, "Drag the HUD to move it. Escape saves and returns.", 12, 12, 0xFFB5B0FF);
        NowPlayingHud.render(app, g, true);
        var b = NowPlayingHud.bounds(app, width, height);
        g.outline(b.x(), b.y(), b.width(), b.height(), 0xFFB5B0FF);
        super.extractRenderState(g, x, y, d);
    }
}
