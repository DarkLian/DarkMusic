package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.download.DownloadModels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkmusic.client.download.DownloadModels.*;

public final class DownloadScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final SongSelection selection = new SongSelection();
    private final Map<String, Entry> known = new LinkedHashMap<>();
    private EditBox url, first, last;
    private RowList rows;
    private Button analyze, download, modeButton, previous, next, selectAll, deselect;
    private Preview displayed;
    private Snapshot rowsSnapshot;
    private boolean rowsQueue;
    private Mode mode = Mode.VIDEO;
    private boolean queueView;
    private String input = "", from = "10", to = "30", notice = "";
    private long selectedJob = -1;

    public DownloadScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.downloads"), parent);
        this.app = app;
    }

    private static String key(Entry e) {
        return e.index() + ":" + e.key();
    }

    private Button button(String label, int x, int y, int w, Runnable action, String help) {
        return addRenderableWidget(Help.tip(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build(), help));
    }

    @Override
    protected void init() {
        rowsSnapshot = null;
        button(Text.get("text.darkmusic.tools"), width - 82, 6, 70, () -> show(new DownloadToolsScreen(app, this)), Text.get("text.darkmusic.set_executable_paths_and_check_yt_dlp_ffmpeg_ffprobe_and_optional_javascript_runti"));
        url = addRenderableWidget(new EditBox(font, 12, 30, width - 116, 20, Text.component("text.darkmusic.video_or_collection_url")));
        url.setMaxLength(4096);
        url.setValue(input);
        url.setHint(Text.component("text.darkmusic.paste_a_video_or_collection_url"));
        url.setResponder(s -> {
            input = s;
            notice = "";
        });
        analyze = button(Text.get("text.darkmusic.analyze"), width - 100, 30, 88, () -> {
            notice = "";
            selection.clear();
            known.clear();
            app.downloads.analyze(input, 0);
        }, Text.get("text.darkmusic.identify_the_source_and_preview_up_to_100_entries_nothing_downloads_automatically"));
        int half = (width - 28) / 2;
        modeButton = button(Text.get("format.darkmusic.mode", mode.label()), 12, 54, half, () -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            updateButtons();
        }, Text.get("text.darkmusic.choose_this_video_only_selected_preview_entries_first_n_a_range_or_explicitly_all"));
        first = addRenderableWidget(Help.tip(new EditBox(font, 16 + half, 54, (half - 4) / 2, 20, Text.component("text.darkmusic.first_n_or_range_start")), Text.get("text.darkmusic.first_n_number_of_entries_range_first_entry_number_starting_at_1")));
        first.setMaxLength(7);
        first.setValue(from);
        first.setResponder(s -> from = s);
        last = addRenderableWidget(Help.tip(new EditBox(font, 20 + half + (half - 4) / 2, 54, (half - 4) / 2, 20, Text.component("text.darkmusic.range_end")), Text.get("text.darkmusic.range_last_entry_number_inclusive_for_10_30_enter_10_and_30")));
        last.setMaxLength(7);
        last.setValue(to);
        last.setResponder(s -> to = s);
        download = button(Text.get("text.darkmusic.download"), 12, 78, half, this::download, Text.get("text.darkmusic.queue_only_the_explicitly_chosen_selection_into_the_current_music_folder_up_to_1_0"));
        button(Text.get("text.darkmusic.show_queue_preview"), 16 + half, 78, half, () -> {
            queueView = !queueView;
            rows.multiple(queueView ? null : selection);
            refreshRows();
        }, Text.get("text.darkmusic.switch_between_collection_preview_and_download_queue_closing_this_screen_does_not"));
        int w = (width - 36) / 4;
        previous = button(Text.get("text.darkmusic.prev_100"), 12, 102, w, () -> page(-1), Text.get("text.darkmusic.preview_the_previous_page_checked_entries_are_kept_across_pages"));
        next = button(Text.get("text.darkmusic.next_100"), 16 + w, 102, w, () -> page(1), Text.get("text.darkmusic.preview_the_next_page_this_does_not_download_it"));
        selectAll = button(Text.get("text.darkmusic.select_page"), 20 + 2 * w, 102, w, selection::all, Text.get("text.darkmusic.check_every_entry_on_this_preview_page_download_selected_preview_includes_checks_k"));
        deselect = button(Text.get("text.darkmusic.clear_checks"), 24 + 3 * w, 102, w, selection::clear, Text.get("text.darkmusic.clear_all_preview_checks_including_hidden_pages"));
        rows = addRenderableWidget(new RowList(12, 128, width - 24, Math.max(28, height - 196), 32, Text.get("text.darkmusic.preview_or_queue"), (i, activated) -> {
            if (queueView) {
                var items = app.downloads.snapshot().items();
                if (i < items.size()) selectedJob = items.get(i).id();
            }
        }));
        rows.multiple(queueView ? null : selection);
        int third = (width - 32) / 3;
        button(Text.get("text.darkmusic.cancel_selected"), 12, height - 28, third, () -> {
            if (selectedJob >= 0) app.downloads.cancel(selectedJob);
        }, Text.get("text.darkmusic.cancel_the_selected_queued_or_active_item_other_downloads_continue"));
        button(Text.get("text.darkmusic.cancel_all"), 16 + third, height - 28, third, app.downloads::cancelAll, Text.get("text.darkmusic.cancel_url_analysis_active_work_and_queued_entries_completed_songs_are_kept"));
        button(Text.get("text.darkmusic.clear_finished"), 20 + 2 * third, height - 28, third, app.downloads::clearFinished, Text.get("text.darkmusic.remove_saved_skipped_failed_and_cancelled_rows_from_the_queue_saved_audio_is_kept"));
        tick();
    }

    private void page(int delta) {
        var p = app.downloads.snapshot().preview();
        if (p != null) app.downloads.analyze(p.url(), Math.max(0, (p.first() - 1) / PAGE_SIZE + delta));
    }

    private void download() {
        try {
            var p = app.downloads.snapshot().preview();
            if (p == null || !p.url().equals(DownloadModels.url(input)))
                throw new IllegalArgumentException(Text.get("text.darkmusic.analyze_this_url_before_downloading"));
            var destination = app.library.root();
            notice = "";
            switch (mode) {
                case VIDEO -> {
                    if (p.collection() && !p.videoAlso())
                        throw new IllegalArgumentException(Text.get("text.darkmusic.this_is_a_collection_url_choose_selected_entries_first_n_or_a_range"));
                    app.downloads.enqueueVideo(p.url(), destination);
                }
                case SELECTED -> {
                    var checked = selection.selected();
                    var selected = known.entrySet().stream().filter(e -> checked.contains(e.getKey())).map(Map.Entry::getValue).toList();
                    if (selected.isEmpty())
                        throw new IllegalArgumentException(Text.get("text.darkmusic.check_at_least_one_preview_entry"));
                    app.downloads.enqueueSelected(selected, destination);
                }
                case FIRST -> app.downloads.enqueueRange(p, 1, positive(from), destination);
                case RANGE -> app.downloads.enqueueRange(p, positive(from), positive(to), destination);
                case ALL -> {
                    if (p.total() < 1)
                        throw new IllegalArgumentException(Text.get("text.darkmusic.this_collection_mix_has_no_known_end_choose_first_n_or_a_range"));
                    app.downloads.enqueueRange(p, 1, p.total(), destination);
                }
            }
            queueView = true;
            rows.multiple(null);
            refreshRows();
        } catch (IllegalArgumentException e) {
            notice = e.getMessage();
        }
    }

    private int positive(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n > 0) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(Text.get("text.darkmusic.enter_a_positive_entry_number"));
    }

    private void updateButtons() {
        var snapshot = app.downloads.snapshot();
        var p = snapshot.preview();
        analyze.active = !snapshot.analyzing();
        download.active = !snapshot.analyzing() && p != null;
        modeButton.setMessage(Component.literal(Text.get("format.darkmusic.mode", mode.label())));
        first.active = mode == Mode.FIRST || mode == Mode.RANGE;
        last.active = mode == Mode.RANGE;
        previous.active = !queueView && !snapshot.analyzing() && p != null && p.first() > 1;
        next.active = !queueView && !snapshot.analyzing() && p != null && p.hasMore();
        selectAll.active = deselect.active = !queueView && p != null;
        download.setMessage(Component.literal(mode == Mode.SELECTED ? Text.get("format.darkmusic.download_checked", selection.size()) : mode == Mode.VIDEO ? Text.get("text.darkmusic.download_one_video") : mode == Mode.ALL ? Text.get("text.darkmusic.download_all_entries") : mode == Mode.FIRST ? Text.get("text.darkmusic.download_first_n") : Text.get("text.darkmusic.download_range")));
    }

    private void refreshRows() {
        var snapshot = app.downloads.snapshot();
        if (rowsSnapshot == snapshot && rowsQueue == queueView) return;
        rowsSnapshot = snapshot;
        rowsQueue = queueView;
        var p = snapshot.preview();
        if (queueView) {
            rows.rows(snapshot.items().stream().map(i -> new RowList.Row(i.title(), Text.get("state.darkmusic." + i.status().name().toLowerCase(java.util.Locale.ROOT)) + (i.progress() >= 0 ? " " + Math.round(i.progress() * 100) + "%" : "") + " | " + i.message())).toList());
            rows.emptyMessage(Text.get("text.darkmusic.no_downloads_queued"));
        } else {
            var entries = p == null ? List.<Entry>of() : p.entries();
            selection.visible(entries.stream().map(DownloadScreen::key).toList());
            rows.rows(entries.stream().map(e -> new RowList.Row(Math.max(1, e.index()) + ". " + e.title(), e.available() ? p.source() : Text.get("text.darkmusic.unavailable_private_deleted"))).toList());
            rows.emptyMessage(Text.get("text.darkmusic.analyze_a_url_to_preview_entries"));
        }
    }

    @Override
    public void tick() {
        var p = app.downloads.snapshot().preview();
        if (p != displayed) {
            if (displayed != null && p != null && !displayed.url().equals(p.url())) {
                selection.clear();
                known.clear();
            }
            displayed = p;
            if (p != null) p.entries().forEach(e -> known.put(key(e), e));
        }
        refreshRows();
        updateButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, Text.get("text.darkmusic.downloads"), 12, 10, 0xFFB5B0FF);
        var s = app.downloads.snapshot();
        String detail = notice.isBlank() ? s.message() : notice;
        if (queueView) {
            var item = s.items().stream().filter(i -> i.id() == selectedJob).findFirst().orElse(null);
            if (item != null) detail = item.message() + " | " + item.directory();
        }
        g.text(font, font.plainSubstrByWidth(detail, width - 24), 12, height - 62, 0xFFFFCC88);
        long done = s.items().stream().filter(i -> i.status().finished()).count(), failed = s.items().stream().filter(i -> i.status() == Status.FAILED).count();
        String summary = queueView ? Text.get("format.darkmusic.queue_summary", done, s.items().size(), failed) : s.preview() == null ? Text.get("text.darkmusic.only_authorized_public_content_tools_are_installed_separately") : Text.get("format.darkmusic.preview_summary", s.preview().source(), s.preview().first(), s.preview().first() + s.preview().entries().size() - 1, s.preview().total() >= 0 ? Text.get("format.darkmusic.total", s.preview().total()) : Text.get("format.darkmusic.unknown_total"));
        g.text(font, font.plainSubstrByWidth(summary, width - 24), 12, height - 44, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, delta);
    }

    private enum Mode {
        VIDEO("text.darkmusic.this_video_only"), SELECTED("text.darkmusic.selected_preview"), FIRST("text.darkmusic.first_n_entries"), RANGE("text.darkmusic.entry_range"), ALL("text.darkmusic.all_entries");
        final String key;

        String label() {
            return Text.get(key);
        }

        Mode(String label) {
            this.key = label;
        }
    }
}
