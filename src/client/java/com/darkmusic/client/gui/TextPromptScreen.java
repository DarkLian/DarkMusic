package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public final class TextPromptScreen extends LocalScreen {
    private final Screen parent;
    private final String initial;
    private final Consumer<String> accept;
    private EditBox text;
    private String error = "";
    private int left, top, panelWidth;

    public TextPromptScreen(Screen parent, String label, String initial, Consumer<String> accept) {
        super(Component.literal(label), parent);
        this.parent = parent;
        this.initial = initial;
        this.accept = accept;
    }

    @Override
    protected void init() {
        String draft = text == null ? initial : text.getValue();
        panelWidth = Math.min(390, width - 24);
        left = (width - panelWidth) / 2;
        top = Math.max(32, (height - 140) / 2);
        text = addRenderableWidget(new UiInput(font, left + 12, top + 38, panelWidth - 24, 20, title));
        text.setMaxLength(100);
        text.setValue(draft);
        setInitialFocus(text);
        UiButton.style(addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.save"), b -> {
            try {
                if (text.getValue().isBlank())
                    throw new IllegalArgumentException(Text.get("text.darkmusic.enter_a_name"));
                accept.accept(text.getValue().strip());
                show(parent);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
        }).bounds(left + 12, top + 70, (panelWidth - 28) / 2, 20).build()), UiButton.Style.PRIMARY);
        addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.cancel"), b -> onClose()).bounds(left + 16 + (panelWidth - 28) / 2, top + 70, (panelWidth - 28) / 2, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.panel(g, left, top, panelWidth, 130);
        UiIcon.EDIT.draw(g, left + 12, top + 10, 12, UiTheme.ACCENT);
        longText.draw(g, font, "title", title.getString(), left + 32, top + 12, panelWidth - 44, UiTheme.TEXT);
        longText.draw(g, font, "error", error, left + 12, top + 104, panelWidth - 24, UiTheme.WARNING);
        super.extractRenderState(g, x, y, d);
    }
}
