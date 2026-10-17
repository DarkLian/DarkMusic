package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A shared, explicit confirmation for destructive metadata/settings actions.
 */
public final class ConfirmationScreen extends LocalScreen {
    private final Screen parent;
    private final Component explanation, confirmLabel;
    private final Supplier<CompletableFuture<Void>> action;
    private List<net.minecraft.util.FormattedCharSequence> lines;
    private Button confirm, cancel;
    private int left, top, panelWidth, panelHeight, scroll, bodyHeight;
    private boolean busy;
    private String error = "";

    public ConfirmationScreen(Screen parent, Component title, Component explanation, Component confirmLabel, Supplier<CompletableFuture<Void>> action) {
        super(title, parent);
        this.parent = parent;
        this.explanation = explanation;
        this.confirmLabel = confirmLabel;
        this.action = action;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(390, width - 24);
        left = (width - panelWidth) / 2;
        lines = font.split(explanation, panelWidth - 24);
        bodyHeight = Math.min(lines.size() * 11, Math.max(22, height - 140));
        panelHeight = bodyHeight + 96;
        top = (height - panelHeight) / 2;
        int half = (panelWidth - 28) / 2;
        cancel = addRenderableWidget(Button.builder(Text.component("text.darkmusic.cancel"), b -> onClose()).bounds(left + 10, top + panelHeight - 30, half, 20).build());
        confirm = addRenderableWidget(Button.builder(confirmLabel, b -> submit()).bounds(left + 18 + half, top + panelHeight - 30, half, 20).build());
        setInitialFocus(cancel);
        confirm.active = cancel.active = !busy;
    }

    private void submit() {
        if (busy) return;
        busy = true;
        error = "";
        confirm.active = cancel.active = false;
        try {
            action.get().whenComplete((unused, failure) -> minecraft.execute(() -> {
                busy = false;
                if (failure == null) show(parent);
                else {
                    Throwable cause = failure;
                    while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                        cause = cause.getCause();
                    error = Text.get("reset.darkmusic.failed", String.valueOf(cause.getMessage()));
                    confirm.active = cancel.active = true;
                    org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Confirmed action failed", failure);
                }
            }));
        } catch (Exception e) {
            busy = false;
            error = Text.get("reset.darkmusic.failed", String.valueOf(e.getMessage()));
            confirm.active = cancel.active = true;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !busy;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (x >= left && x <= left + panelWidth && y >= top + 30 && y < top + 30 + bodyHeight) {
            scroll = Math.clamp(scroll - (int) Math.round(dy * 22), 0, Math.max(0, lines.size() * 11 - bodyHeight));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xFF111420);
        g.fill(left, top, left + panelWidth, top + panelHeight, 0xFF1C2030);
        g.outline(left, top, panelWidth, panelHeight, 0xFF54506F);
        longText.draw(g, font, "title", title.getString(), left + 12, top + 12, panelWidth - 24, 0xFFB5B0FF);
        g.enableScissor(left + 12, top + 30, left + panelWidth - 12, top + 30 + bodyHeight);
        for (int i = 0; i < lines.size(); i++)
            g.text(font, lines.get(i), left + 12, top + 30 + i * 11 - scroll, 0xFFCCCCDD);
        g.disableScissor();
        longText.draw(g, font, "status", busy ? Text.get("reset.darkmusic.working") : error, left + 12, top + panelHeight - 50, panelWidth - 24, 0xFFFFBC80);
        super.extractRenderState(g, x, y, delta);
    }
}
