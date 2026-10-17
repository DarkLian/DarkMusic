package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;

/**
 * Color preferences save immediately, like the rest of Settings.
 */
public final class ColorsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final boolean spectrum;
    private EditBox start, end;
    private int fieldsY, swatchY;

    public ColorsScreen(DarkMusicClient app, Screen parent, boolean spectrum) {
        super(Text.component(spectrum ? "colors.darkmusic.spectrum" : "colors.darkmusic.progress"), parent);
        this.app = app;
        this.spectrum = spectrum;
    }

    private VisualColors colors() {
        return spectrum ? app.settings.spectrumColors : app.settings.progressColors;
    }

    @Override
    protected void init() {
        int columns = width >= 600 ? 4 : 3, gap = 4, cell = (width - 24 - (columns - 1) * gap) / columns;
        var presets = VisualColors.Preset.values();
        for (int i = 0; i < presets.length; i++) {
            var preset = presets[i];
            String name = Text.get("colors.darkmusic." + preset.name().toLowerCase(Locale.ROOT));
            addRenderableWidget(Button.builder(net.minecraft.network.chat.Component.literal(name), button -> {
                var selected = colors();
                if (preset == VisualColors.Preset.CUSTOM) {
                    selected.start = selected.first();
                    selected.end = selected.last();
                }
                selected.preset = preset;
                app.save();
                rebuildWidgets();
            }).bounds(12 + i % columns * (cell + gap), 32 + i / columns * 24, cell, 20).build());
        }
        fieldsY = 38 + ((presets.length + columns - 1) / columns) * 24;
        int half = (width - 28) / 2;
        start = addRenderableWidget(new EditBox(font, 12, fieldsY + 12, half, 20, Text.component("colors.darkmusic.start")));
        end = addRenderableWidget(new EditBox(font, 16 + half, fieldsY + 12, half, 20, Text.component("colors.darkmusic.end")));
        start.setMaxLength(7);
        end.setMaxLength(7);
        start.setValue(String.format(Locale.ROOT, "#%06X", colors().first()));
        end.setValue(String.format(Locale.ROOT, "#%06X", colors().last()));
        start.setResponder(value -> custom());
        end.setResponder(value -> custom());
        Help.tip(start, Text.get("colors.darkmusic.hex_help"));
        Help.tip(end, Text.get("colors.darkmusic.hex_help"));
        swatchY = fieldsY + 38;
    }

    private void custom() {
        Integer first = VisualColors.parse(start.getValue()), last = VisualColors.parse(end.getValue());
        if (first == null || last == null) return;
        var selected = colors();
        selected.start = first;
        selected.end = last;
        selected.preset = VisualColors.Preset.CUSTOM;
        app.save();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xFF111420);
        longText.draw(g, font, "color_title", title.getString() + ": " + Text.get("colors.darkmusic." + colors().preset.name().toLowerCase(Locale.ROOT)), 12, 12, width - 24, 0xFFB5B0FF);
        g.text(font, Text.get("colors.darkmusic.start"), 12, fieldsY, 0xFFE0DDEE);
        g.text(font, Text.get("colors.darkmusic.end"), 16 + (width - 28) / 2, fieldsY, 0xFFE0DDEE);
        ColorStrip.draw(g, colors(), 12, swatchY, width - 24, 8, width - 24);
        boolean valid = VisualColors.parse(start.getValue()) != null && VisualColors.parse(end.getValue()) != null;
        longText.draw(g, font, "colors_help", Text.get(valid ? "colors.darkmusic.mixed_help" : "colors.darkmusic.invalid"), 12, swatchY + 13, width - 24, valid ? 0xFFAAAAC0 : 0xFFFFBC80);
        int previewY = swatchY + 29;
        if (height - previewY > 20) {
            g.enableScissor(12, previewY, width - 12, height - 8);
            NowPlayingHud.renderPreview(app, g, 16, previewY + 2, width - 32, height - previewY - 12, 1, longText);
            g.disableScissor();
        }
        super.extractRenderState(g, mx, my, delta);
    }
}
