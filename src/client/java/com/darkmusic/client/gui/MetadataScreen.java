package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.MediaFiles;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Consumer;

public final class MetadataScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Track track;
    private final Consumer<Track> saved;
    private final Screen parent;
    private MediaFiles.Fields original;
    private EditBox song, artist, album, filename;
    private Button save, delete;
    private String error = "";
    private boolean loading, saving;
    private int left, top, panelWidth;

    public MetadataScreen(DarkMusicClient app, Screen parent, Track track, Consumer<Track> saved) {
        super(Text.component("library.darkmusic.edit_metadata"), parent);
        this.app = app;
        this.parent = parent;
        this.track = track;
        this.saved = saved;
    }

    @Override
    protected void init() {
        String[] draft = song == null ? null : new String[]{song.getValue(), artist.getValue(), album.getValue(), filename.getValue()};
        panelWidth = Math.min(420, width - 24);
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 232) / 2);
        song = input("text.darkmusic.song_title", top + 43);
        artist = input("text.darkmusic.artist", top + 79);
        album = input("library.darkmusic.album", top + 115);
        filename = input("library.darkmusic.filename", top + 151);
        if (draft != null) {
            song.setValue(draft[0]);
            artist.setValue(draft[1]);
            album.setValue(draft[2]);
            filename.setValue(draft[3]);
        } else if (original != null) fill();
        int actionWidth = (panelWidth - 28) / 2;
        save = addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.save"), b -> save()).bounds(left + 12, top + 181, actionWidth, 20).build());
        delete = addRenderableWidget(UiButton.builder(Text.component("delete_audio.single"), b -> {
            if (!saving && !app.mediaBusy) show(new AudioDeletionScreen(app, this, java.util.List.of(track), parent));
        }).bounds(left + 16 + actionWidth, top + 181, actionWidth, 20).build());
        UiButton.style(delete, UiButton.Style.DESTRUCTIVE);
        UiButton.style(save, UiButton.Style.PRIMARY);
        save.active = original != null && !saving;
        if (!loading && original == null) {
            loading = true;
            app.readMetadata(track).whenComplete((fields, failure) -> minecraft.execute(() -> {
                if (failure != null) {
                    error = message(failure);
                    return;
                }
                original = fields;
                if (isCurrent()) {
                    fill();
                    save.active = true;
                }
            }));
        }
    }

    private EditBox input(String key, int y) {
        var box = addRenderableWidget(new UiInput(font, left + 12, y, panelWidth - 24, 20, Text.component(key)));
        box.setMaxLength(512);
        return box;
    }

    private void fill() {
        song.setValue(original.title());
        artist.setValue(original.artist());
        album.setValue(original.album());
        filename.setValue(original.filename());
    }

    private static String message(Throwable failure) {
        return Text.failure(failure, "library.darkmusic.edit_failed");
    }

    private void save() {
        if (original == null || saving) return;
        saving = true;
        save.active = false;
        error = "";
        var draft = new MediaFiles.Fields(song.getValue(), artist.getValue(), album.getValue(), filename.getValue(), original.extension());
        app.editMetadata(track, original, draft).whenComplete((edited, failure) -> minecraft.execute(() -> {
            saving = false;
            if (failure != null) {
                error = message(failure);
                if (save != null) save.active = true;
            } else {
                saved.accept(edited);
                show(parent);
            }
        }));
    }

    @Override
    public void tick() {
        boolean editable = original != null && !saving;
        delete.active = !saving && !app.mediaBusy;
        for (EditBox box : new EditBox[]{song, artist, album, filename}) box.setEditable(editable);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.panel(g, left, top, panelWidth, 232);
        g.text(font, title, left + 12, top + 12, UiTheme.TEXT);
        String[] keys = {"text.darkmusic.song_title", "text.darkmusic.artist", "library.darkmusic.album", "library.darkmusic.filename"};
        for (int i = 0; i < keys.length; i++)
            g.text(font, Text.get(keys[i]) + (i == 3 && original != null ? " (" + original.extension() + ")" : ""), left + 12, top + 30 + i * 36, UiTheme.SECONDARY);
        longText.draw(g, font, "status", saving ? Text.get("library.darkmusic.saving") : original == null && error.isEmpty() ? Text.get("text.darkmusic.loading") : error, left + 12, top + 216, panelWidth - 24, UiTheme.WARNING);
        super.extractRenderState(g, x, y, delta);
    }
}
