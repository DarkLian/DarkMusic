package com.darkmusic.client.gui;

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
    private long request;

    public FolderScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("Choose Music Folder"), parent);
        this.app = app;
        this.parent = parent;
        directory = app.library.root();
    }

    @Override
    protected void init() {
        path = addRenderableWidget(new EditBox(font, 10, 30, Math.max(80, width - 90), 20, Component.literal("Absolute directory path")));
        path.setMaxLength(4096);
        path.setValue(directory.toString());
        addRenderableWidget(Button.builder(Component.literal("Browse"), b -> {
            try {
                Path selected = Path.of(path.getValue());
                if (!selected.isAbsolute()) throw new IllegalArgumentException("Enter an absolute path");
                browse(selected);
            } catch (Exception e) {
                error = e.getMessage();
            }
        }).bounds(width - 74, 30, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Up"), b -> browse(directory.getParent())).bounds(10, 55, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Roots"), b -> browse(null)).bounds(64, 55, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Previous"), b -> {
            page = Math.max(0, page - 1);
            rebuildWidgets();
        }).bounds(124, 55, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"), b -> {
            if ((page + 1) * rows() < children.size()) {
                page++;
                rebuildWidgets();
            }
        }).bounds(194, 55, 50, 20).build());
        for (int i = page * rows(), end = Math.min(children.size(), (page + 1) * rows()); i < end; i++) {
            Path child = children.get(i);
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(child.toString(), Math.max(40, width - 40))), b -> browse(child)).bounds(10, 82 + (i - page * rows()) * 22, width - 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Use This Folder"), b -> app.chooseDirectory(directory, () -> show(parent))).bounds(10, height - 28, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Default"), b -> browse(app.config.defaultMusic())).bounds(124, height - 28, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width - 74, height - 28, 64, 20).build());
        if (request == 0) browse(directory);
    }

    private int rows() {
        return Math.max(1, (height - 140) / 22);
    }

    private void browse(Path target) {
        long ticket = ++request;
        error = "Loading...";
        app.io.execute(() -> {
            List<Path> entries = new ArrayList<>();
            String problem = "";
            try {
                if (target == null) for (var root : java.io.File.listRoots()) entries.add(root.toPath());
                else try (var stream = Files.list(target)) {
                    stream.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)).forEach(entries::add);
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
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, "Choose a Music Folder", 10, 12, 0xFFB5B0FF);
        String notice = !error.isEmpty() ? error : !app.warning.isEmpty() ? app.warning : "Audio files will only be read.";
        g.text(font, font.plainSubstrByWidth(notice, width - 20), 10, height - 44, 0xFFFFCC88);
        super.extractRenderState(g, x, y, d);
    }
}
