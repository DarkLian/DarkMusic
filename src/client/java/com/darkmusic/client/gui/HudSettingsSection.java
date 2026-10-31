package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.WaveformSettings;
import com.darkmusic.client.text.Text;

/**
 * The HUD section of Settings: the HUD's appearance, motion, song card and progress, in that order.
 */
final class HudSettingsSection {
    private final SettingsLayout layout;
    private final SettingsScreen.Sources app;
    private final Settings s;
    private final HudKind kind;

    private HudSettingsSection(SettingsLayout layout, SettingsScreen.Sources app, Settings s, HudKind kind) {
        this.layout = layout;
        this.app = app;
        this.s = s;
        this.kind = kind;
    }

    static void build(SettingsLayout layout, SettingsScreen.Sources app, Settings s) {
        layout.section("text.darkmusic.hud_settings");
        layout.toggle("text.darkmusic.now_playing_hud", () -> s.hudEnabled, v -> {
            s.hudEnabled = v;
            layout.rebuild();
        }, "hud.darkmusic.master_help");
        var section = new HudSettingsSection(layout, app, s, HudKind.of(s));
        if (s.hudEnabled) section.appearance();
        section.motion();
        section.card();
        section.progress();
    }

    private void appearance() {
        var screen = layout.screen();
        layout.subsection("hud.darkmusic.group.appearance");
        layout.choice("circle.darkmusic.style", "circle.darkmusic.style.", () -> s.hudStyle, v -> s.hudStyle = v, "circle.darkmusic.style_help");
        if (kind.classic()) {
            layout.toggle("text.darkmusic.hud_artwork", () -> s.hudArtwork, v -> s.hudArtwork = v, "hud.darkmusic.artwork_help");
            layout.toggle("hud.darkmusic.next_song", () -> s.hudNextSong, v -> s.hudNextSong = v, "hud.darkmusic.next_song_help");
        }

        if (!kind.spectrumOnly()) {
            layout.choice("hud.image_source", "hud.image_source.", s::hudImage, s::hudImage, "hud.image_source.help");
        }

        layout.toggle("hud.darkmusic.spectrum", () -> s.hudVisualizer, v -> s.hudVisualizer = v, "hud.darkmusic.spectrum_help");
        if (!kind.linear()) circular();
        if (kind.classic()) {
            layout.choice("style.darkmusic.shape", "style.darkmusic.shape.", () -> s.hudShape, v -> s.hudShape = v, "style.darkmusic.shape_help");
            layout.toggle("style.darkmusic.raised", () -> s.hudRaised, v -> s.hudRaised = v, "style.darkmusic.raised_help");
            layout.toggle("hud.text_plate", () -> s.hudTextPlate, v -> s.hudTextPlate = v, "hud.text_plate.help");
        }
        if (kind.artwork()) {
            layout.toggle("circle.darkmusic.text", () -> s.artworkText, v -> s.artworkText = v, "hud.darkmusic.artwork_text_help");
            layout.toggle("hud.darkmusic.artwork_reactive", () -> s.artworkReactive, v -> s.artworkReactive = v, "hud.darkmusic.artwork_reactive_help");
        }
        if (kind.linear() && !kind.spectrumOnly()) {
            layout.slider(s.hudBackgroundTransparency, v -> Text.get("hud.darkmusic.transparency", Math.round(s.hudBackgroundTransparency * 100)), v -> s.hudBackgroundTransparency(v), 100, "hud.darkmusic.transparency_help");
            layout.toggle("hud.darkmusic.background_gradient", () -> s.hudBackgroundGradient, v -> s.hudBackgroundGradient = v, "hud.darkmusic.background_gradient_help");
            layout.action("hud.darkmusic.background_colors", () -> screen.show(app.navigation().colors(screen, s.hudBackgroundColors, "hud.darkmusic.background_colors")), "colors.darkmusic.help");
        }
    }

    /**
     * The circular style's own rows: image gap, text, pulse and rotation.
     */
    private void circular() {
        layout.waveformSlider(s.circularImageGap / 24.0, v -> Text.get("hud.circular_gap", s.circularImageGap),
            v -> s.circularImageGap((int) Math.round(v * 24)), 24, "hud.circular_gap.help");
        layout.toggle("circle.darkmusic.text", () -> s.circularText, v -> {
            s.circularText = v;
            layout.rebuild();
        }, "circle.darkmusic.text_help");
        if (s.circularText)
            layout.toggle("hud.text_plate", () -> s.hudTextPlate, v -> s.hudTextPlate = v, "hud.text_plate.help");
        // Off, then following the level, then the beat.
        layout.choice("circle.darkmusic.pulse", () -> Text.get("circle.darkmusic.pulse."
            + (!s.circularIconPulse ? "off" : s.visualQuality.beatReactive ? "beat" : "level")), () -> {
            if (!s.circularIconPulse) {
                s.circularIconPulse = true;
                s.visualQuality.beatReactive = false;
            } else if (!s.visualQuality.beatReactive) s.visualQuality.beatReactive = true;
            else s.circularIconPulse = false;
        }, "circle.darkmusic.pulse_help");
        layout.rotation("circle.darkmusic.rotation", () -> s.circularRotation, v -> s.circularRotation = v, "circle.darkmusic.rotation_help");
        if (s.circularRotation != Settings.Rotation.OFF || s.hudProgress && s.circularProgressRotation != Settings.Rotation.OFF)
            layout.waveformSlider((s.circularRotationSeconds - 4) / 116.0, v -> Text.get("circle.darkmusic.rotation_speed", s.circularRotationSeconds),
                v -> s.circularRotationSeconds(4 + (int) Math.round(v * 116)), 116, "circle.darkmusic.rotation_speed_help");
    }

    private void motion() {
        layout.subsection("hud.darkmusic.group.motion");
        layout.toggle("hud.darkmusic.in_menus", () -> s.hudInMenus, v -> s.hudInMenus = v, "hud.darkmusic.in_menus_help");
        if (s.hudEnabled) {
            layout.waveformSlider(s.hudIdleHide / (double) Settings.IDLE_HIDE_MAX, v -> Text.get("hud.darkmusic.idle_hide") + ": "
                    + (s.hudIdleHide == 0 ? Text.get("hud.darkmusic.idle_off") : Text.get("hud.darkmusic.seconds", s.hudIdleHide)),
                v -> s.hudIdleHide((int) Math.round(v * Settings.IDLE_HIDE_MAX)), Settings.IDLE_HIDE_MAX, "hud.darkmusic.idle_hide_help");
            if (s.hudIdleHide > 0)
                layout.choice("hud.darkmusic.idle_animation", "hud.darkmusic.motion.", () -> s.hudIdleAnimation, v -> s.hudIdleAnimation = v,
                    "hud.darkmusic.idle_animation_help");
            layout.toggle("hud.darkmusic.transitions", () -> s.hudTransitions, v -> s.hudTransitions = v, "hud.darkmusic.transitions_help");
        }
    }

    private void card() {
        var screen = layout.screen();
        layout.subsection("hud.darkmusic.group.card");
        layout.choice("hud.darkmusic.song_card", "hud.darkmusic.song_card.", () -> s.songCard, v -> s.songCard = v, "hud.darkmusic.song_card_help");
        if (s.songCard != Settings.SongCard.OFF) {
            layout.choice("hud.darkmusic.card_animation", "hud.darkmusic.motion.", () -> s.songCardAnimation, v -> s.songCardAnimation = v,
                "hud.darkmusic.card_animation_help");
            layout.action("hud.darkmusic.card_position", () -> screen.show(app.navigation().hudEditor(screen, HudEditorScreen.Element.CARD)), "hud.darkmusic.card_position_help");
        }
    }

    private void progress() {
        var screen = layout.screen();
        layout.subsection("progress.darkmusic.group");
        if (s.hudEnabled && !kind.spectrumOnly())
            layout.toggle("text.darkmusic.hud_progress", () -> s.hudProgress, v -> s.hudProgress = v,
                kind.artwork() ? "hud.darkmusic.artwork_progress_help" : kind.classic() ? "text.darkmusic.show_the_current_track_s_progress_below_the_title_and_artist" : "circle.darkmusic.progress_help");
        if (s.hudEnabled && kind.classic()) {
            layout.toggle("hud.darkmusic.time", () -> s.hudTime, v -> {
                s.hudTime = v;
                layout.rebuild();
            }, "hud.darkmusic.time_help");
            if (s.hudTime)
                layout.choice("hud.darkmusic.time_position", "hud.darkmusic.time_position.", () -> s.hudTimePosition, v -> s.hudTimePosition = v,
                    "hud.darkmusic.time_position_help");
        }
        if (!kind.linear() && s.hudProgress)
            layout.rotation("circle.darkmusic.progress_rotation", () -> s.circularProgressRotation, v -> s.circularProgressRotation = v, "circle.darkmusic.progress_rotation_help");
        layout.toggle("progress.darkmusic.waveform", () -> s.waveformProgress, v -> {
            s.waveformProgress = v;
            layout.rebuild();
        }, "progress.darkmusic.waveform_help");
        layout.finish("finish.darkmusic.progress", () -> s.progressFinish, v -> s.progressFinish = v);
        layout.action("colors.darkmusic.progress", () -> screen.show(app.navigation().colors(screen, false)), "colors.darkmusic.help");
        if (s.waveformProgress) waveform();
    }

    private void waveform() {
        var screen = layout.screen();
        layout.choice("waveform.style", "waveform.style.", () -> s.waveform.style, v -> s.waveform.style = v, "waveform.style.help");
        layout.action("waveform.unplayed", () -> screen.show(new ColorPickerScreen(screen, s.waveform.unplayedColor, 0x555D78, color -> {
            s.waveform.unplayedColor = color;
            app.save();
        })), "waveform.unplayed.help");
        layout.waveformSlider((s.waveform.height - 6) / 12.0, v -> Text.get("waveform.height", s.waveform.height),
            v -> s.waveform.height(6 + (int) Math.round(v * 12)), 12, "waveform.height.help");
        layout.waveformSlider((s.waveform.detail - 48) / 464.0, v -> Text.get("waveform.detail", s.waveform.detail),
            v -> s.waveform.detail(48 + (int) Math.round(v * 464)), 464, "waveform.detail.help");
        if (s.waveform.style == WaveformSettings.Style.BARS)
            layout.waveformSlider(s.waveform.spacing / 4.0, v -> Text.get("waveform.spacing", s.waveform.spacing),
                v -> s.waveform.spacing((int) Math.round(v * 4)), 4, "waveform.spacing.help");
        layout.waveformSlider(s.waveform.smoothing, v -> Text.get("waveform.smoothing", Math.round(s.waveform.smoothing * 100)),
            v -> s.waveform.smoothing(v), 100, "waveform.smoothing.help");
        layout.waveformSlider((s.waveform.contrast - .5) / 2, v -> Text.get("waveform.contrast", Math.round(s.waveform.contrast * 100) / 100.0),
            v -> s.waveform.contrast(.5 + v * 2), 100, "waveform.contrast.help");
        layout.waveformSlider((s.waveform.opacity - .15) / .85, v -> Text.get("waveform.opacity", Math.round(s.waveform.opacity * 100)),
            v -> s.waveform.opacity(.15 + v * .85), 85, "waveform.opacity.help");
        layout.action("waveform.reset", () -> {
            s.waveform = new WaveformSettings();
            app.save();
            layout.rebuild();
        }, "waveform.reset.help").destructive();
    }

    /**
     * Which rows the chosen HUD style offers. Every style but the circular one draws its spectrum in a line, and so
     * does a hidden HUD.
     */
    record HudKind(boolean classic, boolean artwork, boolean spectrumOnly, boolean linear) {
        static HudKind of(Settings s) {
            boolean classic = s.hudStyle == Settings.HudStyle.CLASSIC;
            boolean artwork = s.hudStyle == Settings.HudStyle.ARTWORK;
            boolean spectrumOnly = s.hudStyle == Settings.HudStyle.SPECTRUM;
            return new HudKind(classic, artwork, spectrumOnly, !s.hudEnabled || classic || artwork || spectrumOnly);
        }
    }
}
