package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.awt.Color;
import java.util.function.IntConsumer;

/**
 * A saturation/value field and hue strip, rendered with eight gradient quads.
 */
public final class ColorSpectrum extends AbstractWidget {
    private final IntConsumer change;
    private float hue, saturation, brightness;
    private boolean hueDrag;
    private final SpectrumMesh shade = new SpectrumMesh();

    public ColorSpectrum(int x, int y, int width, int color, IntConsumer change) {
        super(x, y, width, 84, Text.component("colors.darkmusic.visual_picker"));
        this.change = change;
        float[] hsv = Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
        hue = hsv[0];
        saturation = hsv[1];
        brightness = hsv[2];
    }

    public static int rgb(float hue, float saturation, float brightness) {
        return Color.HSBtoRGB(hue, Math.clamp(saturation, 0, 1), Math.clamp(brightness, 0, 1)) & 0xFFFFFF;
    }

    private void publish() {
        change.accept(rgb(hue, saturation, brightness));
    }

    private void choose(double x, double y) {
        if (hueDrag) hue = (float) Math.clamp((x - getX()) / Math.max(1, width - 1), 0, 1);
        else {
            saturation = (float) Math.clamp((x - getX()) / Math.max(1, width - 1), 0, 1);
            brightness = 1 - (float) Math.clamp((y - getY()) / 63, 0, 1);
        }
        publish();
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        hueDrag = event.y() >= getY() + 68;
        choose(event.x(), event.y());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        choose(event.x(), event.y());
    }

    @Override
    public boolean keyPressed(KeyEvent key) {
        if (!active || !isFocused()) return false;
        switch (key.key()) {
            case 263 -> saturation = Math.max(0, saturation - .01f);
            case 262 -> saturation = Math.min(1, saturation + .01f);
            case 264 -> brightness = Math.max(0, brightness - .01f);
            case 265 -> brightness = Math.min(1, brightness + .01f);
            case 266 -> hue = (hue + .01f) % 1;
            case 267 -> hue = (hue + .99f) % 1;
            default -> {
                return false;
            }
        }
        publish();
        return true;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        UiTheme.gradient(g, getX(), getY(), width, 64, 0xFFFFFFFF, 0xFF000000 | rgb(hue, 1, 1));
        shade.begin(width, 0, Settings.HudShape.RECTANGLE);
        shade.gradient(getX(), getY(), getRight(), getY() + 64, 0x00000000, 0xFF000000);
        shade.submit(g);
        for (int i = 0; i < 6; i++) {
            int left = i * width / 6, right = (i + 1) * width / 6;
            UiTheme.gradient(g, getX() + left, getY() + 70, right - left, 14,
                0xFF000000 | rgb(i / 6f, 1, 1), 0xFF000000 | rgb((i + 1) / 6f, 1, 1));
        }
        int x = getX() + Math.round(saturation * (width - 1));
        int y = getY() + Math.round((1 - brightness) * 63);
        g.outline(Math.clamp(x - 3, getX(), getRight() - 7), Math.clamp(y - 3, getY(), getY() + 57), 7, 7, 0xFF000000);
        g.outline(Math.clamp(x - 2, getX() + 1, getRight() - 6), Math.clamp(y - 2, getY() + 1, getY() + 58), 5, 5, 0xFFFFFFFF);
        int marker = getX() + Math.round(hue * (width - 3));
        g.outline(marker, getY() + 69, 3, 16, 0xFF000000);
        g.fill(marker + 1, getY() + 70, marker + 2, getY() + 84, 0xFFFFFFFF);
        if (isFocused()) g.outline(getX() - 1, getY() - 1, width + 2, height + 2, UiTheme.ACCENT);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
