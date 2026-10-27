package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.SpectrumMesh;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.*;
import java.util.function.IntToDoubleFunction;

/**
 * Device-clock measurements only; never consumes the synthetic HUD settings preview.
 */
public final class AudioAnalyzerScreen extends AudioPage {
    private AudioComparison.Mode mode;
    private boolean enabled = true, spectrum = true, waveform = true, response = true, before = true, after = true, bypass, matched;
    private final List<Graph> graphs = new ArrayList<>();
    private final SpectrumMesh mesh = new SpectrumMesh();

    public AudioAnalyzerScreen(DarkMusicClient app, Screen parent, AudioComparison.Mode mode) {
        super(app, parent, "analyzer.title");
        this.mode = mode;
    }

    private void configure() {
        updatePanels();
        app.playback.comparison(new AudioComparison(mode, bypass, matched));
    }

    private void updatePanels() {
        int mask = enabled ? AudioDiagnostics.METERS : 0;
        if (enabled && (before || after)) for (var graph : graphs) {
            int y = contentY(graph.y);
            if (y + 80 < 36 || y > height - 12) continue;
            mask |= switch (graph.kind) {
                case 0, 4 -> response ? AudioDiagnostics.RESPONSE : 0;
                case 1 -> spectrum ? AudioDiagnostics.SPECTRUM : 0;
                case 2 -> waveform ? AudioDiagnostics.WAVEFORM : 0;
                default -> 0;
            };
        }
        var diagnostics = app.playback.diagnostics();
        if (diagnostics != null) diagnostics.panels(mask);
    }

    @Override
    protected void changed() {
        configure();
        rebuildWidgets();
    }

    @Override
    protected void populate() {
        graphs.clear();
        configure();
        section("analyzer.title");
        status(this::status);
        toggle("analyzer.enabled", () -> enabled, v -> enabled = v);
        choice("analyzer.mode", mode, AudioComparison.Mode.values(), v -> mode = v);
        info(() -> Text.get("analyzer.scope." + mode.name().toLowerCase(Locale.ROOT)));
        toggle("analyzer.bypass", () -> bypass, v -> bypass = v);
        toggle("analyzer.match", () -> matched, v -> matched = v);
        info("analyzer.chain");
        info("analyzer.latency_note");
        info(() -> {
            var s = snapshot();
            return s == null ? Text.get("analyzer.no_data") : Text.get("dsp.ir_status." + s.convolutionState)
                + " · " + Text.get("analyzer.protection_latency", s.latencyFrames);
        });
        info(() -> Text.get(app.settings.audio.neutral ? "analyzer.ao_bypassed" : "analyzer.ao_active") + " | " + Text.get(app.settings.environment.active() ? "analyzer.env_active" : "analyzer.env_bypassed"));
        section("analyzer.normalization");
        status(this::normalization);
        info("analyzer.album_scope");
        section("analyzer.meters");
        for (int stage = 0; stage < 5; stage++) {
            int meter = stage;
            status(() -> meter(meter), () -> clipping(meter) ? UiStatus.Tone.WARNING : null);
        }
        info(() -> {
            var s = snapshot();
            return s == null ? Text.get("analyzer.no_data") : units(Text.get("analyzer.dynamics", db(s.detectorDb), db(-s.reductionDb), db(-s.limiterReduction), db(amplitudeDb(s.truePeak))));
        });
        action("analyzer.reset", () -> {
            var d = app.playback.diagnostics();
            if (d != null) d.resetHolds();
        }).runs();
        section("analyzer.stereo");
        info(() -> {
            var s = snapshot();
            return s == null ? Text.get("analyzer.no_data") : s.channels == 1 ? Text.get("analyzer.mono") : units(Text.get("analyzer.correlation", Double.isNaN(s.correlation) ? Text.get("analyzer.na") : String.format(Locale.ROOT, "%+.3f", s.correlation), db(s.leftRms), db(s.rightRms)));
        });
        info("analyzer.correlation_help");
        graph("analyzer.stereo_legend", 3);
        section("analyzer.views");
        toggle("analyzer.before", () -> before, v -> before = v);
        toggle("analyzer.after", () -> after, v -> after = v);
        toggle("analyzer.response", () -> response, v -> response = v);
        if (response) {
            if (mode != AudioComparison.Mode.ENVIRONMENT) graph("analyzer.response_legend", 0);
            if (mode != AudioComparison.Mode.AUDIO_OPTIONS) graph("analyzer.environment_response", 4);
        }
        toggle("analyzer.spectrum", () -> spectrum, v -> spectrum = v);
        if (spectrum) graph("analyzer.spectrum_legend", 1);
        toggle("analyzer.waveform", () -> waveform, v -> waveform = v);
        if (waveform) graph("analyzer.waveform_legend", 2);
    }

    private void graph(String label, int kind) {
        // The legend line is drawn with color swatches in renderContent.
        cursor += 22;
        graphs.add(new Graph(cursor, kind, label));
        cursor += 98;
    }

    private static final String SEPARATOR = " · ";

    private int[] legendColors(int kind) {
        return switch (kind) {
            case 0 -> new int[]{UiTheme.ACCENT, UiTheme.WARNING, UiTheme.SUCCESS};
            case 4 -> new int[]{UiTheme.ACCENT};
            default -> new int[]{UiTheme.ACCENT, UiTheme.SUCCESS};
        };
    }

    private void legend(GuiGraphicsExtractor g, Graph graph, int x, int y) {
        int[] colors = legendColors(graph.kind);
        String[] items = Text.get(graph.legend).split(java.util.regex.Pattern.quote(SEPARATOR));
        int right = formX + formWidth - 6;
        for (int i = 0; i < items.length && x < right; i++) {
            if (i > 0) {
                g.text(font, SEPARATOR, x, y, UiTheme.MUTED);
                x += font.width(SEPARATOR);
            }
            if (i < colors.length) {
                g.fill(x, y, x + 8, y + 8, colors[i]);
                x += 12;
            }
            String text = HorizontalText.ellipsize(font, items[i], Math.max(0, right - x));
            g.text(font, text, x, y, UiTheme.SECONDARY);
            x += font.width(text);
        }
    }

    /**
     * Ticks sit inside the plot's right edge, clear of channel labels and the hover readout.
     */
    private void ticks(GuiGraphicsExtractor g, int kind, int x, int y, int w, int h) {
        String[] labels = switch (kind) {
            case 0, 4 -> new String[]{"+24 dB", "0", "−24"};
            case 1 -> new String[]{"0 dBFS", "−50", "−100"};
            case 2 -> new String[]{"+1", "0", "−1"};
            default -> null;
        };
        if (labels == null) {
            g.text(font, "−60", x, y + h + 2, UiTheme.MUTED);
            g.text(font, "0 dBFS", x + w - font.width("0 dBFS"), y + h + 2, UiTheme.MUTED);
            return;
        }
        int[] rows = {y + 3, y + h / 2 - font.lineHeight - 1, y + h - font.lineHeight - 1};
        for (int i = 0; i < 3; i++)
            g.text(font, labels[i], x + w - 4 - font.width(labels[i]), rows[i], UiTheme.MUTED);
    }

    private boolean clipping(int stage) {
        var s = snapshot();
        return s != null && s.meter(stage).clips() > 0;
    }

    /**
     * A missing measurement reads "N/A", not "N/A dBFS".
     */
    static String units(String text, String unavailable) {
        return text.replace(unavailable + " dBFS", unavailable).replace(unavailable + " dB", unavailable);
    }

    private static String units(String text) {
        return units(text, Text.get("analyzer.na"));
    }

    private AudioDiagnostics.Snapshot snapshot() {
        var state = app.playback.state();
        if (!enabled || app.settings.volume == 0 || state.status() != AudioBackend.Status.PLAYING && state.status() != AudioBackend.Status.PAUSED)
            return null;
        var d = app.playback.diagnostics();
        return d == null ? null : d.snapshot();
    }

    private String status() {
        if (!enabled) return Text.get("analyzer.disabled");
        if (app.settings.volume == 0) return Text.get("analyzer.muted");
        return Text.get("analyzer.status." + app.playback.state().status().name().toLowerCase(Locale.ROOT));
    }

    private String normalization() {
        return LoudnessStatusText.describe(app.playback.normalization());
    }

    private String meter(int stage) {
        var s = snapshot();
        if (s == null) return Text.get("analyzer.stage." + stage) + ": " + Text.get("analyzer.no_data");
        var m = s.meter(stage);
        return units(Text.get("analyzer.meter", Text.get("analyzer.stage." + stage), db(m.peakDb()), db(m.rmsDb()), db(m.holdDb()), m.clips()));
    }

    private static String db(double value) {
        if (Double.isNaN(value)) return Text.get("analyzer.na");
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f", Math.abs(value) < .05 ? 0 : value) : "−∞";
    }

    private static double amplitudeDb(double value) {
        if (Double.isNaN(value)) return Double.NaN;
        return value > 1e-12 ? 20 * Math.log10(value) : Double.NEGATIVE_INFINITY;
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float delta) {
        updatePanels();
        var s = snapshot();
        for (var graph : graphs) {
            int x = formX + 6, y = contentY(graph.y), w = formWidth - 12, h = 80;
            if (y + h < 36 || y - 22 > height - 12) continue;
            legend(g, graph, formX + 4, y - 17);
            g.fill(x, y, x + w, y + h, UiTheme.BACKGROUND);
            g.outline(x, y, w, h, UiTheme.BORDER);
            if (s == null) {
                g.text(font, Text.get("analyzer.no_data"), x + 5, y + 8, UiTheme.MUTED);
                continue;
            }
            mesh.begin(width, 0, Settings.HudShape.RECTANGLE);
            if (graph.kind == 3) {
                int middle = y + 16;
                g.text(font, "L", x + 4, middle, UiTheme.ACCENT);
                g.text(font, "R", x + 4, middle + 24, UiTheme.SUCCESS);
                mesh.fill(x + 20, middle, x + 20 + (int) (Math.clamp((s.leftRms + 60) / 60, 0, 1) * (w - 25)), middle + 10, UiTheme.ACCENT);
                mesh.fill(x + 20, middle + 24, x + 20 + (int) (Math.clamp((s.rightRms + 60) / 60, 0, 1) * (w - 25)), middle + 34, UiTheme.SUCCESS);
            } else if (graph.kind == 4) {
                mesh.fill(x, y + h / 2, x + w, y + h / 2 + 1, UiTheme.BORDER);
                trace(x, y, w, h, s::environment, -24, 24, UiTheme.ACCENT);
            } else if (graph.kind == 0) {
                int zero = y + h / 2;
                mesh.fill(x, zero, x + w, zero + 1, UiTheme.BORDER);
                trace(x, y, w, h, s::filter, -24, 24, UiTheme.ACCENT);
                trace(x, y, w, h, s::net, -24, 24, UiTheme.SUCCESS);
                trace(x, y, w, h, s::dynamic, -24, 24, UiTheme.WARNING);
            } else {
                int a = mode == AudioComparison.Mode.ENVIRONMENT ? 1 : 0, b = mode == AudioComparison.Mode.AUDIO_OPTIONS ? 1 : mode == AudioComparison.Mode.ENVIRONMENT ? 2 : 4;
                if (graph.kind == 1) {
                    if (before) trace(x, y, w, h, i -> s.spectrum(a, i), -100, 0, UiTheme.ACCENT);
                    if (after) trace(x, y, w, h, i -> s.spectrum(b, i), -100, 0, UiTheme.SUCCESS);
                } else {
                    mesh.fill(x, y + h / 2, x + w, y + h / 2 + 1, UiTheme.BORDER);
                    for (int i = 0; i < AudioDiagnostics.POINTS; i++) {
                        int px = x + i * (w - 1) / (AudioDiagnostics.POINTS - 1);
                        if (before) wave(s, a, i, px, y, h, UiTheme.ACCENT);
                        if (after) wave(s, b, i, px + 1, y, h, UiTheme.SUCCESS);
                    }
                }
            }
            mesh.submit(g);
            ticks(g, graph.kind, x, y, w, h);
            if (graph.kind != 2 && graph.kind != 3) {
                g.text(font, "20 Hz", x, y + h + 2, UiTheme.MUTED);
                String max = String.format(Locale.ROOT, "%.1f kHz", Math.min(20000, s.rate * .45) / 1000);
                g.text(font, max, x + w - font.width(max), y + h + 2, UiTheme.MUTED);
                if (mx >= x && mx < x + w && my >= y && my < y + h) {
                    int point = Math.clamp((mx - x) * (AudioDiagnostics.POINTS - 1) / Math.max(1, w - 1), 0, AudioDiagnostics.POINTS - 1);
                    int output = mode == AudioComparison.Mode.AUDIO_OPTIONS ? 1 : mode == AudioComparison.Mode.ENVIRONMENT ? 2 : 4;
                    double value = graph.kind == 1 ? s.spectrum(output, point) : graph.kind == 4 ? s.environment(point) : s.dynamic(point);
                    g.text(font, units(String.format(Locale.ROOT, "%.0f Hz  %s %s", AudioDiagnostics.frequency(point, s.rate), db(value), graph.kind == 1 ? "dBFS" : "dB")), x + 72, y + 3, UiTheme.TEXT);
                }
            }
        }
    }

    private void trace(int x, int y, int w, int h, IntToDoubleFunction read, double low, double high, int color) {
        int previous = y + h - (int) Math.round(Math.clamp((read.applyAsDouble(0) - low) / (high - low), 0, 1) * (h - 2));
        for (int i = 1; i < AudioDiagnostics.POINTS; i++) {
            int next = y + h - 1 - (int) Math.round(Math.clamp((read.applyAsDouble(i) - low) / (high - low), 0, 1) * (h - 2));
            int left = x + (i - 1) * (w - 1) / (AudioDiagnostics.POINTS - 1), right = x + i * (w - 1) / (AudioDiagnostics.POINTS - 1);
            mesh.fill(left, Math.min(previous, next), Math.max(left + 1, right), Math.max(previous, next) + 1, color);
            previous = next;
        }
    }

    private void wave(AudioDiagnostics.Snapshot s, int stage, int point, int x, int y, int height, int color) {
        int top = y + height / 2 - (int) (Math.clamp(s.wave(stage, point, true), -1, 1) * (height / 2 - 1));
        int bottom = y + height / 2 - (int) (Math.clamp(s.wave(stage, point, false), -1, 1) * (height / 2 - 1));
        mesh.fill(x, top, x + 1, Math.max(top + 1, bottom), color);
    }

    @Override
    public void removed() {
        var d = app.playback.diagnostics();
        if (d != null) d.panels(0);
        app.playback.comparison(AudioComparison.OFF);
        super.removed();
    }

    private record Graph(int y, int kind, String legend) {
    }
}
