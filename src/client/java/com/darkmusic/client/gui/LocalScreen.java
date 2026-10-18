package com.darkmusic.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Prevents delayed local UI work from restoring a screen after death or a world transition.
 */
public abstract class LocalScreen extends Screen {
    protected final HorizontalText longText = new HorizontalText();
    private final Screen returnTo;
    private final Object openedIn;

    protected LocalScreen(Component title, Screen parent) {
        super(title);
        returnTo = parent;
        openedIn = minecraft.level;
    }

    protected final boolean isCurrent() {
        return minecraft.gui.screen() == this && minecraft.level == openedIn && (minecraft.player == null || minecraft.player.isAlive());
    }

    protected final void show(Screen next) {
        if (isCurrent()) minecraft.gui.setScreen(next);
    }

    /**
     * Preserve native editing state when a responsive rebuild retains the same draft.
     */
    @Override
    protected void rebuildWidgets() {
        var editing = children().stream().filter(c -> c instanceof UiInput).map(c -> (UiInput) c)
            .map(c -> new EditorState(c.getMessage().getString(), c.getValue(), c.getCursorPosition(), c.selectionEnd(), c.isFocused())).toList();
        var focused = getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget w ? w.getMessage() : null;
        super.rebuildWidgets();
        for (var child : children())
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible && widget.active) {
                if (focused != null && focused.equals(widget.getMessage())) setFocused(widget);
                if (widget instanceof UiInput input) for (var saved : editing)
                    if (saved.label().equals(input.getMessage().getString()) && saved.value().equals(input.getValue())) {
                        input.setCursorPosition(saved.cursor());
                        input.setHighlightPos(saved.end());
                        if (saved.focused()) setFocused(input);
                    }
            }
    }

    private record EditorState(String label, String value, int cursor, int end, boolean focused) {
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (longText.scroll(x, y, dx, dy, HorizontalText.shiftDown())) return true;
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public void removed() {
        for (var child : children()) {
            if (child instanceof RowList rows) rows.cancelDrag();
            else if (child instanceof ScrollBar bar) bar.cancel();
            else if (child instanceof ToolCards cards) cards.setFocused(false);
        }
        var app = com.darkmusic.client.DarkMusicClient.INSTANCE;
        if (app != null) app.save();
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    @Override
    public final void onClose() {
        if (isCurrent()) minecraft.gui.setScreen(returnTo);
    }
}
