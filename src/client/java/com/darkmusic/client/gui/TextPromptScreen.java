package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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

    public TextPromptScreen(Screen parent, String label, String initial, Consumer<String> accept) {
        super(Component.literal(label), parent);
        this.parent = parent;
        this.initial = initial;
        this.accept = accept;
    }

    @Override
    protected void init() {
        text = addRenderableWidget(new EditBox(font, 12, 44, width - 24, 20, title));
        text.setMaxLength(100);
        text.setValue(initial);
        setInitialFocus(text);
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.save"), b -> {
            try {
                if (text.getValue().isBlank())
                    throw new IllegalArgumentException(Text.get("text.darkmusic.enter_a_name"));
                accept.accept(text.getValue().strip());
                show(parent);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
        }).bounds(12, 76, 72, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.cancel"), b -> onClose()).bounds(90, 76, 72, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 20, 0xFFB5B0FF);
        g.text(font, error, 12, 108, 0xFFFFBC80);
        super.extractRenderState(g, x, y, d);
    }
}
