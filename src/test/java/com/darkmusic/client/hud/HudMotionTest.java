package com.darkmusic.client.hud;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HudMotionTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static AudioBackend.State state(String title, AudioBackend.Status status) {
        var track = new Track("root", title + ".mp3", Path.of(title + ".mp3"), title, "Artist", "Album", 200, 1, 0, "");
        return new AudioBackend.State(track, status, 10, 200, "");
    }

    @Test
    void theHudSlidesAwayAfterTheIdleDelayAndReturnsWhenPlaybackResumesOrTheSongChanges() {
        var settings = new Settings();
        settings.hudIdleHide = 5;
        var motion = new HudMotion();
        long t = 100 * SECOND;
        assertEquals(0, motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), t));
        assertEquals(0, motion.hidden(settings, state("A", AudioBackend.Status.PAUSED), t + 4 * SECOND), "Not before the delay");
        double sliding = motion.hidden(settings, state("A", AudioBackend.Status.PAUSED), t + 6 * SECOND);
        assertTrue(sliding > 0, "Slides once paused for longer than the delay");
        for (int frame = 1; frame <= 30; frame++)
            motion.hidden(settings, state("A", AudioBackend.Status.PAUSED), t + 6 * SECOND + frame * SECOND / 60);
        assertTrue(motion.fullyHidden());
        // Resuming brings it back, a frame at a time.
        double back = motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), t + 8 * SECOND);
        assertTrue(back < 1 && !motion.fullyHidden());
        for (int frame = 1; frame <= 30; frame++)
            motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), t + 8 * SECOND + frame * SECOND / 60);
        assertEquals(0, motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), t + 9 * SECOND));
        // A song change while paused also counts as activity.
        motion.hidden(settings, state("A", AudioBackend.Status.PAUSED), t + 20 * SECOND);
        assertTrue(motion.fullyHidden());
        motion.hidden(settings, state("B", AudioBackend.Status.PAUSED), t + 20 * SECOND + 1);
        assertEquals(0, motion.hidden(settings, state("B", AudioBackend.Status.PAUSED), t + 21 * SECOND));
        settings.hudIdleHide = 0;
        assertEquals(0, motion.hidden(settings, state("B", AudioBackend.Status.STOPPED), t + 1000 * SECOND), "Off keeps it");
    }

    @Test
    void fadeModeHidesSlowlyAndComesBackQuickly() {
        var settings = new Settings();
        settings.hudIdleHide = 1;
        settings.hudIdleAnimation = Settings.Motion.FADE;
        var motion = new HudMotion();
        long t = 10 * SECOND;
        motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), t + 2 * SECOND);
        // Paused from here at 60 frames a second: the fade starts after the one-second delay.
        double oneSecondIn = 0;
        for (int frame = 1; frame <= 120; frame++)
            oneSecondIn = motion.hidden(settings, state("A", AudioBackend.Status.PAUSED), t + 2 * SECOND + frame * SECOND / 60);
        assertTrue(oneSecondIn > .3 && oneSecondIn < .6, "A second into the fade it is still partly visible: " + oneSecondIn);
        for (int frame = 121; frame <= 300; frame++)
            motion.hidden(settings, state("A", AudioBackend.Status.PAUSED), t + 2 * SECOND + frame * SECOND / 60);
        assertTrue(motion.fullyHidden());
        long back = t + 10 * SECOND;
        motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), back);
        for (int frame = 1; frame <= 24; frame++)
            motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), back + frame * SECOND / 60);
        assertEquals(0, motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), back + SECOND / 2), "Returns in well under a second");
    }

    @Test
    void aNewSongFadesInAndColorsBelowAVisibleAlphaAreNotDrawn() {
        var settings = new Settings();
        var motion = new HudMotion();
        long t = 50 * SECOND;
        motion.hidden(settings, state("A", AudioBackend.Status.PLAYING), t);
        assertEquals(0, motion.arrival(t));
        assertEquals(1, motion.arrival(t + SECOND));
        assertEquals(0, HudMotion.fade(0xFFB5B0FF, 0), "Zero alpha text would draw opaque, so it is skipped");
        assertEquals(0x80B5B0FF, HudMotion.fade(0xFFB5B0FF, .5) & 0xFFFFFFFF, .0, "Half alpha keeps the color");
        assertEquals(0xFFB5B0FF, HudMotion.fade(0xFFB5B0FF, 1));
    }

    @Test
    void slidingLeavesThroughTheNearerSideEdge() {
        assertTrue(HudMotion.exit(10, 200, 960, 1) <= -(10 + 200), "A left-side HUD leaves to the left");
        assertTrue(10 + HudMotion.exit(700, 200, 960, 1) >= 960 - 700, "A right-side HUD leaves to the right");
        assertEquals(0, HudMotion.exit(700, 200, 960, 0));
    }

    @Test
    void theSongCardFollowsItsSettingAndSlidesInHoldsAndSlidesOut() {
        var settings = new Settings();
        assertFalse(SongCard.wanted(settings, false), "Off by default");
        settings.songCard = Settings.SongCard.HIDDEN_HUD;
        assertTrue(SongCard.wanted(settings, false));
        assertFalse(SongCard.wanted(settings, true));
        settings.songCard = Settings.SongCard.ALWAYS;
        assertTrue(SongCard.wanted(settings, true));
        var slide = Settings.Motion.SLIDE;
        assertEquals(0, SongCard.presence(slide, 0));
        assertEquals(1, SongCard.presence(slide, SongCard.SHOWN / 2));
        assertEquals(0, SongCard.presence(slide, SongCard.SHOWN + 1));
        assertTrue(SongCard.presence(slide, SongCard.SHOWN - HudMotion.SLIDE / 2) < 1, "Slides out at the end");
        // Fade: in quickly, hold, then out slowly.
        var fade = Settings.Motion.FADE;
        assertEquals(1, SongCard.presence(fade, SongCard.FADE_IN));
        assertEquals(1, SongCard.presence(fade, SongCard.FADE_IN + SongCard.FADE_HOLD));
        assertEquals(.5, SongCard.presence(fade, SongCard.FADE_IN + SongCard.FADE_HOLD + SongCard.FADE_OUT / 2), .001);
        assertTrue(SongCard.FADE_OUT > 4 * SongCard.FADE_IN, "The fade out is the slow part");
        assertEquals(0, SongCard.presence(fade, SongCard.duration(fade) + 1));
    }

    @Test
    void theNextSongLineGrowsOnlyTheClassicHudAndInvalidChoicesFallBack() {
        var settings = new Settings();
        int before = HudLayout.content(settings, Integer.MAX_VALUE).height();
        settings.hudNextSong = true;
        assertEquals(before + 11, HudLayout.content(settings, Integer.MAX_VALUE).height());
        settings.hudTime = true;
        assertEquals(before + 21, HudLayout.content(settings, Integer.MAX_VALUE).height(), "Time below the bar adds one line");
        settings.hudTimePosition = Settings.TimePosition.ARTIST_LINE;
        assertEquals(before + 11, HudLayout.content(settings, Integer.MAX_VALUE).height(), "On the artist line it adds nothing");
        settings.hudTimePosition = Settings.TimePosition.BELOW_BAR;
        settings.hudProgress = false;
        assertEquals(0, HudLayout.timeLine(settings), "Without a progress bar the time uses the artist line");
        settings.hudProgress = true;
        settings.hudStyle = Settings.HudStyle.CIRCULAR;
        assertEquals(0, HudLayout.nextLine(settings));
        assertEquals(0, HudLayout.timeLine(settings));
        var loaded = ConfigStore.JSON.fromJson("{\"hudIdleHide\":500,\"songCard\":\"SOMETIMES\",\"songCardX\":2,\"songCardY\":-1}", Settings.class);
        loaded.validate();
        assertEquals(Settings.IDLE_HIDE_MAX, loaded.hudIdleHide, "The slider's two minutes is the limit");
        assertEquals(1, loaded.songCardX, "Out-of-range card positions are clamped onto the screen");
        assertEquals(0, loaded.songCardY);
        assertEquals(Settings.Motion.SLIDE, loaded.hudIdleAnimation);
        assertEquals(Settings.Motion.SLIDE, loaded.songCardAnimation);
        var unplaced = ConfigStore.JSON.fromJson("{\"hudIdleHide\":-3,\"songCardX\":0.5}", Settings.class);
        unplaced.validate();
        assertEquals(0, unplaced.hudIdleHide);
        assertNull(unplaced.songCardX, "A card with only one coordinate goes back beside the HUD");
        assertEquals(Settings.SongCard.OFF, loaded.songCard);
        assertTrue(loaded.hudTransitions);
    }
}
