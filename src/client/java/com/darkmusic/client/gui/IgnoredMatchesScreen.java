package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.cleanup.DuplicateReviewStore;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

public final class IgnoredMatchesScreen extends LocalScreen {
    private final DuplicateReviewStore store;
    private RowList rows;
    private List<DuplicateReviewStore.Ignored> shown = List.of();
    private String selected = "";
    private long revision = -1;
    private net.minecraft.client.gui.components.Button restore, clear;

    public IgnoredMatchesScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("cleanup.ignored"), parent);
        store = app.cleanupReviews();
    }

    @Override
    protected void init() {
        backButton();
        rows = addRenderableWidget(new RowList(12, 38, width - 24, height - 100, 36, Text.get("cleanup.ignored"), (i, a) -> {
            if (i < shown.size()) selected = shown.get(i).key();
        }));
        rows.emptyMessage(Text.get("cleanup.no_ignored"));
        rows.rowTooltips(false);
        int w = (width - 28) / 2;
        restore = addRenderableWidget(UiButton.builder(Text.component("cleanup.restore"), b -> store.restore(selected)).bounds(12, height - 28, w, 20).build());
        clear = addRenderableWidget(UiButton.builder(Text.component("cleanup.restore_all"), b -> show(new ConfirmationScreen(this, Text.component("cleanup.restore_all"), Text.component("cleanup.restore_confirm"), Text.component("cleanup.restore_all"), () -> {
            store.clear();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }))).bounds(16 + w, height - 28, w, 20).build());
        revision = -1;
        tick();
    }

    @Override
    public void tick() {
        if (revision != store.revision()) {
            int scroll = rows.scrollOffset();
            shown = store.snapshot();
            var list = shown;
            rows.rows(list.size(), i -> new RowList.Row(list.get(i).titleA() + " / " + list.get(i).titleB(), list.get(i).pathA() + " / " + list.get(i).pathB()));
            rows.select(java.util.stream.IntStream.range(0, shown.size()).filter(i -> shown.get(i).key().equals(selected)).findFirst().orElse(-1));
            rows.scrollOffset(scroll);
            revision = store.revision();
        }
        restore.active = store.ready() && shown.stream().anyMatch(p -> p.key().equals(selected));
        clear.active = store.ready() && !shown.isEmpty();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.SEARCH);
        longText.begin();
        longText.draw(g, font, "notice", Text.get(store.error().isEmpty() ? "cleanup.restore.help" : store.error()), 12, height - 50, width - 24, store.error().isEmpty() ? UiStatus.color(UiStatus.Tone.INFO) : UiTheme.ERROR);
        super.extractRenderState(g, x, y, delta);
    }
}
