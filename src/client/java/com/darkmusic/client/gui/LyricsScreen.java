package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Timed playback view and readable untimed text share the selected lyric document.
 */
public final class LyricsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Track fixed;
    private RowList plain;
    private net.minecraft.client.gui.components.Button search, versions, options;
    private LyricsDocument rendered;
    private int first;
    private boolean browsing;

    public LyricsScreen(DarkMusicClient app, Screen parent) {
        this(app, parent, null);
    }

    public LyricsScreen(DarkMusicClient app, Screen parent, Track track) {
        super(Text.component("lyrics.darkmusic.title"), parent);
        this.app = app;
        fixed = track;
    }

    private Track track() {
        return fixed == null ? app.playback.state().track() : fixed;
    }

    @Override
    protected void init() {
        backButton();
        int w = (width - 32) / 3;
        search = addRenderableWidget(UiButton.builder(Text.component("lyrics.search"), b -> show(new LyricsSearchScreen(app, this, track())))
            .bounds(12, height - 28, w, 20).build());
        versions = addRenderableWidget(UiButton.builder(Text.component("lyrics.versions"), b -> show(new LyricsSearchScreen(app, this, track())))
            .bounds(16 + w, height - 28, w, 20).build());
        options = addRenderableWidget(UiButton.builder(Text.component("lyrics.options"), b -> show(new LyricsOptionsScreen(app, this, track())))
            .bounds(20 + 2 * w, height - 28, w, 20).build());
        search.active = versions.active = options.active = track() != null && app.settings.lyricsEnabled;
        plain = addRenderableWidget(new RowList(16, 84, width - 32, Math.max(20, height - 120), 24, Text.get("lyrics.plain"), (i, activate) -> {
        }));
        plain.visible = false;
        rendered = null;
    }

    @Override
    public void tick() {
        var track = track();
        search.active = versions.active = options.active = track != null && app.settings.lyricsEnabled;
        app.lyrics.request(track);
        var document = app.lyrics.document(track);
        if (app.settings.lyricsAutomaticLookup && app.lyrics.inspected(track))
            app.lyrics.lookup.automatic(track, document.available());
        plain.visible = document.available() && !document.synchronizedLyrics() && !document.instrumental();
        if (rendered != document) {
            rendered = document;
            first = 0;
            browsing = false;
            plain.rows(document.plain().stream().map(line -> new RowList.Row(line, "")).toList());
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        var data = app.lyrics.get(track());
        if (!data.lines().isEmpty() && y >= 84 && y < height - 36 && !HorizontalText.horizontalIntent(dx, dy)) {
            if (!browsing) first = Math.max(0, current() - Math.max(1, (height - 120) / 24) / 2);
            first = Math.clamp(first - (int) Math.round(dy * 3), 0, Math.max(0, data.lines().size() - 1));
            browsing = true;
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    private int current() {
        var state = app.playback.state();
        return track() != null && state.track() != null && track().id().equals(state.track().id()) ? app.lyrics.get(track()).current(state.position()) : -1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.LYRICS);
        UiTheme.panel(g, 12, 32, width - 24, Math.max(30, height - 68));
        var track = track();
        var document = app.lyrics.document(track);
        if (track != null)
            longText.draw(g, font, "track", track.title() + " | " + track.artist(), 22, 42, width - 44, UiTheme.TEXT);
        String language = app.lyrics.lookup.choice(track).language();
        if (language.equals("auto"))
            language = document.language().equals("und") ? Text.get("lyrics.script." + document.script().name().toLowerCase(java.util.Locale.ROOT)) : document.language();
        longText.draw(g, font, "provider", Text.get("lyrics.provenance", Text.get("lyrics.provider." + (document.provider().isBlank() ? "unknown" : document.provider())),
            Text.get(document.synchronizedLyrics() ? "lyrics.synced" : "lyrics.plain"), language,
            document.generated() ? Text.get("lyrics.generated") : ""), 22, 60, width - 44, UiTheme.SECONDARY);
        var data = app.lyrics.get(track);
        int current = current();
        if (!app.settings.lyricsEnabled || !document.available() || document.instrumental()) {
            var status = app.lyrics.lookup.snapshot(track).status();
            String key = !app.settings.lyricsEnabled ? "lyrics.darkmusic.disabled" : document.instrumental() ? "lyrics.instrumental"
                : !app.lyrics.inspected(track) || status == LyricsLookup.Status.SEARCHING ? "lyrics.status.searching"
                : status != LyricsLookup.Status.IDLE && status != LyricsLookup.Status.DISABLED
                ? "lyrics.status." + status.name().toLowerCase(java.util.Locale.ROOT) : "lyrics.find_prompt";
            g.centeredText(font, Text.get(key), width / 2, Math.max(90, height / 2), UiStatus.color(!app.settings.lyricsEnabled ? UiStatus.Tone.WARNING : document.instrumental() ? UiStatus.Tone.SUCCESS : UiStatus.lyrics(status)));
        } else if (document.synchronizedLyrics()) {
            int visible = Math.max(1, (height - 120) / 24);
            int start = browsing ? first : Math.max(0, current - visible / 2);
            g.enableScissor(16, 84, width - 16, height - 36);
            for (int i = start; i < Math.min(data.lines().size(), start + visible); i++) {
                int yy = 84 + (i - start) * 24;
                if (i == current) g.fill(16, yy, width - 16, yy + 22, UiTheme.SELECTED);
                String line = data.lines().get(i).text();
                int measured = font.width(line);
                longText.draw(g, font, "line" + i, line, measured <= width - 44 ? (width - measured) / 2 : 22, yy + 6,
                    Math.min(measured + 1, width - 44), i == current ? UiTheme.TEXT : UiTheme.MUTED);
            }
            g.disableScissor();
        }
        super.extractRenderState(g, x, y, delta);
    }

    @Override
    public net.minecraft.network.chat.Component getNarrationMessage() {
        var track = track();
        var data = app.lyrics.get(track);
        int current = current();
        return net.minecraft.network.chat.Component.literal(title.getString() + (track == null ? "" : ": " + track.title())
            + (current >= 0 && current < data.lines().size() ? ": " + data.lines().get(current).text() : ""));
    }
}
