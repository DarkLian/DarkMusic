package com.darkmusic.client.gui;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.presence.DiscordPresence;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.systemmedia.MediaControls;
import com.darkmusic.client.systemmedia.MediaControlsPackage;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * One scrollable settings form with a fixed, independently contained live preview.
 */
public final class SettingsScreen extends LocalScreen implements HudPreview {
    /**
     * Settings and the services they control. The pages it opens come from navigation.
     */
    public interface Sources extends AudioPage.Sources, PlayerProgress.Sources {
        Navigation navigation();

        /**
         * Null until startup creates the player, library and history.
         */
        PlaybackController playback();

        MusicLibrary library();

        ListeningHistory listeningHistory();

        MediaControls mediaControls();

        MediaControlsPackage mediaPackage();

        DiscordPresence discord();

        CompletableFuture<Void> resetDefaults();
    }

    private final Sources app;
    private ScrollBar scrollbar;
    private final SettingsPreview preview;
    private final List<SettingsLayout.Placed> controls = new ArrayList<>();
    private final List<SettingsLayout.Section> sections = new ArrayList<>();
    private final List<SettingsLayout.Subsection> subsections = new ArrayList<>();
    private Settings shownSettings;
    private int folderY;
    private String folderPath = "";
    private List<FormattedCharSequence> folderLines = List.of();
    private int scroll, top, bottom, formWidth, cursor;
    private int formX, selectedSection;
    private static final String[] SECTION_KEYS = {"phase2.section.0", "settings.audio_section", "lyrics.section", "settings.appearance_section", "phase2.section.1", "phase2.section.2", "phase2.section.5"};
    private final int[] sectionScroll = new int[SECTION_KEYS.length];
    private boolean previewOnly, previewVisible;
    private final SettingsSearch search = new SettingsSearch();

    public SettingsScreen(Sources app, Screen parent) {
        super(Text.component("text.darkmusic.darkmusic_settings"), parent);
        this.app = app;
        preview = new SettingsPreview(app);
    }

    /**
     * Where the live preview is drawn, its own scale, and the player progress bar inside it. Read only.
     */
    public record PreviewBounds(int x, int y, int width, int height, double scale, int progressX, int progressY,
                                int progressWidth, int progressHeight) {
    }

    public PreviewBounds previewBounds() {
        var progress = preview.playerProgress();
        return new PreviewBounds(preview.x(), preview.y(), preview.width(), preview.height(), preview.scale(),
            progress.getX(), progress.getY(), progress.getWidth(), progress.getHeight());
    }

    /**
     * The value after {@code value} in declaration order, wrapping to the first.
     */
    static <E extends Enum<E>> E next(E value) {
        var values = value.getDeclaringClass().getEnumConstants();
        return values[(value.ordinal() + 1) % values.length];
    }

    private void heading(GuiGraphicsExtractor g, String text, int y) {
        g.fill(formX, y + 17, formX + formWidth, y + 18, UiTheme.BORDER);
        g.text(font, text, formX + 2, y + 3, UiTheme.ACCENT);
    }

    @Override
    protected void init() {
        backButton();
        if (shownSettings != null && shownSettings != app.settings()) {
            preview.reset();
            scroll = 0;
            Arrays.fill(sectionScroll, 0);
            selectedSection = 0;
        }
        shownSettings = app.settings();
        controls.clear();
        sections.clear();
        subsections.clear();
        boolean wide = width >= 800 && height >= 360;
        if (wide) previewOnly = false;
        formX = wide ? 136 : 12;
        formWidth = wide ? Math.min(400, (width - 172) / 2) : width - 24;
        int previewX = wide ? formX + formWidth + 12 : 12, previewY = wide ? 44 : 68;
        preview.place(previewX, previewY, wide ? width - previewX - 12 : width - 24, height - previewY - 44);
        previewVisible = wide || previewOnly;
        int searchY = wide ? 44 : 68;
        top = searchY + 26;
        bottom = height - 12;
        var searchInput = addRenderableWidget(new UiInput(font, formX, searchY, formWidth, 20, Text.component("settings.search")));
        searchInput.setMaxLength(100);
        searchInput.setHint(Text.component("settings.search_hint"));
        searchInput.setValue(search.query());
        searchInput.setResponder(text -> {
            search.query(text);
            scroll = 0;
            position();
        });
        searchInput.visible = !previewOnly;
        for (int i = 0; i < SECTION_KEYS.length; i++) {
            final int index = i;
            if (wide || i == selectedSection) {
                var button = addRenderableWidget(UiButton.builder(Text.component(SECTION_KEYS[i]), b -> {
                    search.query("");
                    sectionScroll[selectedSection] = scroll;
                    selectedSection = wide ? index : (selectedSection + 1) % SECTION_KEYS.length;
                    scroll = sectionScroll[selectedSection];
                    previewOnly = false;
                    rebuildWidgets();
                }).bounds(12, wide ? 44 + i * 28 : 38, wide ? 112 : 140, 24).build());
                if (SECTION_KEYS[i].equals("lyrics.section")) UiButton.icon(button, null, false);
                UiButton.selected(button, () -> selectedSection == index && !searching());
            }
        }
        if (!wide)
            addRenderableWidget(UiButton.builder(Text.component(previewOnly ? "phase2.settings_form" : "phase2.preview"), b -> {
                previewOnly = !previewOnly;
                rebuildWidgets();
            }).bounds(width - 120, 38, 108, 24).build());
        if (previewVisible && (app.settings().hudEnabled || LyricsHud.enabled(app.settings()))) {
            int layoutButtonWidth = Math.min(preview.width(), font.width(Text.get("text.darkmusic.edit_hud_layout")) + 32);
            UiButton.icon(addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.edit_hud_layout"),
                    button -> show(app.navigation().hudEditor(this)))
                .bounds(preview.x() + (preview.width() - layoutButtonWidth) / 2, preview.y() + preview.height() + 4, layoutButtonWidth, 24).build()), UiIcon.EDIT, false);
        }
        buildRows();
        scrollbar = addRenderableWidget(new ScrollBar(() -> scroll, value -> {
            scroll = value;
            position();
        }));
        position();
        app.applySettings();
    }

    private boolean searching() {
        return search.searching();
    }

    /**
     * Matching rows from all sections in order, each section introduced by its heading.
     */
    private void positionSearch() {
        int height = search.arrange(controls, sections);
        scroll = Math.clamp(scroll, 0, Math.max(0, height - (bottom - top)));
        if (scrollbar != null) {
            scrollbar.configure(formX + formWidth + 3, top, bottom - top, height, bottom - top);
            scrollbar.visible &= !previewOnly;
        }
        for (var p : controls) {
            Integer row = search.row(p.widget());
            int at = top + (row == null ? 0 : row) - scroll;
            p.widget().setX(formX);
            p.widget().setY(at);
            p.widget().visible = !previewOnly && row != null && at >= top && at + 20 <= bottom;
            if (!p.widget().visible && getFocused() == p.widget()) setFocused(null);
        }
    }

    /**
     * The form's rows, section by section, in the order they are shown and tabbed through.
     */
    private void buildRows() {
        var s = app.settings();
        var layout = new SettingsLayout(this, app, this::addRenderableWidget, this::rebuildWidgets, controls, sections, subsections, formWidth);
        var general = GeneralSettingsSection.build(layout, app, s, font);
        folderY = general.folderY();
        folderPath = general.folderPath();
        folderLines = general.folderLines();
        shownMedia = general.media();
        audioSettings(layout);
        LyricsSettingsSection.build(layout, app, s);
        appearanceSettings(layout, s);
        HudSettingsSection.build(layout, app, s);
        SpectrumSettingsSection.build(layout, app, s);
        layout.section("reset.darkmusic.section");
        layout.action("reset.darkmusic.button", () -> show(new ConfirmationScreen(this, Text.component("reset.darkmusic.title"),
            Text.component("reset.darkmusic.body"), Text.component("reset.darkmusic.confirm"), app::resetDefaults)), "reset.darkmusic.help").destructive();
        cursor = layout.cursor;
    }

    private void audioSettings(SettingsLayout layout) {
        layout.section("settings.audio_section");
        layout.action("audio.darkmusic.title", () -> show(app.navigation().audioSettings(this)), "audio.darkmusic.options_help");
        layout.action("environment.title", () -> show(app.navigation().soundEnvironment(this)), "environment.scope");
    }

    private void appearanceSettings(SettingsLayout layout, Settings s) {
        layout.section("settings.appearance_section");
        layout.action("settings.darkmusic.menu_background", () -> show(new ColorPickerScreen(this, s.menuBackgroundColor, 0x000000, color -> {
            s.menuBackgroundColor = color;
            app.save();
        })), "settings.darkmusic.menu_background_help");
        layout.action("settings.darkmusic.menu_accent", () -> show(new ColorPickerScreen(this, s.menuAccentColor, 0xB8A8FA, color -> {
            s.menuAccentColor = color;
            app.save();
        })), "settings.darkmusic.menu_accent_help");
    }

    private void position() {
        if (searching()) {
            positionSearch();
            return;
        }
        int origin = sections.get(selectedSection).y();
        int end = selectedSection < sections.size() - 1 ? sections.get(selectedSection + 1).y() : cursor;
        scroll = Math.clamp(scroll, 0, Math.max(0, end - origin - (bottom - top)));
        sectionScroll[selectedSection] = scroll;
        if (scrollbar != null) {
            scrollbar.configure(formX + formWidth + 3, top, bottom - top, end - origin, bottom - top);
            scrollbar.visible &= !previewOnly;
        }
        for (var p : controls) {
            int y = top + p.y() - origin - scroll;
            p.widget().setX(formX);
            p.widget().setY(y);
            p.widget().visible = !previewOnly && p.section() == selectedSection && y >= top && y + 20 <= bottom;
            if (!p.widget().visible && getFocused() == p.widget()) setFocused(null);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        var rows = controls.stream().filter(p -> searching() ? search.row(p.widget()) != null : p.section() == selectedSection).toList();
        int current = IntStream.range(0, rows.size()).filter(i -> rows.get(i).widget() == getFocused()).findFirst().orElse(-1);
        if (!previewOnly && event.key() == 258 && current >= 0) {
            int next = FocusRows.next(rows.stream().map(p -> p.widget().active).toList(), current, event.hasShiftDown(), false);
            if (next >= 0) {
                var row = rows.get(next);
                int y = searching() ? search.row(row.widget()) : row.y() - sections.get(selectedSection).y();
                if (y < scroll || y + 20 > scroll + bottom - top) scroll = Math.max(0, y - (bottom - top - 24) / 2);
                position();
                setFocused(row.widget());
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (HorizontalText.horizontalIntent(dx, dy)) return super.mouseScrolled(x, y, dx, dy);
        if (previewVisible && (lyricsPreview() ? LyricsHud.enabled(app.settings()) : app.settings().hudEnabled)
            && preview.zoom(x, y, dy, lyricsPreview(), progressPreview()))
            return true;
        if (!previewOnly && x >= formX && x <= formX + formWidth && y >= top && y <= bottom) {
            scroll -= (int) Math.round(dy * 24);
            position();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public void tick() {
        if (!folderPath.equals(app.library().root().toString())) rebuildWidgets();
        // The Media Controls row shows installation progress and the connection as they change.
        String media = GeneralSettingsSection.mediaSignature(app);
        if (!media.equals(shownMedia)) rebuildWidgets();
    }

    private String shownMedia = "";

    private boolean lyricsPreview() {
        return SECTION_KEYS[selectedSection].equals("lyrics.section");
    }

    @Override
    public boolean previewsProgress() {
        return previewVisible && (progressPreview() || app.settings().hudEnabled && !lyricsPreview());
    }

    private boolean progressPreview() {
        return SECTION_KEYS[selectedSection].equals("phase2.section.1");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.SETTINGS);
        if (!previewOnly) UiTheme.panel(g, formX - 2, top - 2, formWidth + 4, bottom - top + 4);
        if (previewVisible) preview.render(g, font, longText, delta, lyricsPreview(), progressPreview());
        if (!previewOnly && searching()) {
            g.enableScissor(formX, top, formX + formWidth, bottom);
            for (var heading : search.headings()) heading(g, heading.title(), top + heading.y() - scroll);
            if (search.headings().isEmpty())
                g.text(font, HorizontalText.ellipsize(font, Text.get("settings.search_none", search.query().strip()), formWidth - 8), formX + 4, top + 6, UiTheme.MUTED);
            g.disableScissor();
        } else if (!previewOnly) {
            g.enableScissor(formX, top, formX + formWidth, bottom);
            var section = sections.get(selectedSection);
            int sy = top - scroll;
            heading(g, section.title(), sy);
            for (var group : subsections)
                if (group.section() == selectedSection)
                    heading(g, group.title(), top + group.y() - section.y() - scroll);
            int pathY = top + folderY - section.y() - scroll + 3;
            if (selectedSection == 0)
                for (var line : folderLines) {
                    g.text(font, line, formX + 7, pathY, UiTheme.SECONDARY);
                    pathY += 10;
                }
            g.disableScissor();
        }
        super.extractRenderState(g, x, y, delta);
    }

    @Override
    public void removed() {
        super.removed();
        app.applySettings();
    }
}
