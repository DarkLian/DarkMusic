package com.darkmusic.client.gui;

import com.darkmusic.client.config.PresetNames;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

final class PresetDialogs {
    private PresetDialogs() {
    }

    static TextPromptScreen save(Screen parent, String title, Map<String, ?> presets, int limit, BiConsumer<String, Boolean> save) {
        return save(parent, title, presets, limit, PresetNames.MAX_LENGTH, save);
    }

    static TextPromptScreen save(Screen parent, String title, Map<String, ?> presets, int limit, int nameLimit, BiConsumer<String, Boolean> save) {
        TextPromptScreen[] prompt = new TextPromptScreen[1];
        prompt[0] = TextPromptScreen.validated(parent, Text.get(title), "", 256, input -> {
            String name = PresetNames.valid(input, nameLimit), existing = PresetNames.existing(presets, name);
            if (existing == null) {
                if (presets.size() >= limit) throw new IllegalArgumentException(Text.get("preset.limit", limit));
                save.accept(name, false);
                return true;
            }
            prompt[0].show(new ConfirmationScreen(prompt[0], Text.component("preset.replace_title"),
                Text.component("preset.replace_body", existing), Text.component("preset.replace"), () -> {
                save.accept(existing, true);
                return CompletableFuture.completedFuture(null);
            }, parent));
            return false;
        });
        return prompt[0];
    }
}
