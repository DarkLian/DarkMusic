package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import com.darkmusic.client.DarkMusicClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FolderScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Screen parent;
    private Path directory;
    private EditBox path;
    private List<Path> children = List.of();
    private String error = "";
    private int page;
    private volatile long request;
    private java.util.concurrent.Future<?> listing;

    public FolderScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.choose_music_folder"), parent);
        this.app = app;
        this.parent = parent;
        directory = app.library.root();
    }

    @Override
    protected void init() {
        path = addRenderableWidget(new EditBox(font, 10, 30, Math.max(80, width - 90), 20, Text.component("text.darkmusic.absolute_directory_path")));
        path.setMaxLength(4096);
        path.setValue(directory.toString());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.browse"), b -> {
            try {
                Path selected = Path.of(path.getValue());
                if (!selected.isAbsolute())
                    throw new IllegalArgumentException(Text.get("text.darkmusic.enter_an_absolute_path"));
                browse(selected);
            } catch (Exception e) {
                error = e.getMessage();
            }
        }).bounds(width - 74, 30, 64, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.up"), b -> browse(directory.getParent())).bounds(10, 55, 50, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.roots"), b -> browse(null)).bounds(64, 55, 56, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.previous"), b -> {
            page = Math.max(0, page - 1);
            rebuildWidgets();
        }).bounds(124, 55, 66, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.next"), b -> {
            if ((page + 1) * rows() < children.size()) {
                page++;
                rebuildWidgets();
            }
        }).bounds(194, 55, 50, 20).build());
        for (int i = page * rows(), end = Math.min(children.size(), (page + 1) * rows()); i < end; i++) {
            Path child = children.get(i);
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(child.toString(), Math.max(40, width - 40))), b -> browse(child)).bounds(10, 82 + (i - page * rows()) * 22, width - 20, 20).build());
        }
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.use_this_folder"), b -> app.chooseDirectory(directory, () -> show(parent))).bounds(10, height - 28, 110, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.default"), b -> browse(app.config.defaultMusic())).bounds(124, height - 28, 64, 20).build());
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.cancel"), b -> onClose()).bounds(width - 74, height - 28, 64, 20).build());
        if (request == 0) browse(directory);
    }

    private int rows() {
        return Math.max(1, (height - 140) / 22);
    }

    private void browse(Path target) {
        long ticket = ++request;
        error = Text.get("text.darkmusic.loading");
        if (listing != null) listing.cancel(true);
        listing = app.browsing.submit(() -> {
            List<Path> entries = new ArrayList<>();
            String problem = "";
            try {
                if (target == null) for (var root : java.io.File.listRoots()) entries.add(root.toPath());
                else try (var stream = Files.list(target)) {
                    stream.takeWhile(p -> !Thread.currentThread().isInterrupted()).filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)).forEach(entries::add);
                }
            } catch (Exception e) {
                problem = "Cannot read folder: " + e.getMessage();
            }
            String result = problem;
            minecraft.execute(() -> {
                if (ticket != request || !isCurrent()) return;
                if (result.isEmpty() && target != null) directory = target.toAbsolutePath().normalize();
                children = List.copyOf(entries);
                error = result;
                page = 0;
                rebuildWidgets();
            });
        });
    }

    @Override
    public void removed() {
        request++;
        if (listing != null) listing.cancel(true);
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, Text.get("text.darkmusic.choose_a_music_folder"), 10, 12, 0xFFB5B0FF);
        String notice = !error.isEmpty() ? error : !app.warning.isEmpty() ? app.warning : Text.get("text.darkmusic.audio_files_will_only_be_read");
        g.text(font, font.plainSubstrByWidth(notice, width - 20), 10, height - 44, 0xFFFFCC88);
        super.extractRenderState(g, x, y, d);
    }
}
