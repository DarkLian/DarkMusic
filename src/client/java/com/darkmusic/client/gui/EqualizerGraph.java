package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.IntToDoubleFunction;

/**
 * Graphic EQ bands as vertical faders, so the whole curve is visible at once.
 * Drag or click a band; with focus, Left/Right choose a band, Up/Down adjust it, Home resets it.
 */
public final class EqualizerGraph extends AbstractWidget {
    public static final double RANGE = 12, STEP = .5;
    public static final int HEIGHT = 112;
    private static final int LABEL = 11;

    public interface Change {
        void set(int band, double db);
    }

    private final double[] frequencies;
    private final IntToDoubleFunction read;
    private final Change change;
    private final Runnable committed;
    private int focusedBand, draggingBand = -1;

    @Override
    public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    public EqualizerGraph(int x, int y, int width, double[] frequencies, IntToDoubleFunction read, Change change, Runnable committed) {
        super(x, y, width, HEIGHT, Text.component("audio.darkmusic.eq_graph"));
        this.frequencies = frequencies;
        this.read = read;
        this.change = change;
        this.committed = committed;
    }

    static String frequencyLabel(double hz) {
        return hz >= 1000 ? (hz % 1000 == 0 ? String.format(Locale.ROOT, "%.0fk", hz / 1000) : String.format(Locale.ROOT, "%.1fk", hz / 1000)) : String.format(Locale.ROOT, "%.0f", hz);
    }

    static String gainLabel(double db) {
        return String.format(Locale.ROOT, "%+.1f", Math.abs(db) < .05 ? 0 : db);
    }

    static double snap(double db) {
        return Math.clamp(Math.round(db / STEP) * STEP, -RANGE, RANGE);
    }

    private int plotTop() {
        return getY() + LABEL + 3;
    }

    private int plotBottom() {
        return getBottom() - LABEL - 3;
    }

    private int columnWidth() {
        return Math.max(1, (width - 8) / frequencies.length);
    }

    private int columnX(int band) {
        return getX() + 4 + band * columnWidth();
    }

    private int bandAt(double mouseX) {
        return Math.clamp((int) ((mouseX - getX() - 4) / columnWidth()), 0, frequencies.length - 1);
    }

    private int levelY(double db) {
        double fraction = (RANGE - Math.clamp(db, -RANGE, RANGE)) / (2 * RANGE);
        return plotTop() + (int) Math.round(fraction * (plotBottom() - plotTop()));
    }

    private double levelAt(double mouseY) {
        double fraction = (mouseY - plotTop()) / Math.max(1, plotBottom() - plotTop());
        return snap(RANGE - Math.clamp(fraction, 0, 1) * 2 * RANGE);
    }

    private void apply(int band, double db) {
        focusedBand = band;
        double next = snap(db);
        if (next != read.applyAsDouble(band)) change.set(band, next);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        draggingBand = bandAt(event.x());
        apply(draggingBand, levelAt(event.y()));
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (draggingBand >= 0) apply(draggingBand, levelAt(event.y()));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        draggingBand = -1;
        committed.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !isFocused()) return false;
        if (event.isLeft() || event.isRight()) {
            focusedBand = Math.clamp(focusedBand + (event.isLeft() ? -1 : 1), 0, frequencies.length - 1);
            return true;
        }
        double current = read.applyAsDouble(focusedBand);
        double next = switch (event.key()) {
            case 265 -> current + STEP;
            case 264 -> current - STEP;
            case 266 -> current + 3;
            case 267 -> current - 3;
            case 268 -> 0;
            default -> Double.NaN;
        };
        if (Double.isNaN(next)) return false;
        apply(focusedBand, next);
        committed.run();
        return true;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        var font = Minecraft.getInstance().font;
        g.fill(getX(), getY(), getRight(), getBottom(), UiTheme.BACKGROUND);
        g.outline(getX(), getY(), width, height, isFocused() ? UiTheme.ACCENT : UiTheme.BORDER);
        int zero = levelY(0);
        g.fill(getX() + 2, zero, getRight() - 2, zero + 1, UiTheme.BORDER);
        int hovered = isMouseOver(mx, my) ? bandAt(mx) : -1;
        int column = columnWidth();
        for (int band = 0; band < frequencies.length; band++) {
            double db = read.applyAsDouble(band);
            int x = columnX(band), center = x + column / 2;
            boolean emphasized = active && (band == hovered || band == draggingBand || isFocused() && band == focusedBand);
            int color = !active ? UiTheme.MUTED : emphasized ? UiTheme.TEXT : UiTheme.ACCENT;
            g.fill(center, plotTop(), center + 1, plotBottom(), UiTheme.RAISED);
            int level = levelY(db);
            int barWidth = Math.max(2, Math.min(10, column / 3));
            g.fill(center - barWidth / 2, Math.min(level, zero), center - barWidth / 2 + barWidth, Math.max(level, zero) + 1, active ? UiTheme.mix(UiTheme.SURFACE, color, .55f) : UiTheme.RAISED);
            g.fill(center - barWidth / 2 - 2, level - 1, center - barWidth / 2 + barWidth + 2, level + 2, color);
            String gain = gainLabel(db), hz = frequencyLabel(frequencies[band]);
            if (font.width(gain) <= column)
                g.text(font, gain, center - font.width(gain) / 2 + 1, getY() + 3, emphasized ? UiTheme.TEXT : UiTheme.SECONDARY);
            g.text(font, hz, center - font.width(hz) / 2 + 1, getBottom() - LABEL, emphasized ? UiTheme.TEXT : UiTheme.MUTED);
        }
        g.text(font, "+12", getRight() - 4 - font.width("+12"), plotTop() - 1, UiTheme.MUTED);
        g.text(font, "−12 dB", getRight() - 4 - font.width("−12 dB"), plotBottom() - font.lineHeight + 1, UiTheme.MUTED);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(Text.get("audio.darkmusic.eq_graph") + ", "
            + frequencyLabel(frequencies[focusedBand]) + " Hz " + gainLabel(read.applyAsDouble(focusedBand)) + " dB"));
        output.add(NarratedElementType.USAGE, Text.component("audio.darkmusic.eq_graph.help"));
    }
}
