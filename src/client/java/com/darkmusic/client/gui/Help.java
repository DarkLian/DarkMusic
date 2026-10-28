package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class Help {
    private static final Map<AbstractWidget, Hover> HOVERS = new WeakHashMap<>();
    // Set once at client start; hover help follows the live "show hover info" setting.
    private static volatile Supplier<Settings> settings = () -> null;

    private Help() {
    }

    public static String withKeys(String label, KeyMapping... bindings) {
        String keys = Arrays.stream(bindings).filter(key -> key != null && !key.isUnbound())
            .map(key -> key.getTranslatedKeyMessage().getString()).collect(Collectors.joining(" / "));
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

    public static void settings(Supplier<Settings> source) {
        settings = source;
    }

    static boolean hasInfo(AbstractWidget widget) {
        return HOVERS.containsKey(widget);
    }

    public static void extract(AbstractWidget widget, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var hover = HOVERS.get(widget);
        var current = settings.get();
        if (hover == null || current == null || !current.showHoverInfo) return;
        var mc = Minecraft.getInstance();
        boolean mouse = widget.isHovered();
        if (!mouse && !(widget.isFocused() && mc.getLastInputType().isKeyboard())) return;
        int width = Math.max(1, Math.min(320, g.guiWidth() - 20));
        int maximumLines = Math.max(1, (g.guiHeight() - 20) / (mc.font.lineHeight + 1));
        var language = Language.getInstance();
        if (hover.width != width || hover.maximumLines != maximumLines || hover.language != language) {
            var text = new StringBuilder();
            for (var line : hover.tooltip.toCharSequence(mc)) {
                if (!text.isEmpty()) text.append('\n');
                line.accept((index, style, codepoint) -> {
                    text.appendCodePoint(codepoint);
                    return true;
                });
            }
            var lines = new ArrayList<>(mc.font.split(Component.literal(text.toString()), width));
            if (lines.size() > maximumLines) {
                lines.subList(maximumLines, lines.size()).clear();
                lines.set(maximumLines - 1, Component.literal("…").getVisualOrderText());
            }
            hover.lines = List.copyOf(lines);
            hover.width = width;
            hover.maximumLines = maximumLines;
            hover.language = language;
        }
        int x = mouse ? mouseX : widget.getX() + widget.getWidth() / 2;
        int y = mouse ? mouseY : widget instanceof RowList rows ? rows.tooltipY() : widget.getBottom();
        g.setTooltipForNextFrame(mc.font, hover.lines, Help::position, x, y, true);
    }

    public static Vector2ic position(int screenWidth, int screenHeight, int x, int y, int width, int height) {
        int px = x + 10, py = y + 12;
        if (px + width + 4 > screenWidth) px = x - width - 10;
        if (py + height + 4 > screenHeight) py = y - height - 10;
        return new Vector2i(Math.clamp(px, 4, Math.max(4, screenWidth - width - 4)),
            Math.clamp(py, 4, Math.max(4, screenHeight - height - 4)));
    }

    private static final class Hover {
        final Tooltip tooltip;
        int width = -1, maximumLines;
        Language language;
        List<FormattedCharSequence> lines = List.of();

        Hover(Tooltip tooltip) {
            this.tooltip = tooltip;
        }
    }
}
