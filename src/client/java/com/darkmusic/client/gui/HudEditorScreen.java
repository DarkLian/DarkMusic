package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.hud.HudScale;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private net.minecraft.client.gui.components.AbstractWidget reset, help, back;

    public HudEditorScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("hud.darkmusic.layout"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        dragging = false;
        reset = addRenderableWidget(Help.tip(UiButton.builder(Text.component("hud.darkmusic.reset_selected"), b -> {
            app.settings.hudX = app.settings.hudY = 0.0;
            app.settings.hudScale = 1;
            scaleChanged = System.nanoTime();
            app.save();
        }).bounds(width - 144, 8, 106, 20).build(), Text.get("hud.darkmusic.reset_help")));
        help = addRenderableWidget(new InfoHint(width - 32, 8, Text.component("hud.darkmusic.help")));
        back = backButton(width - 32, 8, 24);
        positionToolbar();
    }

    private void positionToolbar() {
        var b = bounds();
        int[][] corners = {{width - 176, 8}, {8, 8}, {width - 176, height - 32}, {8, height - 32}};
        for (var corner : corners) {
            int x = corner[0], y = corner[1];
            if (x < b.x() + b.width() && x + 168 > b.x() && y < b.y() + b.height() && y + 24 > b.y()) continue;
            reset.setX(x + 4);
            reset.setY(y + 2);
            help.setX(x + 116);
            help.setY(y + 2);
            back.setX(x + 144);
            back.setY(y + 2);
            return;
        }
    }

    @Override
    public void tick() {
        if (!dragging) positionToolbar();
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
        double left = event.x() - dragX;
        double top = event.y() - dragY;
        boolean snap = !HorizontalText.shiftDown();
        double x = snap && Math.abs(left + b.width() / 2.0 - width / 2.0) <= 4
            ? .5 : HudLayout.normalized(left, width, b.width());
        double y = snap && Math.abs(top + b.height() / 2.0 - height / 2.0) <= 4
            ? .5 : HudLayout.normalized(top, height, b.height());
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
        var b = bounds();
        boolean centeredX = Math.abs(b.x() + b.width() / 2.0 - width / 2.0) <= .5;
        boolean centeredY = Math.abs(b.y() + b.height() / 2.0 - height / 2.0) <= .5;
        int subdued = (UiTheme.ACCENT & 0x00FFFFFF) | 0x35000000;
        g.fill(width / 2, 0, width / 2 + 1, height, centeredX ? UiTheme.ACCENT : subdued);
        g.fill(0, height / 2, width, height / 2 + 1, centeredY ? UiTheme.ACCENT : subdued);
        NowPlayingHud.render(app, g, longText);
        g.outline(b.x(), b.y(), b.width(), b.height(), UiTheme.ACCENT);
        if (System.nanoTime() - scaleChanged < 2_000_000_000L)
            g.centeredText(font, Text.get("hud.darkmusic.scale", Math.round(app.settings.hudScale * 100)), width / 2, 36, UiTheme.ACCENT);
        super.extractRenderState(g, x, y, delta);
    }
}
