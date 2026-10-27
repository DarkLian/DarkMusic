package com.darkmusic.client.hud;

/**
 * A screen that previews the HUD itself.
 */
public interface HudPreview {
    /**
     * Whether the preview draws a progress bar, so the waveform of its stand-in track loads while nothing plays.
     */
    default boolean previewsProgress() {
        return true;
    }
}
