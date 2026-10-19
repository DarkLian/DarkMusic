package com.darkmusic.client.gui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public final class Help {
    private static final java.util.Map<AbstractWidget, Hover> HOVERS = new java.util.WeakHashMap<>();

    private Help() {
    }

    public static String withKeys(String label, net.minecraft.client.KeyMapping... bindings) {
        String keys = java.util.Arrays.stream(bindings).filter(key -> key != null && !key.isUnbound())
            .map(key -> key.getTranslatedKeyMessage().getString()).collect(java.util.stream.Collectors.joining(" / "));
        return keys.isEmpty() ? label : label + " (" + keys + ")";
    }

    public static <T extends AbstractWidget> T tip(T widget, String explanation) {
        widget.setTooltip(Tooltip.create(Component.literal(explanation)));
        return widget;
    }

    /**
     * Keep native tooltip narration; only DarkMusic widgets replace passive visual scheduling.
     */
    public static void remember(AbstractWidget widget, Tooltip tooltip) {
        if (tooltip == null) HOVERS.remove(widget);
        else {
            var previous = HOVERS.get(widget);
            if (previous == null || previous.tooltip != tooltip) HOVERS.put(widget, new Hover(tooltip));
        }
    }

    static boolean hasInfo(AbstractWidget widget) {
        return HOVERS.containsKey(widget);
    }

    public static void extract(AbstractWidget widget, net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var app = com.darkmusic.client.DarkMusicClient.INSTANCE;
        var hover = HOVERS.get(widget);
        if (hover == null || app == null || !app.settings.showHoverInfo) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        boolean mouse = widget.isHovered();
        if (!mouse && !(widget.isFocused() && mc.getLastInputType().isKeyboard())) return;
        int width = Math.max(1, Math.min(320, g.guiWidth() - 20));
        int maximumLines = Math.max(1, (g.guiHeight() - 20) / (mc.font.lineHeight + 1));
        var language = net.minecraft.locale.Language.getInstance();
        if (hover.width != width || hover.maximumLines != maximumLines || hover.language != language) {
            var text = new StringBuilder();
            for (var line : hover.tooltip.toCharSequence(mc)) {
                if (!text.isEmpty()) text.append('\n');
                line.accept((index, style, codepoint) -> {
                    text.appendCodePoint(codepoint);
                    return true;
                });
            }
            var lines = new java.util.ArrayList<>(mc.font.split(Component.literal(text.toString()), width));
            if (lines.size() > maximumLines) {
                lines.subList(maximumLines, lines.size()).clear();
                lines.set(maximumLines - 1, Component.literal("…").getVisualOrderText());
            }
            hover.lines = java.util.List.copyOf(lines);
            hover.width = width;
            hover.maximumLines = maximumLines;
            hover.language = language;
        }
        int x = mouse ? mouseX : widget.getX() + widget.getWidth() / 2;
        int y = mouse ? mouseY : widget instanceof RowList rows ? rows.tooltipY() : widget.getBottom();
        g.setTooltipForNextFrame(mc.font, hover.lines, Help::position, x, y, true);
    }

    public static org.joml.Vector2ic position(int screenWidth, int screenHeight, int x, int y, int width, int height) {
        int px = x + 10, py = y + 12;
        if (px + width + 4 > screenWidth) px = x - width - 10;
        if (py + height + 4 > screenHeight) py = y - height - 10;
        return new org.joml.Vector2i(Math.clamp(px, 4, Math.max(4, screenWidth - width - 4)),
            Math.clamp(py, 4, Math.max(4, screenHeight - height - 4)));
    }

    private static final class Hover {
        final Tooltip tooltip;
        int width = -1, maximumLines;
        net.minecraft.locale.Language language;
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = java.util.List.of();

        Hover(Tooltip tooltip) {
            this.tooltip = tooltip;
        }
    }
}
