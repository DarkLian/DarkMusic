package com.darkmusic.client.command;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs chat commands against the live client, on the client thread, with the same guards as the player's controls.
 */
final class ClientActions implements LocalCommands.Actions {
    private final DarkMusicClient app;
    private final String version = FabricLoader.getInstance().getModContainer("darkmusic")
        .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");

    ClientActions(DarkMusicClient app) {
        this.app = app;
    }

    @Override
    public ChatPalette palette() {
        var settings = app.settings;
        return ChatPalette.of(settings == null ? ChatPalette.DEFAULT_ACCENT : settings.menuAccentColor);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public void player() {
        app.requestScreen(false);
    }

    @Override
    public void layout() {
        app.requestScreen(true);
    }

    @Override
    public String lyrics() {
        return app.requestLyrics();
    }

    @Override
    public void pip() {
        app.requestPip();
    }

    @Override
    public void editKeys() {
        app.requestKeyBinds();
    }

    @Override
    public void checkUpdate() {
        if (app.updates != null) app.updates.checkFromCommand();
    }

    @Override
    public void installUpdate() {
        if (app.updates != null) app.updates.requestUpdate();
    }

    @Override
    public boolean installCode(String code) {
        return app.updates != null && app.updates.installCode(code);
    }

    @Override
    public List<LocalCommands.Shortcut> shortcuts() {
        var keys = app.shortcuts;
        if (keys.open == null) return List.of();
        return List.of(
            shortcut("key.darkmusic.open", keys.open),
            shortcut("key.darkmusic.toggle", keys.toggle),
            shortcut("command.darkmusic.keys.previous_next", keys.previous, keys.next),
            shortcut("command.darkmusic.keys.volume", keys.volumeDown, keys.volumeUp),
            shortcut("key.darkmusic.mute", keys.mute),
            shortcut("command.darkmusic.keys.speed", keys.slower, keys.faster),
            shortcut("key.darkmusic.normal_speed", keys.normalSpeed),
            shortcut("command.darkmusic.keys.seek", keys.seekBack, keys.seekForward),
            shortcut("key.darkmusic.stop", keys.stop),
            shortcut("key.darkmusic.jump", keys.jump));
    }

    private static LocalCommands.Shortcut shortcut(String label, KeyMapping... keys) {
        return new LocalCommands.Shortcut(label, Arrays.stream(keys)
            .map(key -> key.isUnbound() ? null : key.getTranslatedKeyMessage().getString()).toList());
    }

    @Override
    public List<String> songs() {
        var library = app.library;
        if (library == null) return List.of();
        return library.snapshot().tracks().stream().map(Track::title).filter(t -> t != null && !t.isBlank()).distinct().toList();
    }

    private boolean ready() {
        return app.idle() && app.settings != null && app.playback != null && app.library != null;
    }

    private Component unavailable() {
        return palette().error("command.darkmusic.unavailable");
    }

    private static boolean active(AudioBackend.State state) {
        return state.track() != null && state.status() != AudioBackend.Status.STOPPED && state.status() != AudioBackend.Status.ERROR;
    }

    @Override
    public Component play(String query) {
        if (!ready()) return unavailable();
        var palette = palette();
        var tracks = app.library.snapshot().tracks();
        if (query != null) {
            Track match = LocalCommands.find(tracks, query);
            if (match == null)
                return palette.error(tracks.isEmpty() ? "command.darkmusic.no_songs" : "command.darkmusic.no_match", query.strip());
            app.playback.play(tracks, match);
            return palette.value("command.darkmusic.playing", match.title());
        }
        var state = app.playback.state();
        if (state.status() == AudioBackend.Status.PLAYING) return palette.info("command.darkmusic.already_playing");
        if (state.track() == null) {
            if (tracks.isEmpty()) return palette.error("command.darkmusic.no_songs");
            app.playback.play(tracks, tracks.getFirst());
            return palette.value("command.darkmusic.playing", tracks.getFirst().title());
        }
        if (state.status() == AudioBackend.Status.PAUSED) app.playback.resume();
        else app.playback.toggle();
        return palette.value("command.darkmusic.playing", state.track().title());
    }

    @Override
    public Component pause() {
        if (!ready()) return unavailable();
        var status = app.playback.state().status();
        if (status == AudioBackend.Status.PAUSED) return palette().info("command.darkmusic.already_paused");
        if (!active(app.playback.state())) return palette().info("upnext.idle");
        app.playback.pause();
        return palette().info("command.darkmusic.paused");
    }

    @Override
    public Component stop() {
        if (!ready()) return unavailable();
        if (!active(app.playback.state())) return palette().info("upnext.idle");
        app.playback.stop();
        return palette().info("command.darkmusic.stopped");
    }

    // The song change itself is the feedback; only explain when there is nothing to skip.
    @Override
    public Component next() {
        if (!ready()) return unavailable();
        if (app.playback.state().track() == null) return palette().info("upnext.idle");
        app.playback.next();
        return null;
    }

    @Override
    public Component previous() {
        if (!ready()) return unavailable();
        if (app.playback.state().track() == null) return palette().info("upnext.idle");
        app.playback.previous();
        return null;
    }

    @Override
    public Component now() {
        if (!ready()) return unavailable();
        var palette = palette();
        var state = app.playback.state();
        if (!active(state))
            return palette.info("upnext.idle").append(palette.plain(" ", palette.muted()))
                .append(palette.button("command.darkmusic.button.play", "/da play", palette.accent()));
        Track track = state.track();
        var song = palette.plain("♪ ", palette.accent()).append(palette.plain(track.title(), palette.text()));
        if (track.artist() != null && !track.artist().isBlank())
            song.append(palette.plain(" — ", palette.muted())).append(palette.plain(track.artist(), palette.secondary()));
        boolean paused = state.status() == AudioBackend.Status.PAUSED;
        var status = palette.key(paused ? "command.darkmusic.state_paused" : "command.darkmusic.state_playing", palette.accent())
            .append(palette.plain("  " + LocalCommands.time(state.position()) + " / " + LocalCommands.time(state.duration()), palette.text()))
            .append(palette.plain(" · ", palette.muted()))
            .append(palette.key("command.darkmusic.volume_short", palette.secondary(), Math.round(app.settings.volume * 100)))
            .append(palette.plain(" · ", palette.muted()))
            .append(palette.plain(speed(app.settings.speed), palette.secondary()));
        if (app.playback.shuffle())
            status.append(palette.plain(" · ", palette.muted())).append(palette.key("text.darkmusic.shuffle", palette.secondary()));
        if (app.playback.repeat() != Repeat.OFF)
            status.append(palette.plain(" · ", palette.muted()))
                .append(palette.key("command.darkmusic.repeat_now", palette.secondary(), Text.get(repeatKey(app.playback.repeat()))));
        MutableComponent buttons = palette.button("command.darkmusic.button.previous", "/da previous", palette.text())
            .append(palette.plain(" ", palette.muted()))
            // The message does not update after a click, so both stay available; the current state's opposite is highlighted.
            .append(palette.button("command.darkmusic.button.play", "/da play", paused ? palette.accent() : palette.text()))
            .append(palette.plain(" ", palette.muted()))
            .append(palette.button("command.darkmusic.button.pause", "/da pause", paused ? palette.text() : palette.accent()))
            .append(palette.plain(" ", palette.muted()))
            .append(palette.button("command.darkmusic.button.next", "/da next", palette.text()))
            .append(palette.plain(" ", palette.muted()))
            .append(palette.button("command.darkmusic.button.stop", "/da stop", palette.text()))
            .append(palette.plain("  ", palette.muted()))
            .append(palette.button("command.darkmusic.button.lyrics", "/da lyrics", palette.secondary()));
        return song.append(Component.literal("\n")).append(status).append(Component.literal("\n")).append(buttons);
    }

    @Override
    public Component queue(String query) {
        if (!ready() || !app.canEditQueue()) return unavailable();
        var palette = palette();
        var tracks = app.library.snapshot().tracks();
        Track match = LocalCommands.find(tracks, query);
        if (match == null)
            return palette.error(tracks.isEmpty() ? "command.darkmusic.no_songs" : "command.darkmusic.no_match", query.strip());
        if (!app.playback.addToQueue(match)) return palette.error("command.darkmusic.queue_failed", match.title());
        return palette.value("command.darkmusic.queued", match.title());
    }

    @Override
    public Component volume(Integer percent) {
        if (!ready()) return unavailable();
        if (percent != null) {
            app.volume(percent / 100.0);
            app.volumeFeedback.show(app.settings.volume);
        }
        return palette().value("command.darkmusic.volume_now", Math.round(app.settings.volume * 100) + "%");
    }

    @Override
    public Component mute() {
        if (!ready()) return unavailable();
        app.toggleMute();
        return app.settings.volume > 0 ? palette().value("command.darkmusic.volume_now", Math.round(app.settings.volume * 100) + "%")
            : palette().info("command.darkmusic.muted");
    }

    @Override
    public Component speed(Double value) {
        if (!ready()) return unavailable();
        if (value != null) {
            app.speed(value);
            app.speedFeedback.show(app.settings.speed);
        }
        return palette().value("command.darkmusic.speed_now", speed(app.settings.speed));
    }

    private static String speed(double value) {
        return String.format(Locale.ROOT, "%.1f×", value);
    }

    @Override
    public Component seek(String time) {
        if (!ready()) return unavailable();
        var state = app.playback.state();
        if (!active(state)) return palette().info("upnext.idle");
        double target = LocalCommands.seekTarget(time, state.position(), state.duration());
        if (Double.isNaN(target)) return palette().error("command.darkmusic.seek_invalid", time.strip());
        if (Double.isInfinite(target))
            return palette().error("command.darkmusic.seek_past_end", LocalCommands.time(state.duration()));
        app.playback.seek(target);
        return palette().value("command.darkmusic.position", LocalCommands.time(target) + " / " + LocalCommands.time(state.duration()));
    }

    @Override
    public Component shuffle(Boolean on) {
        if (!ready()) return unavailable();
        boolean value = on == null ? !app.playback.shuffle() : on;
        app.settings.shuffle = value;
        app.playback.shuffle(value);
        app.save();
        return palette().info(value ? "text.darkmusic.shuffle_on" : "text.darkmusic.shuffle_off");
    }

    @Override
    public Component repeat(Repeat mode) {
        if (!ready()) return unavailable();
        var values = Repeat.values();
        Repeat value = mode != null ? mode : values[(app.playback.repeat().ordinal() + 1) % values.length];
        app.settings.repeat = value;
        app.playback.repeat(value);
        app.save();
        return palette().value("command.darkmusic.repeat_now", Text.get(repeatKey(value)));
    }

    private static String repeatKey(Repeat value) {
        return switch (value) {
            case OFF -> "command.darkmusic.repeat_off";
            case ONE -> "command.darkmusic.repeat_one";
            case ALL -> "command.darkmusic.repeat_all";
        };
    }
}
