package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.*;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Search drafts do not modify music tags or replace the current lyric selection.
 */
public final class LyricsSearchScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Track track;
    private UiInput song, artist;
    private RowList rows;
    private Button preview;
    private LyricsLookup.Snapshot shown;
    private List<LyricsProvider.Candidate> candidates = List.of();
    private int selected = -1;

    public LyricsSearchScreen(DarkMusicClient app, Screen parent, Track track) {
        super(Text.component("lyrics.search"), parent);
        this.app = app;
        this.track = track;
    }

    @Override
    protected void init() {
        backButton();
        String songDraft = song == null ? track.title() : song.getValue();
        String artistDraft = artist == null ? track.artist() : artist.getValue();
        int half = (width - 28) / 2;
        song = addRenderableWidget(new UiInput(font, 12, 36, half, 20, Text.component("lyrics.search_title")));
        artist = addRenderableWidget(new UiInput(font, 16 + half, 36, half, 20, Text.component("lyrics.search_artist")));
        song.setMaxLength(300);
        artist.setMaxLength(300);
        song.setValue(songDraft);
        artist.setValue(artistDraft);
        Help.tip(song, Text.get("lyrics.search_title"));
        Help.tip(artist, Text.get("lyrics.search_artist"));
        var search = addRenderableWidget(UiButton.builder(Text.component("lyrics.search"), b -> {
            app.lyrics.lookup.search(track, new LyricsProvider.Query(song.getValue().strip(), artist.getValue().strip(), track.album(), track.duration()), false);
        }).bounds(12, 60, half, 20).build());
        var source = addRenderableWidget(UiButton.builder(Text.component("lyrics.source_search"), b ->
            app.lyrics.lookup.source(track, app.settings.lyricsLanguages, app.settings.lyricsAutoCaptions)).bounds(16 + half, 60, half, 20).build());
        search.active = source.active = app.settings.lyricsEnabled && app.settings.lyricsOnline;
        Help.tip(source, Text.get("lyrics.source_search.help"));
        rows = addRenderableWidget(new RowList(12, 102, width - 24, Math.max(20, height - 138), 36, title.getString(), (i, activate) -> {
            selected = i;
            if (activate) preview();
        }));
        rows.emptyMessage(Text.get(app.settings.lyricsOnline ? "lyrics.search_prompt" : "lyrics.online_required"));
        int third = (width - 32) / 3;
        preview = addRenderableWidget(UiButton.builder(Text.component("lyrics.preview"), b -> preview()).bounds(12, height - 28, third, 20).build());
        addRenderableWidget(UiButton.builder(Text.component("lyrics.refresh"), b -> app.lyrics.lookup.search(track,
                new LyricsProvider.Query(song.getValue().strip(), artist.getValue().strip(), track.album(), track.duration()), true))
            .bounds(16 + third, height - 28, third, 20).build()).active = app.settings.lyricsEnabled && app.settings.lyricsOnline;
        addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.cancel"), b -> app.lyrics.lookup.cancel(track)).bounds(20 + 2 * third, height - 28, third, 20).build());
        shown = null;
        tick();
    }

    private void preview() {
        if (selected >= 0 && selected < candidates.size())
            show(new LyricCandidateScreen(app, this, track, candidates.get(selected).document()));
    }

    @Override
    public void tick() {
        var snapshot = app.lyrics.lookup.snapshot(track);
        if (shown != snapshot) {
            String id = selected >= 0 && selected < candidates.size() ? candidates.get(selected).document().provider() + ":" + candidates.get(selected).document().recordId() : "";
            shown = snapshot;
            candidates = snapshot.candidates();
            selected = -1;
            rows.rows(candidates.stream().map(c -> {
                var d = c.document();
                return new RowList.Row(d.title() + " | " + d.artist(), Text.get(d.synchronizedLyrics() ? "lyrics.synced" : d.instrumental() ? "lyrics.instrumental" : "lyrics.plain")
                    + " | " + d.provider() + " | " + MusicScreen.time(d.duration()) + " | " + Text.get(c.reliable() ? "lyrics.match.reliable" : "lyrics.match.review"), d.album() + "\n" + Text.get(c.evidence()) + "\n" + d.language() + " | " + Text.get("lyrics.variant." + d.variant().name().toLowerCase(java.util.Locale.ROOT)));
            }).toList());
            for (int i = 0; i < candidates.size(); i++)
                if (id.equals(candidates.get(i).document().provider() + ":" + candidates.get(i).document().recordId()))
                    selected = i;
            rows.select(selected);
        }
        preview.active = selected >= 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.LYRICS);
        longText.begin();
        String status = Text.get("lyrics.status." + app.lyrics.lookup.snapshot(track).status().name().toLowerCase(java.util.Locale.ROOT));
        long retry = app.lyrics.lookup.retrySeconds(track);
        if (retry > 0) status += " | " + Text.get("lyrics.retry_in", retry);
        longText.drawUpdating(g, font, "status", status, 14, 87, width - 28, UiTheme.SECONDARY);
        super.extractRenderState(g, x, y, delta);
    }
}
