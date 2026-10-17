package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.download.AuditionClip;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.darkmusic.client.download.DownloadModels.*;

public final class DownloadScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final SongSelection selection = new SongSelection();
    private final Map<String, Entry> known = new LinkedHashMap<>();
    private EditBox url, first, last, search;
    private RowList rows;
    private Button analyze, download, modeButton, previous, next, selectAll, deselect, listen, stopListening;
    private Preview displayed;
    private Snapshot rowsSnapshot;
    private boolean rowsQueue, queueView;
    private Mode mode = Mode.VIDEO;
    private String input, from = "1", to = "30", notice = "", query = "";
    private long selectedJob = -1;
    private List<Entry> visible = List.of();
    private Entry listeningEntry;
    private AuditionPlayer audition;
    private Track pausedTrack;
    private double pausedPosition;

    public DownloadScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.downloads"), parent);
        this.app = app;
        input = app.downloads.lastAnalyzedUrl();
        app.downloads.clearPreview();
    }

    private static String key(Entry entry) {
        return entry.index() + ":" + entry.key();
    }

    private static boolean single(Preview p) {
        return !p.collection() || p.total() == 1 && p.entries().size() == 1 && !p.hasMore();
    }

    private boolean matches(Preview p) {
        try {
            return p != null && p.url().equals(DownloadModels.url(input));
        } catch (IllegalArgumentException e) {
            return false;
        }
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
            stopAudition();
            notice = "";
            selection.clear();
            known.clear();
            listeningEntry = null;
            queueView = false;
            app.downloads.analyze(input, 0);
        }, Text.get("text.darkmusic.identify_the_source_and_preview_up_to_100_entries_nothing_downloads_automatically"));
        int half = (width - 28) / 2;
        modeButton = button("", 12, 54, half, this::cycleMode, Text.get("download.darkmusic.mode_help"));
        first = addRenderableWidget(Help.tip(new EditBox(font, 16 + half, 54, (half - 4) / 2, 20, Text.component("download.darkmusic.range_start")), Text.get("download.darkmusic.range_help")));
        first.setMaxLength(7);
        first.setValue(from);
        first.setResponder(s -> from = s);
        last = addRenderableWidget(Help.tip(new EditBox(font, 20 + half + (half - 4) / 2, 54, (half - 4) / 2, 20, Text.component("text.darkmusic.range_end")), Text.get("download.darkmusic.range_help")));
        last.setMaxLength(7);
        last.setValue(to);
        last.setResponder(s -> to = s);
        download = button(Text.get("text.darkmusic.download"), 12, 78, half, this::download, Text.get("text.darkmusic.queue_only_the_explicitly_chosen_selection_into_the_current_music_folder_up_to_1_0"));
        button(Text.get("text.darkmusic.show_queue_preview"), 16 + half, 78, half, () -> {
            queueView = !queueView;
            if (queueView) stopAudition();
            rowsSnapshot = null;
            refreshRows();
        }, Text.get("text.darkmusic.switch_between_collection_preview_and_download_queue_closing_this_screen_does_not"));
        int w = (width - 36) / 4;
        previous = button(Text.get("text.darkmusic.prev_100"), 12, 102, w, () -> page(-1), Text.get("text.darkmusic.preview_the_previous_page_checked_entries_are_kept_across_pages"));
        next = button(Text.get("text.darkmusic.next_100"), 16 + w, 102, w, () -> page(1), Text.get("text.darkmusic.preview_the_next_page_this_does_not_download_it"));
        selectAll = button(Text.get("text.darkmusic.select_page"), 20 + 2 * w, 102, w, selection::all, Text.get("download.darkmusic.search_help"));
        deselect = button(Text.get("text.darkmusic.clear_checks"), 24 + 3 * w, 102, w, selection::clear, Text.get("text.darkmusic.clear_all_preview_checks_including_hidden_pages"));
        int actionWidth = Math.min(116, (width - 36) / 3);
        search = addRenderableWidget(Help.tip(new EditBox(font, 12, 128, width - 32 - actionWidth * 2, 20, Text.component("download.darkmusic.search")), Text.get("download.darkmusic.search_help")));
        search.setMaxLength(256);
        search.setValue(query);
        search.setHint(Text.component("download.darkmusic.search"));
        search.setResponder(s -> {
            query = s;
            rowsSnapshot = null;
            refreshRows();
        });
        listen = button(Text.get("download.darkmusic.listen"), width - 16 - actionWidth * 2, 128, actionWidth, this::listen, Text.get("download.darkmusic.listen_help"));
        stopListening = button(Text.get("download.darkmusic.stop_listening"), width - 12 - actionWidth, 128, actionWidth, this::stopAudition, Text.get("download.darkmusic.listen_help"));
        rows = addRenderableWidget(new RowList(12, 182, width - 24, Math.max(28, height - 218), 32, Text.get("text.darkmusic.preview_or_queue"), (i, activated) -> {
            if (queueView) {
                var items = app.downloads.snapshot().items();
                if (i < items.size()) selectedJob = items.get(i).id();
            } else if (i < visible.size()) {
                listeningEntry = visible.get(i);
                if (activated) listen();
            }
        }));
        int third = (width - 32) / 3;
        button(Text.get("text.darkmusic.cancel_selected"), 12, height - 28, third, () -> {
            if (selectedJob >= 0) app.downloads.cancel(selectedJob);
        }, Text.get("text.darkmusic.cancel_the_selected_queued_or_active_item_other_downloads_continue"));
        button(Text.get("text.darkmusic.cancel_all"), 16 + third, height - 28, third, app.downloads::cancelAll, Text.get("text.darkmusic.cancel_url_analysis_active_work_and_queued_entries_completed_songs_are_kept"));
        button(Text.get("text.darkmusic.clear_finished"), 20 + 2 * third, height - 28, third, app.downloads::clearFinished, Text.get("text.darkmusic.remove_saved_skipped_failed_and_cancelled_rows_from_the_queue_saved_audio_is_kept"));
        tick();
    }

    private void cycleMode() {
        var p = app.downloads.snapshot().preview();
        do {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
        }
        while (mode == Mode.VIDEO && p != null && p.collection() && !p.videoAlso() && !single(p));
        selection.clear();
        rowsSnapshot = null;
        refreshRows();
        updateButtons();
    }

    private void page(int delta) {
        var p = app.downloads.snapshot().preview();
        if (p != null) {
            stopAudition();
            app.downloads.analyze(p.url(), Math.max(0, (p.first() - 1) / PAGE_SIZE + delta));
        }
    }

    private void listen() {
        var p = app.downloads.snapshot().preview();
        if (queueView || !matches(p) || listeningEntry == null || !listeningEntry.available()) return;
        if (audition == null) {
            var runner = new ProcessRunner();
            audition = new AuditionPlayer((entry, tools, token) -> AuditionClip.fetch(entry, tools, app.config.directory(), runner, token), () -> new JavaSoundBackend(minecraft::execute), minecraft::execute, this::resumeMusic);
            app.audition = audition;
        }
        var state = app.playback.state();
        if (pausedTrack == null && (state.status() == AudioBackend.Status.PLAYING || state.status() == AudioBackend.Status.LOADING || state.status() == AudioBackend.Status.SEEKING)) {
            pausedTrack = state.track();
            pausedPosition = state.position();
            app.playback.pause();
        }
        audition.play(listeningEntry, new Tools(app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath), app.settings.volume);
    }

    private void resumeMusic() {
        if (audition != null && (audition.snapshot().status() == AuditionPlayer.Status.LOADING || audition.snapshot().status() == AuditionPlayer.Status.PLAYING))
            return;
        Track track = pausedTrack;
        pausedTrack = null;
        var state = app.playback.state();
        if (track != null && track.equals(state.track()) && (state.status() == AudioBackend.Status.PAUSED || state.status() == AudioBackend.Status.PLAYING || state.status() == AudioBackend.Status.LOADING || state.status() == AudioBackend.Status.SEEKING) && Math.abs(state.position() - pausedPosition) < .5)
            app.playback.resume();
    }

    private void stopAudition() {
        if (audition != null) audition.stop();
        resumeMusic();
    }

    private void download() {
        try {
            var p = app.downloads.snapshot().preview();
            if (!matches(p))
                throw new IllegalArgumentException(Text.get("text.darkmusic.analyze_this_url_before_downloading"));
            var destination = app.library.root();
            notice = "";
            switch (mode) {
                case VIDEO -> {
                    if (single(p)) app.downloads.enqueueSelected(List.of(p.entries().getFirst()), destination);
                    else if (p.videoAlso()) app.downloads.enqueueVideo(p.url(), destination);
                    else throw new IllegalArgumentException(Text.get("download.darkmusic.choose_collection_mode"));
                }
                case SELECTED -> {
                    var checked = selection.selected();
                    var selected = known.entrySet().stream().filter(e -> checked.contains(e.getKey())).map(Map.Entry::getValue).toList();
                    if (selected.isEmpty())
                        throw new IllegalArgumentException(Text.get("text.darkmusic.check_at_least_one_preview_entry"));
                    app.downloads.enqueueSelected(selected, destination);
                }
                case RANGE -> app.downloads.enqueueRange(p, positive(from), positive(to), destination);
                case ALL -> {
                    if (p.total() < 1) throw new IllegalArgumentException(Text.get("download.darkmusic.unknown_end"));
                    app.downloads.enqueueRange(p, 1, p.total(), destination);
                }
            }
            stopAudition();
            queueView = true;
            rowsSnapshot = null;
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
        boolean valid = matches(p);
        analyze.active = !snapshot.analyzing();
        download.active = !snapshot.analyzing() && valid;
        modeButton.active = valid && !single(p);
        modeButton.setMessage(Component.literal(Text.get("format.darkmusic.mode", mode.label())));
        first.visible = last.visible = mode == Mode.RANGE;
        first.active = last.active = valid && mode == Mode.RANGE && !snapshot.analyzing();
        previous.active = !queueView && !snapshot.analyzing() && valid && p.first() > 1;
        next.active = !queueView && !snapshot.analyzing() && valid && p.hasMore();
        selectAll.active = deselect.active = !queueView && valid && mode == Mode.SELECTED;
        search.visible = listen.visible = stopListening.visible = !queueView;
        search.active = valid;
        listen.active = valid && listeningEntry != null && listeningEntry.available() && !snapshot.analyzing();
        stopListening.active = audition != null && (audition.snapshot().status() == AuditionPlayer.Status.LOADING || audition.snapshot().status() == AuditionPlayer.Status.PLAYING);
        download.setMessage(Component.literal(mode == Mode.SELECTED ? Text.get("format.darkmusic.download_checked", selection.size()) : mode == Mode.VIDEO ? Text.get("text.darkmusic.download_one_video") : mode == Mode.ALL ? Text.get("text.darkmusic.download_all_entries") : Text.get("text.darkmusic.download_range")));
    }

    private void refreshRows() {
        if (rows == null) return;
        var snapshot = app.downloads.snapshot();
        if (rowsSnapshot == snapshot && rowsQueue == queueView) return;
        rowsSnapshot = snapshot;
        rowsQueue = queueView;
        var p = snapshot.preview();
        rows.multiple(!queueView && mode == Mode.SELECTED ? selection : null);
        if (queueView) {
            rows.rows(snapshot.items().stream().map(i -> new RowList.Row(i.title(), Text.get("state.darkmusic." + i.status().name().toLowerCase(Locale.ROOT)) + (i.progress() >= 0 ? " " + Math.round(i.progress() * 100) + "%" : "") + " | " + i.message())).toList());
            rows.emptyMessage(Text.get("text.darkmusic.no_downloads_queued"));
        } else {
            String filter = query.strip().toLowerCase(Locale.ROOT);
            visible = p == null ? List.of() : p.entries().stream().filter(e -> filter.isEmpty() || (e.title() + " " + Math.max(1, e.index())).toLowerCase(Locale.ROOT).contains(filter)).toList();
            if (listeningEntry != null && !visible.contains(listeningEntry)) listeningEntry = null;
            selection.visible(visible.stream().map(DownloadScreen::key).toList());
            rows.rows(visible.stream().map(e -> new RowList.Row(Math.max(1, e.index()) + ". " + e.title(), e.available() ? p.source() : Text.get("text.darkmusic.unavailable_private_deleted"))).toList());
            rows.emptyMessage(p == null ? Text.get("text.darkmusic.analyze_a_url_to_preview_entries") : Text.get("download.darkmusic.no_matches"));
        }
    }

    @Override
    public void tick() {
        var p = app.downloads.snapshot().preview();
        if (p != displayed) {
            if (p != null) {
                if (displayed == null && known.isEmpty() || displayed != null && !displayed.url().equals(p.url())) {
                    selection.clear();
                    known.clear();
                    mode = single(p) || p.videoAlso() ? Mode.VIDEO : Mode.SELECTED;
                }
                p.entries().forEach(e -> known.put(key(e), e));
            }
            displayed = p;
        }
        refreshRows();
        updateButtons();
    }

    @Override
    public void removed() {
        stopAudition();
        if (audition != null) {
            audition.close();
            if (app.audition == audition) app.audition = null;
            audition = null;
        }
        app.downloads.clearPreview();
        displayed = null;
        known.clear();
        selection.clear();
        listeningEntry = null;
        query = "";
        rowsSnapshot = null;
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 10, 0xFFB5B0FF);
        var s = app.downloads.snapshot();
        String detail = notice.isBlank() ? s.message() : notice;
        if (audition != null && !queueView) {
            var a = audition.snapshot();
            if (a.status() == AuditionPlayer.Status.ERROR) detail = a.error();
            else if (a.status() != AuditionPlayer.Status.STOPPED)
                detail = Text.get(a.status() == AuditionPlayer.Status.LOADING ? "download.darkmusic.loading_audio" : "download.darkmusic.listening", a.title());
        }
        longText.draw(g, font, "detail", detail, 12, 154, width - 24, 0xFFFFCC88);
        long done = s.items().stream().filter(i -> i.status().finished()).count(), failed = s.items().stream().filter(i -> i.status() == Status.FAILED).count();
        String summary = queueView ? Text.get("format.darkmusic.queue_summary", done, s.items().size(), failed) : s.preview() == null ? "" : Text.get("format.darkmusic.preview_summary", s.preview().source(), s.preview().first(), s.preview().first() + s.preview().entries().size() - 1, s.preview().total() >= 0 ? Text.get("format.darkmusic.total", s.preview().total()) : Text.get("format.darkmusic.unknown_total"));
        longText.draw(g, font, "summary", summary, 12, 168, width - 24, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, delta);
    }

    private enum Mode {
        VIDEO("text.darkmusic.this_video_only"), SELECTED("text.darkmusic.selected_preview"), RANGE("text.darkmusic.entry_range"), ALL("text.darkmusic.all_entries");
        final String key;

        Mode(String key) {
            this.key = key;
        }

        String label() {
            return Text.get(key);
        }
    }
}
