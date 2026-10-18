package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Read-only tool status, with one shared scroll surface and no fictional per-tool actions.
 */
public final class ToolCards extends AbstractWidget {
    @Override
    public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    private List<String> versions = List.of();
    private int scroll;
    private final ScrollBar scrollbar = new ScrollBar(() -> this.scroll, value -> scroll = value);
    private final HorizontalText horizontal = new HorizontalText();
    private final String[] names = {"yt-dlp", "FFmpeg", "FFprobe", Text.get("phase2.optional_runtime")};

    public ToolCards(int x, int y, int width, int height) {
        super(x, y, width, height, Text.component("ui.darkmusic.dependencies"));
    }

    public void versions(List<String> values) {
        versions = List.copyOf(values);
    }

    private int columns() {
        return width >= 600 ? 2 : 1;
    }

    private void move(int delta) {
        scroll = Math.clamp(scroll + delta, 0, Math.max(0, (4 / columns()) * 64 - height));
    }

    @Override
    public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean twice) {
        if (scrollbar.hit(event.x(), event.y())) scrollbar.onClick(event, twice);
    }

    @Override
    protected void onDrag(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        scrollbar.drag(event.y());
    }

    @Override
    public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
        scrollbar.cancel();
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) scrollbar.cancel();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!isMouseOver(x, y)) return false;
        if (horizontal.scroll(x, y, dx, dy, HorizontalText.shiftDown())) return true;
        move((int) Math.round(-dy * 32));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isFocused() && (event.key() == 262 || event.key() == 263)) {
            boolean moved = false;
            for (int i = 0; i < 4; i++) moved |= horizontal.pan("version" + i, event.key() == 262 ? 24 : -24);
            return moved;
        }
        if (!isFocused() || event.key() != 264 && event.key() != 265) return false;
        move(event.key() == 264 ? 32 : -32);
        return true;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        var font = Minecraft.getInstance().font;
        horizontal.begin();
        g.enableScissor(getX(), getY(), getRight(), getBottom());
        int columns = columns(), cell = (width - 12 - (columns - 1) * 8) / columns;
        for (int i = 0; i < 4; i++) {
            int px = getX() + i % columns * (cell + 8), py = getY() + i / columns * 64 - scroll;
            UiTheme.panel(g, px, py, cell, 56);
            UiIcon.TOOLS.draw(g, px + 10, py + 11, 14, UiTheme.ACCENT);
            g.text(font, names[i], px + 32, py + 10, UiTheme.TEXT);
            String status = i < versions.size() ? versions.get(i) : Text.get("download.darkmusic.tools_unchecked");
            horizontal.draw(g, font, "version" + i, status, px + 32, py + 29, cell - 42, UiTheme.SECONDARY);
        }
        g.disableScissor();
        scrollbar.configure(getRight() - 9, getY(), height, (4 / columns) * 64, height);
        scrollbar.extractRenderState(g, x, y, delta);
        if (isFocused()) g.outline(getX(), getY(), width, height, UiTheme.ACCENT);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        setMessage(Component.literal(String.join(", ", names) + ": " + String.join(", ", versions)));
        defaultButtonNarrationText(output);
    }
}
