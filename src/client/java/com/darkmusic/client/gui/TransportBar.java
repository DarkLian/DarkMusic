package com.darkmusic.client.gui;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The player's bottom dock: speed, the playback buttons, volume and the seek bar. Changes go through the transport
 * operations; each tick it follows the player's state.
 */
final class TransportBar {
    /**
     * How the screen adds the dock's widgets: transport-styled icon buttons with their shortcuts, and sliders.
     */
    interface Controls {
        Button icon(String key, UiIcon icon, int x, int y, int width, Runnable action);

        ValueSlider slider(ValueSlider slider);
    }

    private final MusicScreen.Sources app;
    private Button play, shuffle, repeat, jump, seekBack, seekForward, mute;
    private ValueSlider progress, speed, volume;
    // A seek applies only to the song that was playing when the drag began.
    private Track seekTrack;
    private boolean seekDragging;

    TransportBar(MusicScreen.Sources app) {
        this.app = app;
    }

    /**
     * Forgets a drag in progress; the screen is being rebuilt.
     */
    void reset() {
        seekDragging = false;
        seekTrack = null;
        shuffle = repeat = null;
    }

    void build(PlayerLayout layout, Controls controls, Runnable playPause, Runnable jumpToPlaying) {
        int transportY = layout.transportY();
        int x = layout.transportX();
        int sliderWidth = layout.controlWidth();
        int buttonWidth = layout.transportButtonWidth();
        int gap = PlayerLayout.CONTROL_GAP;
        speed = controls.slider(Help.tip(new MediaSlider(x, transportY, sliderWidth, (app.settings().speed - .5) / 2.5,
                v -> Text.get("format.darkmusic.speed", String.format(Locale.ROOT, "%.1f", app.settings().speed)),
                v -> app.speed(.5 + v * 2.5), app::save, 25, v -> String.format(Locale.ROOT, "%.1f×", .5 + v * 2.5), true),
            Help.withKeys(Text.get("text.darkmusic.playback_speed_0_5x_to_3_0x_in_0_1x_steps_faster_raises_pitch_slower_lowers_it"), app.shortcuts().slower(), app.shortcuts().faster())));
        x += sliderWidth + gap;
        UiButton.shortcuts(controls.icon("key.darkmusic.normal_speed", UiIcon.REFRESH, x, transportY, buttonWidth, () -> app.speed(1)), app.shortcuts().normalSpeed());
        x += buttonWidth + gap;
        shuffle = controls.icon("text.darkmusic.shuffle", UiIcon.SHUFFLE, x, transportY, buttonWidth, this::shuffle);
        x += buttonWidth + gap;
        jump = controls.icon("player.darkmusic.jump", UiIcon.LOCATE, x, transportY, buttonWidth, jumpToPlaying);
        x += buttonWidth + gap;
        controls.icon("text.darkmusic.previous", UiIcon.PREVIOUS, x, transportY, buttonWidth, () -> {
            app.transport().previous();
        });
        x += buttonWidth + gap;
        seekBack = controls.icon("key.darkmusic.seek_back", UiIcon.SEEK_BACK, x, transportY, buttonWidth, () -> app.seekRelative(-5));
        x += buttonWidth + gap;
        play = controls.icon("text.darkmusic.play", UiIcon.PLAY, x, transportY - 4, buttonWidth + 8, playPause);
        play.setHeight(28);
        UiButton.playOutline(play);
        x += buttonWidth + 8 + gap;
        seekForward = controls.icon("key.darkmusic.seek_forward", UiIcon.SEEK_FORWARD, x, transportY, buttonWidth, () -> app.seekRelative(5));
        x += buttonWidth + gap;
        controls.icon("text.darkmusic.next", UiIcon.NEXT, x, transportY, buttonWidth, () -> {
            app.transport().next();
        });
        x += buttonWidth + gap;
        controls.icon("text.darkmusic.stop", UiIcon.STOP, x, transportY, buttonWidth, () -> app.transport().stop());
        x += buttonWidth + gap;
        repeat = controls.icon("text.darkmusic.repeat", UiIcon.REPEAT, x, transportY, buttonWidth, this::repeat);
        x += buttonWidth + gap;
        mute = UiButton.shortcuts(controls.icon("key.darkmusic.mute", app.settings().volume == 0 ? UiIcon.MUTED : UiIcon.VOLUME, x, transportY, buttonWidth, app::toggleMute), app.shortcuts().mute());
        UiButton.selected(mute, () -> app.settings().volume == 0);
        x += buttonWidth + gap;
        volume = controls.slider(Help.tip(new MediaSlider(x, transportY, sliderWidth, app.settings().volume,
                v -> Text.get("format.darkmusic.volume", Math.round(v * 100)), app::volume, app::save, 0, v -> Math.round(v * 100) + "%", false),
            Help.withKeys(Text.get("text.darkmusic.darkmusic_volume_independent_of_minecraft_sound_sliders"), app.shortcuts().volumeDown(), app.shortcuts().volumeUp())));
        progress = controls.slider(new ValueSlider(layout.dock().x() + 4, layout.seekY(), layout.dock().width() - 8, 0,
            v -> MusicScreen.time(progress != null && progress.editing() ? v * app.player().state().duration() : app.player().state().position()) + " / " + MusicScreen.time(app.player().state().duration()),
            v -> {
                if (!seekDragging) seekTrack = app.player().state().track();
                seekDragging = progress != null && progress.editing();
            }, this::commitSeek));
        PlayerProgress.configure(progress, app, () -> app.player().state().track());
        UiButton.selected(shuffle, () -> app.player().shuffle());
        UiButton.selected(repeat, () -> app.player().repeat() != Repeat.OFF);
    }

    private void shuffle() {
        if (app.mediaBusy()) return;
        app.shuffle(!app.player().shuffle());
    }

    private void repeat() {
        if (app.mediaBusy()) return;
        var values = Repeat.values();
        app.repeat(values[(app.player().repeat().ordinal() + 1) % values.length]);
    }

    private void commitSeek() {
        var state = app.player().state();
        if (seekTrack != null && state.track() != null && seekTrack.id().equals(state.track().id()) && progress.active)
            app.transport().seek(progress.fraction() * state.duration());
        seekDragging = false;
        seekTrack = null;
    }

    /**
     * Follows the player: volume, speed, which controls are enabled, progress, and the labels and icons of play,
     * shuffle and repeat. Controlling: the selected song is the one playing, so Play pauses and resumes it.
     */
    void sync(AudioBackend.State audio, boolean controlling) {
        jump.active = audio.track() != null;
        volume.sync(app.settings().volume);
        UiButton.icon(mute, app.settings().volume == 0 ? UiIcon.MUTED : UiIcon.VOLUME, true);
        speed.sync((app.settings().speed - .5) / 2.5);
        play.active = !app.mediaBusy();
        seekBack.active = seekForward.active = !app.mediaBusy() && audio.track() != null && audio.duration() > 0
            && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR;
        progress.active = !app.mediaBusy() && audio.track() != null && audio.duration() > 0 && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR;
        progress.sync(audio.duration() > 0 ? audio.position() / audio.duration() : 0);
        play.setMessage(Component.literal(controlling && audio.status() == AudioBackend.Status.PLAYING ? Text.get("text.darkmusic.pause") : controlling && audio.status() == AudioBackend.Status.PAUSED ? Text.get("text.darkmusic.resume") : Text.get("text.darkmusic.play")));
        UiButton.icon(play, controlling && audio.status() == AudioBackend.Status.PLAYING ? UiIcon.PAUSE : UiIcon.PLAY, true);
        if (shuffle != null)
            shuffle.setMessage(Component.literal(app.player().shuffle() ? Text.get("text.darkmusic.shuffle_on") : Text.get("text.darkmusic.shuffle_off")));
        if (repeat != null) {
            repeat.setMessage(Text.component("format.darkmusic.repeat", UiStatus.label(app.player().repeat().name())));
            UiButton.icon(repeat, app.player().repeat() == Repeat.ONE ? UiIcon.REPEAT_ONE : app.player().repeat() == Repeat.ALL ? UiIcon.REPEAT_ALL : UiIcon.REPEAT, true);
        }
    }

    /**
     * The position the time readout shows: where the seek bar is held while dragging, otherwise the player's.
     */
    double shownPosition(AudioBackend.State audio) {
        return progress.editing() ? progress.fraction() * audio.duration() : audio.position();
    }

    boolean seekable() {
        return progress.active;
    }
}
