package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.cleanup.*;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.*;

/**
 * Pair rows preserve real evidence; related rows never imply untested transitive matches.
 */
public final class SmartCleanupScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final DuplicateScanner scanner;
    private final DuplicateReviewStore reviews;
    private RowList rows;
    private Button start, cancel, sensitivity, audio, review;
    private DuplicatePolicy policy = DuplicatePolicy.BALANCED;
    private boolean analyzeAudio;
    private String query = "", selected = "";
    private long revision = -1, reviewRevision = -1;
    private List<DuplicateResult> visible = List.of();
    private List<DuplicateGroups.Group> groups = List.of();
    private List<DuplicateResult> groupedPairs;
    private String groupedQuery = "";

    private record ResultRow(DuplicateGroups.Group group, DuplicateResult pair) {
        String id() {
            return pair == null ? "group:" + group.id() : pair.id();
        }
    }

    private List<ResultRow> displayed = List.of();
    private final Set<String> expanded = new HashSet<>();

    public SmartCleanupScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("cleanup.title"), parent);
        this.app = app;
        scanner = app.cleanup();
        reviews = app.cleanupReviews();
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        return addRenderableWidget(UiButton.builder(Text.component(key), b -> action.run()).bounds(x, y, width, 20).build());
    }

    @Override
    protected void init() {
        backButton();
        int half = (width - 28) / 2;
        sensitivity = button("cleanup.sensitivity", 12, 34, half, () -> {
            policy = DuplicatePolicy.values()[(policy.ordinal() + 1) % 3];
            updateButtons();
        });
        audio = button("cleanup.audio", 16 + half, 34, half, () -> {
            analyzeAudio = !analyzeAudio;
            updateButtons();
        });
        Help.tip(sensitivity, Text.get("cleanup.sensitivity.help"));
        Help.tip(audio, Text.get("cleanup.audio.help"));
        var search = addRenderableWidget(new UiInput(font, 12, 60, width - 24, 20, Text.component("text.darkmusic.search_songs")));
        search.setValue(query);
        search.setHint(Text.component("cleanup.search"));
        search.setResponder(value -> {
            query = value;
            refresh(true);
        });
        rows = addRenderableWidget(new RowList(12, 86, width - 24, Math.max(28, height - 164), 36, Text.get("cleanup.results"), (i, activated) -> {
            if (i >= 0 && i < displayed.size()) {
                var row = displayed.get(i);
                selected = row.id();
                if (row.pair() == null) toggle(row.group());
                else if (activated) compare();
            }
        }));
        rows.emptyMessage(Text.get("cleanup.empty"));
        rows.rowTooltips(false);
        rows.icons(i -> displayed.get(i).pair() == null ? UiIcon.FOLDER : UiIcon.MUSIC);
        rows.indent(i -> displayed.get(i).pair() == null ? 0 : 12);
        rows.expanded(i -> expanded.contains(displayed.get(i).group().id()));
        rows.disclosure(i -> displayed.get(i).pair() == null ? 4 : -1, i -> toggle(displayed.get(i).group()));
        int w = (width - 36) / 4, y = height - 28;
        start = button("cleanup.start", 12, y, w, () -> {
            if (!app.mediaBusy && !app.library.snapshot().scanning())
                scanner.start(app.library.snapshot().tracks(), policy, analyzeAudio);
        });
        cancel = button("cleanup.cancel", 16 + w, y, w, scanner::cancel);
        review = button("cleanup.review", 20 + w * 2, y, w, this::compare);
        button("cleanup.ignored", 24 + w * 3, y, w, () -> show(new IgnoredMatchesScreen(app, this)));
        Help.tip(review, Text.get("cleanup.review.help"));
        revision = -1;
        refresh(true);
        updateButtons();
    }

    private void compare() {
        visible.stream().filter(p -> p.id().equals(selected)).findFirst().ifPresent(p -> {
            if (!app.mediaBusy) show(new DuplicateComparisonScreen(app, this, p));
        });
    }

    private void toggle(DuplicateGroups.Group group) {
        selected = "group:" + group.id();
        if (!expanded.remove(group.id())) expanded.add(group.id());
        refresh(true);
        updateButtons();
    }

    private void refresh(boolean force) {
        var state = scanner.snapshot();
        if (!force && revision == state.revision() && reviewRevision == reviews.revision()) return;
        int scroll = rows.scrollOffset();
        String find = query.strip().toLowerCase(Locale.ROOT);
        boolean changed = groupedPairs != state.pairs() || reviewRevision != reviews.revision() || !find.equals(groupedQuery);
        if (!changed && !force) { revision = state.revision(); return; }
        if (changed) {
            visible = state.pairs().stream().filter(p -> !reviews.contains(p) && p.matches(find)).toList();
            groups = DuplicateGroups.build(visible);
            groupedPairs = state.pairs();
            groupedQuery = find;
        }
        var next = new ArrayList<ResultRow>();
        for (var group : groups) {
            if (group.pairs().stream().anyMatch(p -> p.id().equals(selected))) expanded.add(group.id());
            next.add(new ResultRow(group, null));
            if (expanded.contains(group.id())) for (var pair : group.pairs()) next.add(new ResultRow(group, pair));
        }
        displayed = List.copyOf(next);
        var list = displayed;
        rows.rows(list.size(), i -> {
            var row = list.get(i);
            if (row.pair() == null)
                return new RowList.Row(Text.get("cleanup.group", row.group().tracks().size(), row.group().pairs().size()),
                    row.group().tracks().stream().map(t -> t.title()).collect(java.util.stream.Collectors.joining(" / ")));
            var p = row.pair();
            return new RowList.Row(p.a().title() + "  /  " + p.b().title(),
                Text.get("cleanup.strength." + p.strength().name().toLowerCase(Locale.ROOT)) + " | " + reasons(p));
        });
        int index = java.util.stream.IntStream.range(0, list.size()).filter(i -> list.get(i).id().equals(selected)).findFirst().orElse(-1);
        rows.select(index);
        rows.scrollOffset(scroll);
        revision = state.revision();
        reviewRevision = reviews.revision();
    }

    static String reasons(DuplicateResult pair) {
        return pair.evidence().stream().sorted().map(e -> Text.get("cleanup.evidence." + e.name().toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.joining("; "));
    }

    private void updateButtons() {
        var state = scanner.snapshot();
        boolean busy = state.running();
        start.active = !busy && !app.mediaBusy && !app.library.snapshot().scanning();
        cancel.active = busy;
        sensitivity.active = audio.active = !busy;
        sensitivity.setMessage(net.minecraft.network.chat.Component.literal(Text.get("cleanup.sensitivity") + ": " + Text.get("cleanup.policy." + policy.name().toLowerCase(Locale.ROOT))));
        audio.setMessage(net.minecraft.network.chat.Component.literal(Text.get("cleanup.audio") + ": " + Text.get(analyzeAudio ? "cleanup.enabled" : "cleanup.disabled")));
        review.active = !app.mediaBusy && visible.stream().anyMatch(p -> p.id().equals(selected));
    }

    @Override
    public void tick() {
        refresh(false);
        updateButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.SEARCH);
        var s = scanner.snapshot();
        String status = Text.get("cleanup.stage." + s.stage().name().toLowerCase(Locale.ROOT)) + " | " + Text.get("cleanup.progress", s.done(), s.total(), visible.size());
        if (app.library.snapshot().scanning()) status = Text.get("cleanup.wait_scan");
        longText.draw(g, font, "progress", status, 12, height - 72, width - 24, UiTheme.SECONDARY);
        String details = !reviews.error().isEmpty() ? Text.get(reviews.error()) : s.cacheFailed() ? Text.get("cleanup.cache_failed") : s.limited() ? Text.get("cleanup.limited") : Text.get("cleanup.skipped", s.unavailable(), s.audioFailed());
        longText.draw(g, font, "details", details, 12, height - 56, width - 24, s.cacheFailed() || s.limited() ? UiTheme.WARNING : UiTheme.MUTED);
        super.extractRenderState(g, x, y, delta);
    }
}
