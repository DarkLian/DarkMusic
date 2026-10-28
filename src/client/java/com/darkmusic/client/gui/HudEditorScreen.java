package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsSource;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.hud.HudScale;
import com.darkmusic.client.hud.HudSources;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.hud.SongCard;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.Arrays;
import java.util.List;

/**
 * Independent placement of the player HUD, lyrics and song change card, with shared snapping and wheel behavior.
 */
public class HudEditorScreen extends LocalScreen implements HudPreview {
    /**
     * HUD positions and scales to edit, and the HUDs to preview them on.
     */
    public interface Sources extends SettingsSource {
        HudSources hud();
    }

    public enum Element {
        PLAYER("lyrics.hud.player"), LYRICS("lyrics.hud.element"), CARD("hud.darkmusic.card_element");

        final String label;

        Element(String label) {
            this.label = label;
        }
    }

    private final Sources app;
    private final HudScale wheel = new HudScale(), lyricsWheel = new HudScale(), cardWheel = new HudScale();
    private Element selected;
    private boolean dragging;
    private double dragX, dragY;
    private long scaleChanged;
    private AbstractWidget reset, help, back, select;

    public HudEditorScreen(Sources app, Screen parent) {
        this(app, parent, Element.PLAYER);
    }

    public HudEditorScreen(Sources app, Screen parent, Element selected) {
        super(Text.component("hud.darkmusic.layout"), parent);
        this.app = app;
        this.selected = selected;
    }

    private boolean available(Element element) {
        return switch (element) {
            case PLAYER -> true;
            case LYRICS -> LyricsHud.enabled(app.settings());
            case CARD -> app.settings().songCard != Settings.SongCard.OFF;
        };
    }

    private List<Element> elements() {
        return Arrays.stream(Element.values()).filter(this::available).toList();
    }

    private HudLayout.Bounds bounds(Element element) {
        return switch (element) {
            case PLAYER -> NowPlayingHud.bounds(app.hud(), width, height);
            case LYRICS -> LyricsHud.bounds(app.settings(), width, height);
            case CARD -> SongCard.bounds(app.hud(), width, height);
        };
    }

    @Override
    protected void init() {
        dragging = false;
        if (!available(selected)) selected = Element.PLAYER;
        select = addRenderableWidget(UiButton.builder(Text.component(selected.label), b -> {
            var choices = elements();
            selected = choices.get((choices.indexOf(selected) + 1) % choices.size());
            select.setMessage(Text.component(selected.label));
        }).bounds(8, 8, 100, 20).build());
        select.active = elements().size() > 1;
        reset = addRenderableWidget(Help.tip(UiButton.builder(Text.component("hud.darkmusic.reset_selected"), b -> {
            switch (selected) {
                case LYRICS -> {
                    app.settings().lyricsHudX = .5;
                    app.settings().lyricsHudY = .9;
                    app.settings().lyricsHudScale = 1;
                }
                case CARD -> {
                    // Back beside the HUD.
                    app.settings().songCardX = app.settings().songCardY = null;
                    app.settings().songCardScale = 1;
                }
                case PLAYER -> {
                    app.settings().hudX = app.settings().hudY = 0.0;
                    app.settings().hudScale = 1;
                }
            }
            scaleChanged = System.nanoTime();
            app.save();
        }).bounds(width - 144, 8, 106, 20).build(), Text.get("hud.darkmusic.reset_help")));
        help = addRenderableWidget(new InfoHint(width - 32, 8, Text.component("hud.darkmusic.help")));
        back = backButton(width - 32, 8, 24);
        positionToolbar();
    }

    private void positionToolbar() {
        int[][] corners = {{width - 280, 8}, {8, 8}, {width - 280, height - 32}, {8, height - 32}};
        for (int i = 0; i < corners.length; i++) {
            var corner = corners[i];
            int x = corner[0], y = corner[1];
            if (i < corners.length - 1 && elements().stream().anyMatch(element -> overlaps(bounds(element), x, y)))
                continue;
            select.setX(x + 4);
            select.setY(y + 2);
            reset.setX(x + 108);
            reset.setY(y + 2);
            help.setX(x + 220);
            help.setY(y + 2);
            back.setX(x + 248);
            back.setY(y + 2);
            return;
        }
    }

    private static boolean overlaps(HudLayout.Bounds b, int x, int y) {
        return x < b.x() + b.width() && x + 272 > b.x() && y < b.y() + b.height() && y + 24 > b.y();
    }

    @Override
    public void tick() {
        if (!dragging) positionToolbar();
    }

    private boolean target(double x, double y) {
        return elements().stream().anyMatch(element -> bounds(element).contains(x, y));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (super.mouseClicked(event, twice)) return true;
        if (event.button() != 0 || !target(event.x(), event.y())) return false;
        choose(event.x(), event.y());
        var b = bounds(selected);
        dragging = true;
        dragX = event.x() - b.x();
        dragY = event.y() - b.y();
        return true;
    }

    private void choose(double x, double y) {
        // The toolbar can select any element when their bounds overlap; a click keeps the current one if it is under it.
        if (!bounds(selected).contains(x, y))
            selected = elements().stream().filter(element -> bounds(element).contains(x, y)).findFirst().orElse(selected);
        select.setMessage(Text.component(selected.label));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (dragging) return true;
        if (HorizontalText.horizontalIntent(dx, dy)) return super.mouseScrolled(x, y, dx, dy);
        if (dy == 0 || !target(x, y)) return super.mouseScrolled(x, y, dx, dy);
        choose(x, y);
        switch (selected) {
            case LYRICS -> app.settings().lyricsHudScale = lyricsWheel.scroll(app.settings().lyricsHudScale, dy);
            case CARD -> {
                pinCard();
                app.settings().songCardScale = cardWheel.scroll(app.settings().songCardScale, dy);
            }
            case PLAYER -> app.settings().hudScale = wheel.scroll(app.settings().hudScale, dy);
        }
        scaleChanged = System.nanoTime();
        app.save();
        return true;
    }

    /**
     * A card that still follows the HUD gets its own position where it is now, before being moved or scaled.
     */
    private void pinCard() {
        if (app.settings().songCardX != null && app.settings().songCardY != null) return;
        var b = SongCard.bounds(app.hud(), width, height);
        app.settings().songCardX = HudLayout.normalized(b.x(), width, b.width());
        app.settings().songCardY = HudLayout.normalized(b.y(), height, b.height());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) return super.mouseDragged(event, dx, dy);
        var b = bounds(selected);
        double left = event.x() - dragX;
        double top = event.y() - dragY;
        boolean snap = !HorizontalText.shiftDown();
        double x = snap && Math.abs(left + b.width() / 2.0 - width / 2.0) <= 4
            ? .5 : HudLayout.normalized(left, width, b.width());
        double y = snap && Math.abs(top + b.height() / 2.0 - height / 2.0) <= 4
            ? .5 : HudLayout.normalized(top, height, b.height());
        switch (selected) {
            case LYRICS -> {
                app.settings().lyricsHudX = x;
                app.settings().lyricsHudY = y;
            }
            case CARD -> {
                app.settings().songCardX = x;
                app.settings().songCardY = y;
            }
            case PLAYER -> {
                app.settings().hudX = x;
                app.settings().hudY = y;
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        app.save();
        return true;
    }

    private double scale(Element element) {
        return switch (element) {
            case PLAYER -> app.settings().hudScale;
            case LYRICS -> app.settings().lyricsHudScale;
            case CARD -> app.settings().songCardScale;
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xCC111420);
        var b = bounds(selected);
        boolean centeredX = Math.abs(b.x() + b.width() / 2.0 - width / 2.0) <= .5;
        boolean centeredY = Math.abs(b.y() + b.height() / 2.0 - height / 2.0) <= .5;
        int subdued = (UiTheme.ACCENT & 0x00FFFFFF) | 0x35000000;
        g.fill(width / 2, 0, width / 2 + 1, height, centeredX ? UiTheme.ACCENT : subdued);
        g.fill(0, height / 2, width, height / 2 + 1, centeredY ? UiTheme.ACCENT : subdued);
        NowPlayingHud.render(app.hud(), g, longText);
        LyricsHud.render(app.hud(), g, true);
        if (available(Element.CARD)) SongCard.renderPreview(app.hud(), g);
        for (var element : elements())
            if (element != selected) {
                var other = bounds(element);
                g.outline(other.x(), other.y(), other.width(), other.height(), subdued);
            }
        g.outline(b.x(), b.y(), b.width(), b.height(), UiTheme.ACCENT);
        if (System.nanoTime() - scaleChanged < 2_000_000_000L)
            g.centeredText(font, Text.get("hud.darkmusic.scale", Math.round(scale(selected) * 100)), width / 2, 36, UiTheme.ACCENT);
        super.extractRenderState(g, x, y, delta);
    }
}
