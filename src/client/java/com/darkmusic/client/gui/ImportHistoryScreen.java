package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public final class ImportHistoryScreen extends LocalScreen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final DarkMusicClient app;
    private RowList rows;
    private long revision = -1;

    public ImportHistoryScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("import.darkmusic.history"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        backButton();
        rows = addRenderableWidget(new RowList(12, 42, Math.max(1, width - 24), Math.max(1, height - 54), 36, Text.get("import.darkmusic.history"), (i, activated) -> {
        }));
        rows.emptyMessage(Text.get("import.darkmusic.history_empty"));
        revision = -1;
        tick();
    }

    @Override
    public void tick() {
        if (app.importHistory == null || revision == app.importHistory.revision()) return;
        revision = app.importHistory.revision();
        var list = new ArrayList<RowList.Row>();
        for (var batch : app.importHistory.batches()) {
            String when = DATE.format(Instant.ofEpochMilli(batch.timestamp()));
            if (!batch.error().isBlank())
                list.add(new RowList.Row(Text.get("import.darkmusic.failed"), when + " | " + batch.destination(), batch.error()));
            for (var item : batch.entries()) {
                String path = item.source().replace('\\', '/');
                String name = path.substring(path.lastIndexOf('/') + 1);
                String status = Text.get(item.success() ? "import.darkmusic.success" : "import.darkmusic.failed");
                list.add(new RowList.Row(name + " | " + status, when + " | " + batch.destination() + (item.success() ? "" : " | " + item.message()), item.source() + "\n" + item.destination()));
            }
        }
        rows.rows(list);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.PLAYLIST);
        super.extractRenderState(g, x, y, delta);
    }
}
