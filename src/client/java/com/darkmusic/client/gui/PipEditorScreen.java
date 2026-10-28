package com.darkmusic.client.gui;

import com.darkmusic.client.config.PipSettings;
import com.darkmusic.client.pip.PipPreview;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;

import java.util.Locale;

/**
 * Placement preserves source aspect ratio. Cropping changes UV coordinates, never the source window.
 */
public final class PipEditorScreen extends LocalScreen implements PipPreview {
    private final PipScreen.Sources app;
    private final boolean crop;
    private boolean dragging, resizing;
    private Button back, reset;
    private double anchorX, anchorY, offsetX, offsetY, endX, endY;

    public PipEditorScreen(PipScreen.Sources app, Screen parent, boolean crop) {
        super(Text.component(crop ? "pip.crop" : "pip.placement"), parent);
        this.app = app;
        this.crop = crop;
    }

    @Override
    protected void init() {
        dragging = false;
        back = backButton();
        reset = addRenderableWidget(UiButton.builder(Text.component("pip.reset"), b -> {
            if (crop) app.settings().pip.resetCrop();
            else {
                app.settings().pip.x = .68;
                app.settings().pip.y = .08;
                app.settings().pip.width = .3;
            }
            app.save();
        }).bounds(12, 4, 80, 20).build());
        if (crop) addRenderableWidget(UiButton.builder(Text.component("pip.crop_exact"), b -> {
            var s = app.settings().pip;
            show(TextPromptScreen.validated(this, Text.get("pip.crop_exact.help"), String.format(Locale.ROOT, "%.1f %.1f %.1f %.1f", s.left * 100, s.top * 100, s.right * 100, s.bottom * 100), 128, input -> {
                if (!s.cropPercent(input)) throw new IllegalArgumentException(Text.get("pip.crop_invalid"));
                app.save();
                return true;
            }));
        }).bounds(98, 4, Math.min(120, width - 190), 20).build());
    }

    private PipSettings.Bounds image() {
        if (!crop) return app.pip().bounds(width, height);
        var f = app.pip().frame();
        double ratio = f == null ? 16 / 9.0 : f.width() / (double) f.height();
        int w = width - 24, h = (int) (w / ratio);
        if (h > height - 90) {
            h = Math.max(16, height - 90);
            w = (int) (h * ratio);
        }
        return new PipSettings.Bounds((width - w) / 2, 40 + (height - 80 - h) / 2, w, h);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        positionControls(image());
        if (super.mouseClicked(event, twice)) return true;
        var b = image();
        if (event.button() != 0 || !b.contains(event.x(), event.y())) return false;
        setFocused(null);
        dragging = true;
        anchorX = endX = event.x();
        anchorY = endY = event.y();
        offsetX = anchorX - b.x();
        offsetY = anchorY - b.y();
        resizing = !crop && offsetX > b.width() - 12 && offsetY > b.height() - 12;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (!dragging) return super.mouseDragged(e, dx, dy);
        var s = app.settings().pip;
        var b = image();
        endX = Math.clamp(e.x(), b.x(), b.x() + b.width());
        endY = Math.clamp(e.y(), b.y(), b.y() + b.height());
        if (!crop) {
            if (resizing) s.width = Math.clamp((e.x() - b.x()) / width, .1, 1);
            else {
                var f = app.pip().frame();
                s.move(e.x() - offsetX, e.y() - offsetY, width, height, f == null ? 16 : f.width(), f == null ? 9 : f.height(), !HorizontalText.shiftDown());
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (!dragging) return super.mouseReleased(e);
        dragging = false;
        if (crop) {
            var b = image();
            double l = (Math.min(anchorX, endX) - b.x()) / b.width(), t = (Math.min(anchorY, endY) - b.y()) / b.height(), r = (Math.max(anchorX, endX) - b.x()) / b.width(), d = (Math.max(anchorY, endY) - b.y()) / b.height();
            if (r - l >= .05 && d - t >= .05) {
                var s = app.settings().pip;
                s.left = l;
                s.top = t;
                s.right = r;
                s.bottom = d;
            }
        }
        app.save();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (crop || !image().contains(x, y)) return super.mouseScrolled(x, y, dx, dy);
        app.settings().pip.width = Math.clamp(app.settings().pip.width * Math.pow(1.08, dy), .1, 1);
        app.save();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!crop && getFocused() == null) {
            var s = app.settings().pip;
            var b = image();
            s.x = b.x() / (double) width;
            s.y = b.y() / (double) height;
            double step = event.hasShiftDown() ? 8 : 1;
            switch (event.key()) {
                case 263 -> s.x -= step / width;
                case 262 -> s.x += step / width;
                case 265 -> s.y -= step / height;
                case 264 -> s.y += step / height;
                case 61, 334 -> s.width *= 1.04;
                case 45, 333 -> s.width /= 1.04;
                default -> {
                    return super.keyPressed(event);
                }
            }
            s.validate();
            app.save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xcc111420);
        app.pip().update();
        var b = image();
        positionControls(b);
        app.pip().draw(g, b, crop, !crop);
        if (!crop) {
            int guide = (UiTheme.ACCENT & 0xffffff) | 0x50000000;
            boolean centeredX = Math.abs(b.x() + b.width() / 2.0 - width / 2.0) <= .5;
            boolean centeredY = Math.abs(b.y() + b.height() / 2.0 - height / 2.0) <= .5;
            g.fill(width / 2, 0, width / 2 + 1, height, centeredX ? UiTheme.ACCENT : guide);
            g.fill(0, height / 2, width, height / 2 + 1, centeredY ? UiTheme.ACCENT : guide);
        }
        g.outline(b.x(), b.y(), b.width(), b.height(), UiTheme.ACCENT);
        if (crop) {
            var s = app.settings().pip;
            int l = dragging ? (int) Math.min(anchorX, endX) : b.x() + (int) (s.left * b.width()), t = dragging ? (int) Math.min(anchorY, endY) : b.y() + (int) (s.top * b.height());
            int r = dragging ? (int) Math.max(anchorX, endX) : b.x() + (int) (s.right * b.width()), d = dragging ? (int) Math.max(anchorY, endY) : b.y() + (int) (s.bottom * b.height());
            g.outline(l, t, Math.max(1, r - l), Math.max(1, d - t), UiStatus.color(UiStatus.Tone.SUCCESS));
        } else
            g.fill(b.x() + b.width() - 6, b.y() + b.height() - 6, b.x() + b.width(), b.y() + b.height(), UiTheme.ACCENT);
        if (crop) g.fill(0, 0, width, 28, 0xff111420);
        super.extractRenderState(g, x, y, delta);
    }

    private void positionControls(PipSettings.Bounds image) {
        if (crop || back == null || reset == null) return;
        var s = app.settings().pip;
        var spectrum = s.audioSpectrum ? s.spectrum.bounds(image, width, height) : null;
        positionControl(reset, image, spectrum);
        positionControl(back, image, spectrum);
    }

    private void positionControl(Button button, PipSettings.Bounds image, PipSettings.Bounds spectrum) {
        button.setY(PipEditorLayout.buttonY(height, image, spectrum, button.getX(), button.getWidth()));
        // A full-screen PiP has no free corner. Reveal the whole image while it is being dragged.
        button.visible = !dragging || PipEditorLayout.overlap(new PipSettings.Bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight()), image, spectrum) == 0;
    }
}
