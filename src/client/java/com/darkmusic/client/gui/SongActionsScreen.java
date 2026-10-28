package com.darkmusic.client.gui;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Temporary playback actions, deliberately separate from playlist editing.
 */
public final class SongActionsScreen extends AudioPage<SongActionsScreen.Sources> {
    /**
     * Actions on one song, and the queue they open.
     */
    public interface Sources extends AudioPage.Sources, UpNextScreen.Sources {
    }

    private final Track track;
    private String feedback = "";
    private UiStatus.Tone feedbackTone = UiStatus.Tone.INFO;
    private SettingRow next, add;

    public SongActionsScreen(Sources app, Screen parent, Track track) {
        super(app, parent, "upnext.actions");
        this.track = track;
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.PLAYLIST;
    }

    @Override
    protected void populate() {
        if (track != null) {
            cursor += SongCard.HEIGHT + 8;
            next = labeledAction(Text.get("upnext.play_next"), Text.get("upnext.play_next.help"), () -> queue(true)).runs();
            add = labeledAction(Text.get("upnext.add"), Text.get("upnext.add.help"), () -> queue(false)).runs();
            tick();
        }
        status(() -> feedback, () -> feedbackTone);
        action("upnext.title", () -> show(new UpNextScreen(app, this)));
    }

    private void queue(boolean first) {
        if (!app.canEditQueue()) return;
        var resolved = app.playlists().index(app.library().snapshot().tracks()).get(track.id());
        if (resolved == null) {
            feedback = Text.get("player.darkmusic.outside_library");
            feedbackTone = UiStatus.Tone.WARNING;
            return;
        }
        boolean accepted = first ? app.playback().playNext(resolved) : app.playback().addToQueue(resolved);
        feedbackTone = accepted ? UiStatus.Tone.SUCCESS : UiStatus.Tone.WARNING;
        if (accepted)
            feedback = Text.get(first ? "upnext.added_next" : "upnext.added", track.title());
        else feedback = Text.get("upnext.full", PlaybackController.QUEUE_LIMIT);
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int x, int y, float delta) {
        if (track != null) SongCard.draw(app, g, font, longText, track, "selected_song", formX, contentY(0), formWidth);
    }

    @Override
    public void tick() {
        if (next != null) next.active = add.active = app.canEditQueue();
    }
}
