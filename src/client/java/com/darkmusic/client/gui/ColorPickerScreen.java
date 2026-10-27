package com.darkmusic.client.gui;

import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Switchable spectrum/RGB editor with a shared palette, hex input, and immediate updates.
 */
public final class ColorPickerScreen extends LocalScreen {
    private final IntConsumer change;
    private final int defaultColor;
    private int color;
    private UiInput hex;
    private boolean syncing;
    private boolean spectrum = true;

    public ColorPickerScreen(Screen parent, int color, IntConsumer change) {
        this(parent, color, color, change);
    }

    public ColorPickerScreen(Screen parent, int color, int defaultColor, IntConsumer change) {
        super(Text.component("colors.darkmusic.picker"), parent);
        this.color = color & 0xFFFFFF;
        this.defaultColor = defaultColor & 0xFFFFFF;
        this.change = change;
    }

    @Override
    protected void init() {
        backButton();
        int w = Math.min(360, width - 24), x = (width - w) / 2, y = 60;
        var visualMode = addRenderableWidget(UiButton.builder(Text.component("colors.darkmusic.visual_picker"), b -> {
            spectrum = true;
            rebuildWidgets();
        }).bounds(x, 36, (w - 4) / 2, 20).build());
        visualMode.active = !spectrum;
        var rgbMode = addRenderableWidget(UiButton.builder(net.minecraft.network.chat.Component.literal("RGB"), b -> {
            spectrum = false;
            rebuildWidgets();
        }).bounds(x + (w + 4) / 2, 36, (w - 4) / 2, 20).build());
        rgbMode.active = spectrum;
        int[] palette = {0xB8A8FA, 0xFF6B9D, 0xFFAD66, 0xFFE28A, 0x66DDB8, 0x64B5F6,
            0x111420, 0x263547, 0x5B416F, 0x367B78, 0xFFFFFF, 0x000000};
        for (int i = 0; i < palette.length; i++) {
            final int value = palette[i];
            var button = addRenderableWidget(UiButton.builder(net.minecraft.network.chat.Component.empty(), b -> {
                update(value);
                rebuildWidgets();
            }).bounds(x + i % 6 * (w / 6), y + i / 6 * 20, w / 6 - 4, 18).build());
            button.swatch(() -> value, () -> value);
            Help.tip(button, String.format(Locale.ROOT, "#%06X", value));
        }
        String[] keys = {"red", "green", "blue"};
        if (spectrum) addRenderableWidget(new ColorSpectrum(x, 104, w, color, this::update));
        else for (int i = 0; i < 3; i++) {
            int shift = 16 - i * 8;
            String key = keys[i];
            var slider = addRenderableWidget(new ValueSlider(x, 104 + i * 28, w,
                (color >> shift & 255) / 255.0,
                v -> Text.get("colors.darkmusic." + key) + ": " + (color >> shift & 255),
                v -> update((color & ~(255 << shift)) | ((int) Math.round(v * 255) << shift)), () -> {
            }, 255));
            slider.plain(true);
        }
        hex = addRenderableWidget(new UiInput(font, x, 196, w - 90, 20, Text.component("colors.darkmusic.hex_help")));
        hex.setMaxLength(7);
        hex.setValue(String.format(Locale.ROOT, "#%06X", color));
        hex.setResponder(value -> {
            if (syncing) return;
            Integer parsed = VisualColors.parse(value);
            hex.invalid(parsed == null);
            if (parsed != null) {
                update(parsed);
                rebuildWidgets();
            }
        });
        addRenderableWidget(UiButton.builder(Text.component("colors.darkmusic.default"), b -> {
            update(defaultColor);
            rebuildWidgets();
        }).bounds(x + w - 86, 196, 86, 20).build());
    }

    private void update(int value) {
        color = value & 0xFFFFFF;
        change.accept(color);
        if (hex != null) {
            syncing = true;
            hex.setValue(String.format(Locale.ROOT, "#%06X", color));
            hex.invalid(false);
            syncing = false;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.SETTINGS);
        int w = Math.min(360, width - 24), x = (width - w) / 2;
        g.fill(x, 222, x + w, Math.min(height - 8, 230), 0xFF000000 | color);
        super.extractRenderState(g, mx, my, delta);
    }
}
