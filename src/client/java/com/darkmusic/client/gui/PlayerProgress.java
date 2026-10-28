package com.darkmusic.client.gui;

import com.darkmusic.client.config.SettingsSource;
import com.darkmusic.client.hud.HudSources;
import com.darkmusic.client.library.Track;

import java.util.function.Supplier;

/**
 * Actual main-player styling, also used by its noninteractive Settings preview.
 */
public final class PlayerProgress {
    /**
     * Progress colors and finish from settings, and waveforms from the HUD.
     */
    public interface Sources extends SettingsSource {
        HudSources hud();
    }

    private PlayerProgress() {
    }

    static void configure(ValueSlider slider, Sources app, Supplier<Track> track) {
        slider.trackOnly(true);
        slider.simpleProgress(true);
        slider.colors(() -> app.settings().progressColors);
        slider.waveform(() -> app.settings().waveformProgress ? app.hud().waveform(track.get()) : null, () -> app.settings().waveform);
        slider.finish(() -> app.settings().progressFinish);
    }
}
