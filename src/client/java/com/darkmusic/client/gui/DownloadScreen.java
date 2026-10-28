package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.config.DownloadFormat;
import com.darkmusic.client.download.AuditionClip;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.darkmusic.client.download.DownloadModels.*;

public final class DownloadScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final SongSelection selection = new SongSelection();
    private final Map<String, Entry> known = new LinkedHashMap<>();
    private EditBox url, first, last, search, pageNumber;
    private RowList rows;
    private Button format;
    private Button queueToggle, analyze, download, modeButton, previous, next, goToPage, selectAll, deselect, listen, stopListening, retrySelected, retryFailed, clearFailed, cancelSelected, cancelAll, clearFinished;
    private Button errorDetails;
    private Button existingDetails;
    private long sourceRevision = -1, shownChoice = -1;
    private Preview displayed;
    private int furthestPage = 1;
    private Snapshot rowsSnapshot;
    private boolean rowsQueue, queueView;
    private Mode mode = Mode.VIDEO;
    private String input, from = "1", to = "30", notice = "", query = "";
    private long selectedJob = -1;
    private List<Entry> visible = List.of();
    private List<Item> visibleJobs = List.of();
    private String entryQuery = "", queueQuery = "";
    private Entry listeningEntry;
    private Entry auditionEntry;
    private AuditionPlayer audition;
    private Track pausedTrack;
    private double pausedPosition;

    public DownloadScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.downloads"), parent);
        this.app = app;
        input = app.downloads.inputUrl();
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
        return addRenderableWidget(Help.tip(UiButton.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build(), help));
    }

    private boolean sourceExpanded = true;
    private int sourceY, tabsY, modeY, searchY, pageY, resultTop;
    private Button sourceToggle, entriesTab;

    private void queue(boolean next) {
        if (queueView == next) return;
        if (queueView) queueQuery = query;
        else entryQuery = query;
        queueView = next;
        query = queueView ? queueQuery : entryQuery;
        search.setValue(query);
        if (queueView) stopAudition();
        rowsSnapshot = null;
        refreshRows();
        updateButtons();
    }

    private Button action(String key, Runnable callback, String help) {
        return button(Text.get(key), 0, 0, 1, callback, Text.get(help));
    }

    private UiInput input(String key, int max, String value, Consumer<String> change) {
        var field = addRenderableWidget(new UiInput(font, 0, 0, 1, 20, Text.component(key)));
        field.setMaxLength(max);
        field.setValue(value);
        field.setResponder(change);
        return field;
    }

    @Override
    protected void init() {
        app.sourceIndex();
        backButton();
        rowsSnapshot = null;
        UiButton.icon(button(Text.get("text.darkmusic.tools"), width - 172, 4, 88,
            () -> show(new DownloadToolsScreen(app, this)), Text.get("text.darkmusic.set_executable_paths_and_check_yt_dlp_ffmpeg_ffprobe_and_optional_javascript_runti")), UiIcon.TOOLS, false);
        sourceToggle = UiButton.icon(button(Text.get("phase2.source"), width - 264, 4, 88, () -> {
            sourceExpanded = !sourceExpanded;
            updateButtons();
        }, Text.get("download.darkmusic.source_panel_help")), UiIcon.SEARCH, false);
        url = input("text.darkmusic.video_or_collection_url", 4096, input, s -> {
            input = s;
            app.downloads.rememberInput(s);
            notice = "";
        });
        url.setHint(Text.component("text.darkmusic.paste_a_video_or_collection_url"));
        analyze = action("text.darkmusic.analyze", () -> {
            stopAudition();
            notice = "";
            selection.clear();
            known.clear();
            listeningEntry = null;
            if (queueView) {
                queueQuery = query;
                query = entryQuery;
                search.setValue(query);
            }
            queueView = false;
            app.downloads.analyze(input, 0);
        }, "text.darkmusic.identify_the_source_and_preview_up_to_100_entries_nothing_downloads_automatically");
        modeButton = action("phase2.source", this::cycleMode, "download.darkmusic.mode_help");
        first = input("download.darkmusic.range_start", 7, from, s -> from = s);
        last = input("text.darkmusic.range_end", 7, to, s -> to = s);
        Help.tip(first, Text.get("download.darkmusic.range_help"));
        Help.tip(last, Text.get("download.darkmusic.range_help"));
        format = action("audio.darkmusic.download", () -> {
            var formats = DownloadFormat.values();
            app.settings.downloadFormat = formats[(app.settings.downloadFormat.ordinal() + 1) % formats.length];
            app.save();
            updateButtons();
        }, "audio.darkmusic.download_help");
        download = action("text.darkmusic.download", this::download, "text.darkmusic.queue_only_the_explicitly_chosen_selection_into_the_current_music_folder_up_to_1_0");
        entriesTab = action("phase2.entries", () -> queue(false), "text.darkmusic.switch_between_collection_preview_and_download_queue_closing_this_screen_does_not");
        queueToggle = action("ui.darkmusic.queue", () -> queue(true), "text.darkmusic.switch_between_collection_preview_and_download_queue_closing_this_screen_does_not");
        previous = action("text.darkmusic.prev_100", () -> page(-1), "text.darkmusic.preview_the_previous_page_checked_entries_are_kept_across_pages");
        next = action("text.darkmusic.next_100", () -> page(1), "text.darkmusic.preview_the_next_page_this_does_not_download_it");
        UiButton.icon(previous, UiIcon.PREVIOUS, true);
        UiButton.icon(next, UiIcon.NEXT, true);
        pageNumber = input("download.darkmusic.page", 7, pageNumber == null ? "1" : pageNumber.getValue(), value -> {
        });
        goToPage = action("download.darkmusic.go_to_page", this::jumpPage, "download.darkmusic.page_help");
        selectAll = action("text.darkmusic.select_page", selection::all, "download.darkmusic.select_page_help");
        deselect = action("text.darkmusic.clear_checks", selection::clear, "text.darkmusic.clear_all_preview_checks_including_hidden_pages");
        search = input("download.darkmusic.search", 256, query, s -> {
            query = s;
            rowsSnapshot = null;
            refreshRows();
        });
        Help.tip(search, Text.get("download.darkmusic.search_help"));
        listen = action("download.darkmusic.listen", this::listen, "download.darkmusic.listen_help");
        stopListening = action("download.darkmusic.stop_listening", this::stopAudition, "download.darkmusic.listen_help");
        UiButton.icon(listen, UiIcon.PLAY, true);
        UiButton.icon(stopListening, UiIcon.STOP, true);
        rows = addRenderableWidget(new RowList(12, 150, width - 24, 40, 36, Text.get("text.darkmusic.preview_or_queue"), (i, activated) -> {
            if (queueView) {
                if (i < visibleJobs.size()) selectedJob = visibleJobs.get(i).id();
            } else if (i < visible.size()) {
                listeningEntry = visible.get(i);
                if (activated) listen();
            }
        }));
        rows.rowTooltips(false);
        retrySelected = action("download.darkmusic.retry_selected", () -> {
            if (queueView && selectedJob >= 0) app.downloads.retry(selectedJob);

        }, "download.darkmusic.retry_help");
        retryFailed = action("download.darkmusic.retry_failed", app.downloads::retryFailed, "download.darkmusic.retry_help");
        clearFailed = action("download.darkmusic.clear_failed", app.downloads::clearFailed, "download.darkmusic.clear_failed_help");
        cancelSelected = action("text.darkmusic.cancel_selected", () -> {
            if (selectedJob >= 0) app.downloads.cancel(selectedJob);
        }, "text.darkmusic.cancel_the_selected_queued_or_active_item_other_downloads_continue");
        cancelAll = action("text.darkmusic.cancel_all", app.downloads::cancelAll, "text.darkmusic.cancel_url_analysis_active_work_and_queued_entries_completed_songs_are_kept");
        clearFinished = action("text.darkmusic.clear_finished", app.downloads::clearFinished, "download.darkmusic.clear_finished_help");
        errorDetails = action("download.diag.title", () -> {
            var diagnostic = queueView ? app.downloads.diagnostic(selectedJob) : app.downloads.analysisDiagnostic();
            if (diagnostic != null) show(new DownloadDiagnosticScreen(app, this, diagnostic, queueView
                ? () -> app.downloads.retry(selectedJob) : () -> app.downloads.analyze(app.downloads.inputUrl(), 0)));
        }, "download.diag.help");
        UiButton.icon(errorDetails, UiIcon.WARNING, true);
        existingDetails = action("download.library.existing", () -> {
            var choice = app.downloads.duplicateChoice();
            if (choice != null) show(new DownloadDuplicateScreen(app, this, choice));
            else if (hasExistingMatches(listeningEntry)) show(new ExistingDownloadsScreen(app, this, listeningEntry));
        }, "download.library.inspect_help");
        UiButton.icon(existingDetails, UiIcon.FOLDER, true);
        UiButton.style(analyze, UiButton.Style.PRIMARY);
        UiButton.style(download, UiButton.Style.PRIMARY);
        UiButton.selected(entriesTab, () -> !queueView);
        UiButton.selected(queueToggle, () -> queueView);
        UiButton.style(cancelSelected, UiButton.Style.DESTRUCTIVE);
        UiButton.style(cancelAll, UiButton.Style.DESTRUCTIVE);
        UiButton.style(clearFailed, UiButton.Style.DESTRUCTIVE);
        UiButton.style(clearFinished, UiButton.Style.SUBTLE);
        rows.tint(i -> queueView && i < visibleJobs.size() ? switch (visibleJobs.get(i).status()) {
            case FAILED -> UiTheme.ERROR;
            case SAVED, SKIPPED -> UiTheme.SUCCESS;
            case CANCELLED -> UiTheme.MUTED;
            default -> UiTheme.SECONDARY;
        } : UiTheme.SECONDARY);
        rows.icons(i -> queueView && i < visibleJobs.size() ? switch (visibleJobs.get(i).status()) {
            case FAILED -> UiIcon.WARNING;
            case SAVED, SKIPPED -> UiIcon.CHECK;
            case CANCELLED -> UiIcon.STOP;
            case CONVERTING -> UiIcon.TOOLS;
            default -> UiIcon.DOWNLOAD;
        } : UiIcon.MUSIC);
        rows.progress(i -> queueView && i < visibleJobs.size() ? visibleJobs.get(i).progress() : -1);
        tick();
    }

    private void place(AbstractWidget widget, int x, int y, int w, boolean visible) {
        int previousWidth = widget.getWidth();
        widget.setX(x);
        widget.setY(y);
        widget.setWidth(w);
        if (previousWidth != w && widget instanceof EditBox editor)
            editor.setCursorPosition(editor.getCursorPosition());
        widget.visible = visible;
        if (!visible && getFocused() == widget) setFocused(null);
    }

    private int pageCountX;
    private boolean labelledListen;
    // Left of Listen in each layout; the diagnostic and existing-file icons fill leftward from here.
    private int iconX;

    private int pageCountWidth() {
        return font.width(Text.get("download.darkmusic.page_of", "999+"));
    }

    private String pageCount(Preview preview) {
        int pages = availablePages(preview);
        return Text.get("download.darkmusic.page_of", preview.total() > 0 || !preview.hasMore() ? Integer.toString(pages) : pages + "+");
    }

    private void layout() {
        boolean wide = width >= 640 && height >= 360;
        labelledListen = wide;
        if (wide) {
            layoutWide();
            return;
        }
        boolean source = sourceExpanded;
        sourceY = 38;
        tabsY = source ? 68 : 38;
        modeY = tabsY + 28;
        boolean modes = !queueView && source;
        searchY = modes ? modeY + 26 : tabsY + 28;
        pageY = searchY + 26;
        resultTop = queueView ? searchY + 42 : pageY + 42;
        sourceToggle.visible = true;
        place(url, 12, sourceY, width - 116, source);
        place(analyze, width - 100, sourceY, 88, source);
        place(entriesTab, 12, tabsY, 84, true);
        place(queueToggle, 100, tabsY, 84, true);
        int actions = (width - 208) / 2;
        place(download, 192, tabsY, actions, !queueView);
        place(format, 196 + actions, tabsY, width - 208 - actions, !queueView);
        int half = (width - 28) / 2;
        place(modeButton, 12, modeY, half, modes);
        place(first, 16 + half, modeY, (half - 4) / 2, modes && mode == Mode.RANGE);
        place(last, 20 + half + (half - 4) / 2, modeY, (half - 4) / 2, modes && mode == Mode.RANGE);
        place(search, 12, searchY, width - (queueView ? 24 : 80), true);
        place(listen, width - 64, searchY, 24, !queueView);
        place(stopListening, width - 36, searchY, 24, !queueView);
        iconX = width - 92;
        place(previous, 12, pageY, 24, !queueView);
        place(next, 40, pageY, 24, !queueView);
        place(pageNumber, 68, pageY, 40, !queueView);
        place(goToPage, 112, pageY, 28, !queueView);
        pageCountX = 146;
        int choiceX = pageCountX + pageCountWidth() + 6;
        int choice = (width - 12 - choiceX - 4) / 2;
        place(selectAll, choiceX, pageY, choice, !queueView && mode == Mode.SELECTED);
        place(deselect, choiceX + choice + 4, pageY, choice, !queueView && mode == Mode.SELECTED);
        int third = (width - 32) / 3;
        UiButton.icon(retrySelected, UiIcon.REFRESH, false);
        place(retrySelected, 12, height - 56, third, queueView);
        place(retryFailed, 16 + third, height - 56, third, queueView);
        place(clearFailed, 20 + third * 2, height - 56, third, queueView);
        place(cancelSelected, 12, height - 30, third, queueView);
        place(cancelAll, 16 + third, height - 30, third, queueView);
        place(clearFinished, 20 + third * 2, height - 30, third, queueView);
        rows.setY(resultTop);
        rows.setX(12);
        rows.setWidth(width - 24);
        rows.setHeight(height - (queueView ? 66 : 12) - resultTop);
        layoutDiagnostic();
    }

    private void layoutWide() {
        sourceY = 42;
        tabsY = 80;
        modeY = 140;
        searchY = 80;
        pageY = 108;
        resultTop = queueView ? 122 : 150;
        sourceToggle.visible = false;
        int side = 156, rightX = 184, rightWidth = width - rightX - 12;
        place(url, 16, sourceY, width - 124, true);
        place(analyze, width - 104, sourceY, 88, true);
        place(entriesTab, 16, tabsY, side, true);
        place(queueToggle, 16, tabsY + 26, side, true);
        place(modeButton, 16, modeY, side, !queueView);
        place(first, 16, modeY + 26, 76, !queueView && mode == Mode.RANGE);
        place(last, 96, modeY + 26, 76, !queueView && mode == Mode.RANGE);
        place(download, 16, modeY + (mode == Mode.RANGE ? 56 : 30), side, !queueView);
        place(format, 16, modeY + (mode == Mode.RANGE ? 82 : 56), side, !queueView);
        // Wide enough for its longest state, so the label never needs shortening.
        int listenWidth = Math.clamp(Stream.of("download.darkmusic.listen", "text.darkmusic.pause", "text.darkmusic.resume")
            .mapToInt(key -> font.width(Text.get(key))).max().orElse(0) + 33, 72, 160);
        place(search, rightX, searchY, rightWidth - (queueView ? 0 : listenWidth + 40), true);
        place(listen, width - 40 - listenWidth, searchY, listenWidth, !queueView);
        place(stopListening, width - 36, searchY, 24, !queueView);
        iconX = width - 68 - listenWidth;
        place(previous, rightX, pageY, 24, !queueView);
        place(next, rightX + 28, pageY, 24, !queueView);
        place(pageNumber, rightX + 56, pageY, 40, !queueView);
        place(goToPage, rightX + 100, pageY, 28, !queueView);
        pageCountX = rightX + 134;
        int choiceX = pageCountX + pageCountWidth() + 6;
        int choice = (rightX + rightWidth - choiceX - 4) / 2;
        place(selectAll, choiceX, pageY, choice, !queueView && mode == Mode.SELECTED);
        place(deselect, choiceX + choice + 4, pageY, choice, !queueView && mode == Mode.SELECTED);
        int third = (rightWidth - 8) / 3;
        place(retrySelected, rightX, height - 56, third, queueView);
        place(retryFailed, rightX + third + 4, height - 56, third, queueView);
        place(clearFailed, rightX + 2 * (third + 4), height - 56, third, queueView);
        place(cancelSelected, rightX, height - 30, third, queueView);
        place(cancelAll, rightX + third + 4, height - 30, third, queueView);
        place(clearFinished, rightX + 2 * (third + 4), height - 30, third, queueView);
        rows.setX(rightX);
        rows.setWidth(rightWidth);
        rows.setY(resultTop);
        rows.setHeight(height - (queueView ? 66 : 12) - resultTop);
        layoutDiagnostic();
    }

    private void layoutDiagnostic() {
        boolean available = (queueView ? app.downloads.diagnostic(selectedJob) : app.downloads.analysisDiagnostic()) != null;
        int slot = queueView ? width - 36 : iconX;
        place(errorDetails, slot, searchY, 24, available);
        errorDetails.active = available;
        if (available) search.setWidth(Math.max(24, search.getWidth() - 28));
        boolean inspect = app.downloads.duplicateChoice() != null || !queueView && listeningEntry != null;
        place(existingDetails, slot - (available ? 28 : 0), searchY, 24, inspect);
        existingDetails.active = app.downloads.duplicateChoice() != null || !queueView && hasExistingMatches(listeningEntry);
        if (inspect) search.setWidth(Math.max(24, search.getWidth() - 28));
    }

    private boolean hasExistingMatches(Entry entry) {
        if (entry == null) return false;
        var lookup = app.sourceIndex().lookup(entry);
        return !lookup.confirmed().isEmpty() || !lookup.possible().isEmpty();
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

    private int availablePages(Preview preview) {
        return preview.total() > 0 ? (int) ((preview.total() + (long) PAGE_SIZE - 1) / PAGE_SIZE) : furthestPage;
    }

    private void jumpPage() {
        var snapshot = app.downloads.snapshot();
        var preview = snapshot.preview();
        if (queueView || snapshot.analyzing() || !matches(preview)) return;
        try {
            int page = positive(pageNumber.getValue());
            if (page > availablePages(preview)) throw new IllegalArgumentException();
            notice = "";
            if (page != (preview.first() - 1) / PAGE_SIZE + 1) {
                stopAudition();
                app.downloads.analyze(preview.url(), page - 1);
            }
        } catch (IllegalArgumentException e) {
            notice = Text.get("download.darkmusic.page_invalid", availablePages(preview));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (pageNumber != null && pageNumber.visible && pageNumber.isFocused() && event.isConfirmation()) {
            jumpPage();
            return true;
        }
        return super.keyPressed(event);
    }

    private void listen() {
        var p = app.downloads.snapshot().preview();
        if (queueView || !matches(p) || listeningEntry == null || !listeningEntry.available()) return;
        if (audition != null && auditionEntry != null && auditionEntry.key().equals(listeningEntry.key())) {
            if (audition.snapshot().status() == AuditionPlayer.Status.PLAYING) {
                audition.pause();
                return;
            }
            if (audition.snapshot().status() == AuditionPlayer.Status.PAUSED) {
                audition.resume();
                return;
            }
            if (audition.snapshot().status() == AuditionPlayer.Status.LOADING) return;
        }
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
        auditionEntry = listeningEntry;
        audition.play(listeningEntry, new ToolPaths(app.settings.ytDlpPath, app.settings.ffmpegPath, app.settings.ffprobePath, app.settings.javascriptPath), app.settings.volume);
    }

    private void resumeMusic() {
        if (audition != null && (audition.snapshot().status() == AuditionPlayer.Status.LOADING || audition.snapshot().status() == AuditionPlayer.Status.PLAYING || audition.snapshot().status() == AuditionPlayer.Status.PAUSED))
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
        if (queueView) return;
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
                case RANGE -> {
                    int start = positive(from), end = positive(to);
                    // Validate here so a rejected range keeps the user on the form beside its fields.
                    DownloadModels.range(start, end);
                    app.downloads.enqueueRange(p, start, end, destination);
                }
                case ALL -> {
                    if (p.total() < 1) throw new IllegalArgumentException(Text.get("download.darkmusic.unknown_end"));
                    DownloadModels.range(1, p.total());
                    app.downloads.enqueueRange(p, 1, p.total(), destination);
                }
            }
            stopAudition();
            entryQuery = query;
            queueView = true;
            query = queueQuery;
            search.setValue(query);
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
        download.active = !queueView && !snapshot.analyzing() && valid;
        modeButton.active = !queueView && valid && !single(p);
        modeButton.setMessage(Component.literal(Text.get("format.darkmusic.mode", mode.label())));
        format.setMessage(Text.component("download.darkmusic.format", Text.get("download.darkmusic.format." + app.settings.downloadFormat.name().toLowerCase(Locale.ROOT))));
        format.active = !queueView && !snapshot.analyzing();
        first.visible = last.visible = !queueView && mode == Mode.RANGE;
        first.active = last.active = !queueView && valid && mode == Mode.RANGE && !snapshot.analyzing();
        previous.active = !queueView && !snapshot.analyzing() && valid && p.first() > 1;
        next.active = !queueView && !snapshot.analyzing() && valid && p.hasMore();
        pageNumber.active = goToPage.active = !queueView && !snapshot.analyzing() && valid && availablePages(p) > 1;
        selectAll.active = deselect.active = !queueView && valid && mode == Mode.SELECTED;
        search.visible = true;
        listen.visible = stopListening.visible = !queueView;
        search.active = queueView || valid;
        search.setHint(Text.component(queueView ? "download.darkmusic.search_queue" : "download.darkmusic.search"));
        listen.active = valid && listeningEntry != null && listeningEntry.available() && !snapshot.analyzing();
        boolean currentAudition = audition != null && auditionEntry != null && listeningEntry != null && auditionEntry.key().equals(listeningEntry.key());
        var auditionStatus = audition == null ? AuditionPlayer.Status.STOPPED : audition.snapshot().status();
        boolean playing = currentAudition && auditionStatus == AuditionPlayer.Status.PLAYING;
        listen.setMessage(Text.component(playing ? "text.darkmusic.pause" : currentAudition && auditionStatus == AuditionPlayer.Status.PAUSED ? "text.darkmusic.resume" : "download.darkmusic.listen"));
        UiButton.icon(listen, playing ? UiIcon.PAUSE : UiIcon.PLAY, !labelledListen);
        listen.active &= !(currentAudition && auditionStatus == AuditionPlayer.Status.LOADING);
        stopListening.active = auditionStatus == AuditionPlayer.Status.LOADING || auditionStatus == AuditionPlayer.Status.PLAYING || auditionStatus == AuditionPlayer.Status.PAUSED;
        var selectedItem = visibleJobs.stream().filter(i -> i.id() == selectedJob).findFirst();
        var failedJob = selectedItem.filter(i -> i.status() == Status.FAILED);
        retrySelected.active = queueView && failedJob.isPresent();
        retryFailed.active = clearFailed.active = queueView && snapshot.items().stream().anyMatch(i -> i.status() == Status.FAILED);
        cancelSelected.active = queueView && selectedItem.filter(i -> !i.status().finished()).isPresent();
        cancelAll.active = queueView && (snapshot.analyzing() || snapshot.items().stream().anyMatch(i -> !i.status().finished()));
        clearFinished.active = queueView && snapshot.items().stream().anyMatch(i -> i.status().finished() && i.status() != Status.FAILED);
        download.setMessage(Component.literal(mode == Mode.SELECTED ? Text.get("format.darkmusic.download_checked", selection.size()) : mode == Mode.VIDEO ? Text.get("text.darkmusic.download_one_video") : mode == Mode.ALL ? Text.get("text.darkmusic.download_all_entries") : Text.get("text.darkmusic.download_range")));
        layout();
    }

    private void refreshRows() {
        if (rows == null) return;
        var snapshot = app.downloads.snapshot();
        long source = app.sourceIndex().revision();
        if (rowsSnapshot == snapshot && rowsQueue == queueView && sourceRevision == source) return;
        sourceRevision = source;
        rowsSnapshot = snapshot;
        rowsQueue = queueView;
        var p = snapshot.preview();
        rows.multiple(!queueView && mode == Mode.SELECTED ? selection : null);
        if (queueView) {
            String filter = query.strip().toLowerCase(Locale.ROOT);
            visibleJobs = snapshot.items().stream().filter(i -> filter.isEmpty() || (i.title() + " " + Text.get("state.darkmusic." + i.status().name().toLowerCase(Locale.ROOT)) + " " + i.message() + " " + i.directory()).toLowerCase(Locale.ROOT).contains(filter)).toList();
            rows.rows(visibleJobs.stream().map(i -> jobRow(i)).toList());
            rows.select(IntStream.range(0, visibleJobs.size()).filter(i -> visibleJobs.get(i).id() == selectedJob).findFirst().orElse(-1));
            rows.emptyMessage(Text.get(snapshot.items().isEmpty() ? "text.darkmusic.no_downloads_queued" : "download.darkmusic.no_queue_matches"));
        } else {
            String filter = query.strip().toLowerCase(Locale.ROOT);
            visible = p == null ? List.of() : p.entries().stream().filter(e -> filter.isEmpty() || (e.title() + " " + Math.max(1, e.index())).toLowerCase(Locale.ROOT).contains(filter)).toList();
            if (listeningEntry != null && !visible.contains(listeningEntry)) listeningEntry = null;
            selection.visible(visible.stream().map(DownloadScreen::key).toList());
            rows.rows(visible.stream().map(e -> entryRow(e)).toList());
            rows.emptyMessage(p == null ? Text.get("text.darkmusic.analyze_a_url_to_preview_entries") : Text.get("download.darkmusic.no_matches"));
        }
    }

    @Override
    public void tick() {
        var choice = app.downloads.duplicateChoice();
        if (choice != null && choice.id() != shownChoice && !app.downloads.snapshot().analyzing()) {
            shownChoice = choice.id();
            show(new DownloadDuplicateScreen(app, this, choice));
            return;
        }
        var p = app.downloads.snapshot().preview();
        if (p != displayed) {
            if (p != null && displayed == null && (width < 640 || height < 360)) sourceExpanded = false;
            if (p != null) {
                if (displayed == null && known.isEmpty() || displayed != null && !displayed.url().equals(p.url())) {
                    selection.clear();
                    known.clear();
                    furthestPage = 1;
                    mode = single(p) || p.videoAlso() ? Mode.VIDEO : Mode.SELECTED;
                }
                int currentPage = (p.first() - 1) / PAGE_SIZE + 1;
                furthestPage = Math.max(furthestPage, currentPage);
                pageNumber.setValue(Integer.toString(currentPage));
                Help.tip(pageNumber, Text.get("download.darkmusic.page_range", availablePages(p)));
                p.entries().forEach(e -> known.put(key(e), e));
            }
            displayed = p;
        }
        refreshRows();
        updateButtons();
    }

    private RowList.Row entryRow(Entry entry) {
        var row = new RowList.Row(Math.max(1, entry.index()) + ". " + entry.title(), "");
        if (!entry.available())
            return row.status(Text.get("text.darkmusic.unavailable_private_deleted"), UiStatus.Tone.WARNING);
        var lookup = app.sourceIndex().lookup(entry);
        if (!lookup.confirmed().isEmpty()) {
            var f = lookup.confirmed().getFirst();
            return new RowList.Row(row.title(), f.track().title() + " | " + f.format() + " | " + f.track().file())
                .status(Text.get("download.library.already"), UiStatus.Tone.SUCCESS);
        }
        if (!lookup.possible().isEmpty()) return new RowList.Row(row.title(), lookup.possible().getFirst().title())
            .status(Text.get("download.library.possible"), UiStatus.Tone.WARNING);
        if (lookup.complete()) return row;
        return row.status(Text.get("download.library.incomplete"), UiStatus.Tone.WARNING);
    }

    static RowList.Row jobRow(Item item) {
        String message = item.message();
        String metadata = item.directory().toString();
        if (item.status() == Status.SAVED) {
            int warning = message.indexOf(" \u2014 ");
            String filename = warning < 0 ? message : message.substring(0, warning);
            if (!filename.isBlank()) metadata = filename + " | " + metadata;
            message = warning < 0 ? "" : message.substring(warning + 3);
        }
        String status = Text.get("state.darkmusic." + item.status().name().toLowerCase(Locale.ROOT))
            + (item.progress() >= 0 ? " " + Math.round(item.progress() * 100) + "%" : "")
            + (message.isBlank() ? "" : " | " + message);
        return new RowList.Row(item.title(), metadata, item.message())
            .status(status, item.status() == Status.SAVED && !message.isBlank() ? UiStatus.Tone.WARNING : UiStatus.download(item.status()));
    }

    @Override
    public void removed() {
        stopAudition();
        if (audition != null) {
            audition.close();
            if (app.audition == audition) app.audition = null;
            audition = null;
        }
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.DOWNLOAD);
        boolean wide = width >= 640 && height >= 360;
        if (url.visible) UiTheme.panel(g, 10, sourceY - 4, width - 20, 28);
        if (wide) {
            UiTheme.panel(g, 10, tabsY - 6, 168, height - tabsY - 4);
            UiTheme.panel(g, 180, searchY - 6, width - 190, height - searchY - 4);
        }
        if (queueView) UiTheme.panel(g, wide ? 180 : 10, height - 60, width - (wide ? 190 : 20), 54);
        var s = app.downloads.snapshot();
        String detail = notice.isBlank() ? s.message() : notice;
        if (audition != null && !queueView) {
            var a = audition.snapshot();
            if (a.status() == AuditionPlayer.Status.ERROR) detail = a.error();
            else if (a.status() != AuditionPlayer.Status.STOPPED)
                detail = Text.get(a.status() == AuditionPlayer.Status.LOADING ? "download.darkmusic.loading_audio" : a.status() == AuditionPlayer.Status.PAUSED ? "download.darkmusic.preview_paused" : "download.darkmusic.listening", a.title());
        }

        long done = s.items().stream().filter(i -> i.status().finished()).count(), failed = s.items().stream().filter(i -> i.status() == Status.FAILED).count();
        String summary = queueView ? Text.get("format.darkmusic.queue_summary", done, s.items().size(), failed) : s.preview() == null ? "" : Text.get("format.darkmusic.preview_summary", s.preview().source(), s.preview().first(), s.preview().first() + s.preview().entries().size() - 1, s.preview().total() >= 0 ? Text.get("format.darkmusic.total", s.preview().total()) : Text.get("format.darkmusic.unknown_total"));
        summary = Text.get(queueView ? "ui.darkmusic.queue" : "ui.darkmusic.preview_entries") + (summary.isBlank() ? "" : ": " + summary);
        if (!queueView) summary = Text.get("folders.destination", app.library.root()) + " | " + summary;
        var tone = !notice.isBlank() || app.downloads.analysisDiagnostic() != null ? UiStatus.Tone.ERROR : UiStatus.Tone.INFO;
        if (audition != null && audition.snapshot().status() == AuditionPlayer.Status.ERROR) tone = UiStatus.Tone.ERROR;
        longText.drawStatus(g, font, "detail", detail, summary, rows.getX(), resultTop - 13, rows.getWidth(), UiStatus.color(tone), UiTheme.SECONDARY);
        if (pageNumber.visible && matches(s.preview()))
            g.text(font, pageCount(s.preview()), pageCountX, pageNumber.getY() + 6, pageNumber.active ? UiTheme.SECONDARY : UiTheme.MUTED);
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
