package com.darkmusic.client.gui;

import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.*;

/**
 * An explicit choice for confirmed source copies, separate from fuzzy cleanup suggestions.
 */
public final class DownloadDuplicateScreen extends LocalScreen {
    /**
     * The downloader waiting on this choice, artwork, and the file browser.
     */
    public interface Sources {
        DownloadService downloads();

        DownloadOperations downloadOperations();

        ArtworkCache artwork();

        void openFolder(Path directory);
    }

    private final Sources app;
    private final DownloadService.DuplicateChoice choice;
    private List<LibrarySourceIndex.Existing> files = List.of();
    private int selected = -1;

    public DownloadDuplicateScreen(Sources app, Screen parent, DownloadService.DuplicateChoice choice) {
        super(Text.component("download.library.review"), parent);
        this.app = app;
        this.choice = choice;
    }

    @Override
    protected void init() {
        backButton();
        files = choice.matches().values().stream().flatMap(List::stream).distinct().toList();
        var rows = addRenderableWidget(new RowList(12, 72, width - 24, Math.max(28, height - 140), 36, Text.get("download.library.existing"), (i, activated) -> {
            selected = i;
            if (activated) open();
        }));
        rows.rows(files.size(), i -> {
            var f = files.get(i);
            return new RowList.Row(f.track().title() + " | " + f.format(), f.track().file().toString());
        });
        rows.artwork(app.artwork(), i -> files.get(i).track());
        rows.rowTooltips(false);
        int w = (width - 32) / 3;
        addRenderableWidget(UiButton.builder(Text.component("download.library.skip"), b -> {
            app.downloadOperations().resolveDuplicateChoice(choice.id(), true);
            onClose();
        }).bounds(12, height - 28, w, 20).build());
        addRenderableWidget(UiButton.builder(Text.component("download.library.anyway"), b -> {
            app.downloadOperations().resolveDuplicateChoice(choice.id(), false);
            onClose();
        }).bounds(16 + w, height - 28, w, 20).build());
        addRenderableWidget(UiButton.builder(Text.component("cleanup.location"), b -> open()).bounds(20 + w * 2, height - 28, w, 20).build());
        selected = files.isEmpty() ? -1 : 0;
        rows.select(selected);
    }

    private void open() {
        if (selected >= 0 && selected < files.size()) app.openFolder(files.get(selected).track().file().getParent());
    }

    @Override
    protected Screen returnScreen() {
        app.downloadOperations().cancelDuplicateChoice(choice.id());
        return super.returnScreen();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.WARNING);
        longText.draw(g, font, "summary", Text.get("download.library.warning", choice.matches().size()), 12, 36, width - 24, UiTheme.WARNING);
        longText.draw(g, font, "format", Text.get("download.library.format_help"), 12, 52, width - 24, UiTheme.SECONDARY);
        longText.draw(g, font, "help", Text.get("download.library.no_fuzzy_skip"), 12, height - 50, width - 24, UiTheme.MUTED);
        super.extractRenderState(g, x, y, delta);
    }
}
