package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;

/**
 * Color preferences save immediately, like the rest of Settings.
 */
public final class ColorsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final VisualColors target;
    private EditBox start, end;
    private int fieldsY, swatchY, editorWidth, previewX, previewY, previewWidth;
    private boolean previewOnly;
    private int presetPage, pageY, pageCount;
    private VisualColors.Preset shownPreset;
    private String draftStart, draftEnd;

    public ColorsScreen(DarkMusicClient app, Screen parent, boolean spectrum) {
        super(Text.component(spectrum ? "colors.darkmusic.spectrum" : "colors.darkmusic.progress"), parent);
        this.app = app;
        this.target = spectrum ? app.settings.spectrumColors : app.settings.progressColors;
    }

    public ColorsScreen(DarkMusicClient app, Screen parent, VisualColors target, String titleKey) {
        super(Text.component(titleKey), parent);
        this.app = app;
        this.target = target;
    }

    private VisualColors colors() {
        return target;
    }

    @Override
    protected void init() {
        if (start != null && shownPreset == colors().preset) {
            draftStart = start.getValue();
            draftEnd = end.getValue();
        } else {
            draftStart = String.format(Locale.ROOT, "#%06X", colors().first());
            draftEnd = String.format(Locale.ROOT, "#%06X", colors().last());
        }
        shownPreset = colors().preset;
        boolean wide = width >= 700;
        editorWidth = wide ? Math.min(500, (width - 40) / 2) : width - 24;
        int columns = 3, gap = 4, cell = (editorWidth - (columns - 1) * gap) / columns;
        int tileHeight = height < 330 ? 20 : 30, gridTop = height < 330 ? 34 : 52;
        var presets = VisualColors.Preset.values();
        int rows = Math.max(1, Math.min(4, (height - gridTop - 130) / (tileHeight + gap)));
        int perPage = rows * columns;
        pageCount = (presets.length + perPage - 1) / perPage;
        presetPage = Math.clamp(presetPage, 0, pageCount - 1);
        for (int i = 0; i < perPage && presetPage * perPage + i < presets.length; i++) {
            var preset = presets[presetPage * perPage + i];
            String name = Text.get("colors.darkmusic." + preset.name().toLowerCase(Locale.ROOT));
            var tile = addRenderableWidget(UiButton.builder(net.minecraft.network.chat.Component.literal(name), button -> {
                var selected = colors();
                if (preset == VisualColors.Preset.CUSTOM) {
                    selected.start = selected.first();
                    selected.end = selected.last();
                }
                selected.preset = preset;
                shownPreset = null;
                app.save();
                rebuildWidgets();
            }).bounds(12 + i % columns * (cell + gap), gridTop + i / columns * (tileHeight + gap), cell, tileHeight).build());
            UiButton.selected(tile, () -> colors().preset == preset);
            tile.swatch(() -> preset == VisualColors.Preset.CUSTOM ? colors().start : preset.start, () -> preset == VisualColors.Preset.CUSTOM ? colors().end : preset.end);
        }
        pageY = gridTop + rows * (tileHeight + gap) + 2;
        var previous = addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.previous"), b -> {
            presetPage--;
            rebuildWidgets();
        }).bounds(12, pageY, 24, 20).build());
        UiButton.icon(previous, UiIcon.PREVIOUS, true);
        previous.active = presetPage > 0;
        var next = addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.next"), b -> {
            presetPage++;
            rebuildWidgets();
        }).bounds(40, pageY, 24, 20).build());
        UiButton.icon(next, UiIcon.NEXT, true);
        next.active = presetPage < pageCount - 1;
        addRenderableWidget(UiButton.builder(Text.component("colors.darkmusic.default"), b -> {
            if (target == app.settings.hudBackgroundColors) {
                target.preset = VisualColors.Preset.CUSTOM;
                target.start = target.end = 0x111420;
            } else target.preset = VisualColors.Preset.OCEAN_DRIVE;
            shownPreset = null;
            presetPage = 0;
            app.save();
            rebuildWidgets();
        }).bounds(12 + editorWidth - 88, pageY, 88, 20).build());
        fieldsY = pageY + 42;
        int half = (editorWidth - 4) / 2;
        start = addRenderableWidget(new UiInput(font, 12, fieldsY + 12, half, 20, Text.component("colors.darkmusic.start")));
        end = addRenderableWidget(new UiInput(font, 16 + half, fieldsY + 12, half, 20, Text.component("colors.darkmusic.end")));
        start.setMaxLength(7);
        end.setMaxLength(7);
        start.setValue(draftStart);
        end.setValue(draftEnd);
        start.setResponder(value -> custom());
        end.setResponder(value -> custom());
        Help.tip(start, Text.get("colors.darkmusic.hex_help"));
        Help.tip(end, Text.get("colors.darkmusic.hex_help"));
        swatchY = fieldsY + 38;
        addRenderableWidget(UiButton.builder(Text.component("colors.darkmusic.pick_start"), b -> pick(true))
            .bounds(12, swatchY + 30, half, 20).build());
        addRenderableWidget(UiButton.builder(Text.component("colors.darkmusic.pick_end"), b -> pick(false))
            .bounds(16 + half, swatchY + 30, half, 20).build());
        previewX = wide ? editorWidth + 28 : 12;
        previewWidth = wide ? width - previewX - 12 : width - 24;
        previewY = 36;
        if (!wide) {
            if (previewOnly) for (var child : children())
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) widget.visible = false;
            UiButton.icon(addRenderableWidget(UiButton.builder(Text.component(previewOnly ? "phase2.settings_form" : "phase2.preview"), b -> {
                previewOnly = !previewOnly;
                rebuildWidgets();
            }).bounds(width - 116, 6, 104, 24).build()), UiIcon.PREVIEW, false);
        }
    }

    private void pick(boolean first) {
        int defaultColor = target == app.settings.hudBackgroundColors ? 0x111420
            : first ? VisualColors.Preset.OCEAN_DRIVE.start : VisualColors.Preset.OCEAN_DRIVE.end;
        show(new ColorPickerScreen(this, first ? colors().first() : colors().last(), defaultColor, color -> {
            var value = colors();
            int a = value.first(), b = value.last();
            value.start = first ? color : a;
            value.end = first ? b : color;
            value.preset = VisualColors.Preset.CUSTOM;
            shownPreset = null;
            app.save();
        }));
    }

    private void custom() {
        Integer first = VisualColors.parse(start.getValue()), last = VisualColors.parse(end.getValue());
        if (first == null || last == null) return;
        var selected = colors();
        selected.start = first;
        selected.end = last;
        selected.preset = VisualColors.Preset.CUSTOM;
        shownPreset = selected.preset;
        app.save();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        longText.draw(g, font, "color_title", title.getString() + ": " + Text.get("colors.darkmusic." + colors().preset.name().toLowerCase(Locale.ROOT)), 12, 10, width - (width < 700 ? 140 : 24), UiTheme.ACCENT);
        if (width >= 700 || !previewOnly) {
            if (height >= 330) UiTheme.section(g, font, Text.get("ui.darkmusic.presets"), 12, 30, editorWidth);
            g.text(font, (presetPage + 1) + " / " + pageCount, 72, pageY + 6, UiTheme.SECONDARY);
            UiTheme.section(g, font, Text.get("ui.darkmusic.custom_colors"), 12, fieldsY - 20, editorWidth);
            g.text(font, Text.get("colors.darkmusic.start"), 12, fieldsY, UiTheme.SECONDARY);
            g.text(font, Text.get("colors.darkmusic.end"), 16 + (editorWidth - 4) / 2, fieldsY, UiTheme.SECONDARY);
            UiTheme.gradient(g, 12, swatchY, editorWidth, 8, colors().color(0), colors().color(1));
            boolean valid = VisualColors.parse(start.getValue()) != null && VisualColors.parse(end.getValue()) != null;
            ((UiInput) start).invalid(VisualColors.parse(start.getValue()) == null);
            ((UiInput) end).invalid(VisualColors.parse(end.getValue()) == null);
            longText.draw(g, font, "colors_help", Text.get(valid ? "colors.darkmusic.mixed_help" : "colors.darkmusic.invalid"), 12, swatchY + 13, editorWidth, valid ? UiTheme.SECONDARY : UiTheme.ERROR);
        }
        if (width >= 700 || previewOnly) {
            UiTheme.panel(g, previewX, previewY, previewWidth, height - previewY - 8);
            if (height - previewY > 50)
                g.text(font, Text.get("ui.darkmusic.live_preview"), previewX + 8, previewY + 7, UiTheme.ACCENT);
            int inset = height - previewY > 50 ? 22 : 4;
            g.enableScissor(previewX + 2, previewY + inset, previewX + previewWidth - 2, height - 10);
            NowPlayingHud.renderPreview(app, g, previewX + 8, previewY + inset, previewWidth - 16, height - previewY - inset - 12, 1, longText);
            g.disableScissor();
        }
        super.extractRenderState(g, mx, my, delta);
    }
}
