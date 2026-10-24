package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;

import java.util.function.Supplier;

/**
 * Actual main-player styling, also used by its noninteractive Settings preview.
 */
final class PlayerProgress {
    private PlayerProgress() {
    }

    static void configure(ValueSlider slider, DarkMusicClient app, Supplier<Track> track) {
        slider.trackOnly(true);
        slider.simpleProgress(true);
        slider.colors(() -> app.settings.progressColors);
        slider.waveform(() -> app.settings.waveformProgress && app.waveforms != null ? app.waveforms.get(track.get()) : null, () -> app.settings.waveform);
        slider.finish(() -> app.settings.progressFinish);
    }
}
