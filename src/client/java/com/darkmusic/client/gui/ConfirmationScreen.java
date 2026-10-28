package com.darkmusic.client.gui;

import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * A shared, explicit confirmation for destructive metadata/settings actions.
 */
public final class ConfirmationScreen extends LocalScreen {
    private final Screen completionScreen;
    private final Component explanation, confirmLabel;
    private final Supplier<CompletableFuture<Void>> action;
    private List<FormattedCharSequence> lines;
    private Button confirm, cancel;
    private ScrollBar scrollbar;
    private int left, top, panelWidth, panelHeight, scroll, bodyHeight;
    private boolean busy;
    private boolean destructive = true;

    public ConfirmationScreen nonDestructive() {
        destructive = false;
        return this;
    }

    private String error = "";

    public ConfirmationScreen(Screen parent, Component title, Component explanation, Component confirmLabel, Supplier<CompletableFuture<Void>> action) {
        this(parent, title, explanation, confirmLabel, action, parent);
    }

    public ConfirmationScreen(Screen parent, Component title, Component explanation, Component confirmLabel, Supplier<CompletableFuture<Void>> action, Screen completionScreen) {
        super(title, parent);
        this.completionScreen = completionScreen;
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
        cancel = addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.cancel"), b -> onClose()).bounds(left + 10, top + panelHeight - 30, half, 20).build());
        confirm = addRenderableWidget(UiButton.builder(confirmLabel, b -> submit()).bounds(left + 18 + half, top + panelHeight - 30, half, 20).build());
        if (destructive) UiButton.style(confirm, UiButton.Style.DESTRUCTIVE);
        setInitialFocus(cancel);
        confirm.active = cancel.active = !busy;
        scrollbar = addRenderableWidget(new ScrollBar(() -> scroll, value -> scroll = value));
        scrollbar.configure(left + panelWidth - 10, top + 30, bodyHeight, lines.size() * 11, bodyHeight);
    }

    private void submit() {
        if (busy) return;
        busy = true;
        error = "";
        confirm.active = cancel.active = false;
        try {
            action.get().whenComplete((unused, failure) -> minecraft.execute(() -> {
                busy = false;
                if (failure == null) show(completionScreen);
                else {
                    Throwable cause = failure;
                    while (cause instanceof CompletionException && cause.getCause() != null)
                        cause = cause.getCause();
                    error = Text.get("reset.darkmusic.failed", Text.failure(cause, "error.operation"));
                    confirm.active = cancel.active = true;
                    LoggerFactory.getLogger("darkmusic").warn("Confirmed action failed", failure);
                }
            }));
        } catch (Exception e) {
            busy = false;
            error = Text.get("reset.darkmusic.failed", Text.failure(e, "error.operation"));
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
        UiTheme.background(g, width, height);
        UiTheme.panel(g, left, top, panelWidth, panelHeight);
        UiIcon.WARNING.draw(g, left + 12, top + 10, 12, UiTheme.ERROR);
        longText.draw(g, font, "title", title.getString(), left + 32, top + 12, panelWidth - 44, UiTheme.TEXT);
        g.enableScissor(left + 12, top + 30, left + panelWidth - 12, top + 30 + bodyHeight);
        for (int i = 0; i < lines.size(); i++)
            g.text(font, lines.get(i), left + 12, top + 30 + i * 11 - scroll, UiTheme.SECONDARY);
        g.disableScissor();
        longText.draw(g, font, "status", busy ? Text.get("reset.darkmusic.working") : error, left + 12, top + panelHeight - 50, panelWidth - 24, busy ? UiStatus.color(UiStatus.Tone.INFO) : UiTheme.ERROR);
        super.extractRenderState(g, x, y, delta);
    }
}
