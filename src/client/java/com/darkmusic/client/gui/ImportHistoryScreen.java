package com.darkmusic.client.gui;

import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ImportHistoryScreen extends LocalScreen {
    /**
     * Past imports.
     */
    public interface Sources {
        ImportHistory importHistory();
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final Sources app;
    private final SongSelection selection = new SongSelection();
    private RowList rows;
    // Listed records by selection key, in list order.
    private Map<String, ImportHistory.Item> shown = Map.of();
    private Button remove;
    private long revision = -1;

    public ImportHistoryScreen(Sources app, Screen parent) {
        super(Text.component("import.darkmusic.history"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        backButton();
        rows = addRenderableWidget(new RowList(12, 42, Math.max(1, width - 24), Math.max(28, height - 114), 36, Text.get("import.darkmusic.history"), (i, activated) -> {
        }));
        rows.multiple(selection);
        rows.emptyMessage(Text.get("import.darkmusic.history_empty"));
        int w = (width - 32) / 3;
        button("text.darkmusic.select_all", 12, height - 66, w, selection::all);
        button("text.darkmusic.deselect_all", 16 + w, height - 66, w, selection::clear);
        button("text.darkmusic.invert_selection", 20 + w * 2, height - 66, w, selection::invert);
        remove = button("import.history.remove", 12, height - 28, width - 24, () -> {
            var chosen = selection.selected().stream().map(shown::get).filter(item -> item != null).toList();
            if (chosen.isEmpty() || app.importHistory() == null) return;
            show(new ConfirmationScreen(this, Text.component("import.history.remove"),
                Text.component("import.history.remove_confirm", chosen.size()), Text.component("import.history.remove"), () -> {
                app.importHistory().remove(chosen);
                selection.clear();
                return CompletableFuture.completedFuture(null);
            }));
        });
        UiButton.style(remove, UiButton.Style.DESTRUCTIVE);
        revision = -1;
        tick();
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        return addRenderableWidget(UiButton.builder(Text.component(key), b -> action.run()).bounds(x, y, w, 20).build());
    }

    @Override
    public void tick() {
        var history = app.importHistory();
        if (history != null && revision != history.revision()) {
            revision = history.revision();
            int scroll = rows.scrollOffset();
            var list = new ArrayList<RowList.Row>();
            var records = new LinkedHashMap<String, ImportHistory.Item>();
            for (var batch : history.batches()) {
                String when = DATE.format(Instant.ofEpochMilli(batch.timestamp()));
                String batchKey = batch.timestamp() + "\u0000" + batch.destination() + "\u0000";
                if (!batch.error().isBlank()) {
                    list.add(new RowList.Row(Text.get("import.darkmusic.failed"), when + " | " + batch.destination(), batch.error()).status(batch.error(), UiStatus.Tone.ERROR));
                    records.put(batchKey + "failure", new ImportHistory.Item(batch, null));
                }
                for (var item : batch.entries()) {
                    String path = item.source().replace('\\', '/');
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    String status = Text.get(item.success() ? "import.darkmusic.success" : "import.darkmusic.failed");
                    list.add(new RowList.Row(name, when + " | " + batch.destination(), item.source() + "\n" + item.destination()).status(status + (item.success() ? "" : " | " + item.message()), item.success() ? UiStatus.Tone.SUCCESS : UiStatus.Tone.ERROR));
                    records.put(batchKey + item.source() + "\u0000" + item.destination() + "\u0000" + item.success() + "\u0000" + item.message(), new ImportHistory.Item(batch, item));
                }
            }
            shown = records;
            selection.retain(records.keySet());
            selection.visible(new ArrayList<>(records.keySet()));
            rows.rows(list);
            rows.scrollOffset(scroll);
        }
        remove.active = !selection.isEmpty();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.PLAYLIST);
        longText.draw(g, font, "selection", Text.get("format.darkmusic.selected", selection.size()), 12, height - 40, width - 24, UiStatus.color(UiStatus.Tone.INFO));
        super.extractRenderState(g, x, y, delta);
    }
}
