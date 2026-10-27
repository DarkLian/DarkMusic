package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

public final class LyricCandidateScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Track track;
    private final LyricsDocument document;
    private String error = "";

    public LyricCandidateScreen(DarkMusicClient app, Screen parent, Track track, LyricsDocument document) {
        super(Text.component("lyrics.preview"), parent);
        this.app = app;
        this.track = track;
        this.document = document;
    }

    @Override
    protected void init() {
        backButton();
        var rows = addRenderableWidget(new RowList(12, 64, width - 24, Math.max(20, height - 102), 24, title.getString(), (i, activate) -> {
        }));
        var lines = document.synchronizedLyrics() ? document.timed().lines().stream().map(l -> l.text()).toList() : document.plain();
        rows.rows(lines.stream().map(line -> new RowList.Row(line, "")).toList());
        addRenderableWidget(UiButton.builder(Text.component("lyrics.apply"), b -> {
            b.active = false;
            app.lyrics.lookup.select(track, document).whenComplete((done, failure) -> minecraft.execute(() -> {
                if (failure == null) onClose();
                else {
                    error = Text.get("lyrics.operation_failed");
                    b.active = true;
                }
            }));
        }).bounds(12, height - 28, width - 24, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.LYRICS);
        longText.begin();
        longText.draw(g, font, "identity", document.title() + " | " + document.artist(), 14, 35, width - 28, UiTheme.TEXT);
        longText.draw(g, font, "source", error.isEmpty() ? document.provider() + " | " + Text.get(document.generated() ? "lyrics.generated" : document.synchronizedLyrics() ? "lyrics.synced" : "lyrics.plain") : error, 14, 49, width - 28, error.isEmpty() ? UiStatus.color(UiStatus.Tone.INFO) : UiTheme.ERROR);
        super.extractRenderState(g, x, y, delta);
    }
}
