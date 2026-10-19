package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.BooleanSupplier;

final class FavoriteButton extends AbstractWidget {
    private final BooleanSupplier liked;
    private final Runnable action;
    private long likedAt;
    private long transitionAt = System.nanoTime();
    private float emphasis;

    FavoriteButton(int x, int y, BooleanSupplier liked, Runnable action) {
        super(x, y, 22, 20, Text.component("library.darkmusic.favorite"));
        this.liked = liked;
        this.action = action;
    }

    private void toggle() {
        if (!active) return;
        action.run();
        if (liked.getAsBoolean()) likedAt = System.nanoTime();
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        toggle();
    }

    @Override
    public boolean keyPressed(KeyEvent key) {
        if (isFocused() && active && (key.key() == 32 || key.isConfirmation())) {
            toggle();
            return true;
        }
        return false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        boolean favorite = liked.getAsBoolean();
        setMessage(Text.component(favorite ? "library.darkmusic.unfavorite" : "library.darkmusic.favorite"));
        boolean highlighted = active && isHoveredOrFocused();
        long now = System.nanoTime();
        float step = Math.min(1, (now - transitionAt) / 120_000_000f);
        transitionAt = now;
        emphasis += Math.clamp((highlighted ? 1 : 0) - emphasis, -step, step);
        g.fill(getX(), getY(), getRight(), getBottom(), active ? UiTheme.mix(UiTheme.RAISED, UiTheme.HOVER, emphasis) : UiTheme.SURFACE);
        g.outline(getX(), getY(), width, height, !active ? UiTheme.RAISED : highlighted ? UiTheme.ACCENT : UiTheme.BORDER);
        double t = (System.nanoTime() - likedAt) / 320_000_000.0;
        int size = 14 + (favorite && t >= 0 && t < 1 ? (int) Math.round(4 * Math.sin(Math.PI * t)) : 0);
        (favorite ? UiIcon.HEART_FILLED : UiIcon.HEART).draw(g, getX() + (width - size) / 2, getY() + (height - size) / 2, size, active ? (favorite ? UiTheme.ACCENT : UiTheme.SECONDARY) : UiTheme.MUTED);
        if (!active) g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, 0x55111420);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
