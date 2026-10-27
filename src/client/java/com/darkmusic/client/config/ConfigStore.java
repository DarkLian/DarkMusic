package com.darkmusic.client.config;

import com.darkmusic.client.text.Text;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * All filesystem access runs on the supplied serial I/O executor.
 */
public final class ConfigStore {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(Repeat.class,
        new TypeAdapter<Repeat>() {
            @Override
            public void write(JsonWriter writer, Repeat value) throws IOException {
                writer.value(value == null ? "OFF" : value.name());
            }

            @Override
            public Repeat read(JsonReader reader) throws IOException {
                if (reader.peek() == JsonToken.STRING) {
                    String value = reader.nextString();
                    try {
                        return Repeat.valueOf(value);
                    } catch (IllegalArgumentException ignored) {
                    }
                } else reader.skipValue();
                return Repeat.OFF;
            }
        }).create();
    private final Path directory;
    private final ExecutorService io;
    private final Consumer<String> warning;
    private boolean preserveInvalid;
    private String pending;
    private boolean writing;
    private volatile String snapshotJson;

    /**
     * Immutable preferences already serialized by load/save; safe to capture without doing export work on the UI thread.
     */
    public String snapshotJson() {
        return java.util.Objects.requireNonNull(snapshotJson, "Settings have not loaded");
    }

    public void committedSnapshot(String json) {
        snapshotJson = java.util.Objects.requireNonNull(json);
    }

    public ConfigStore(Path directory, ExecutorService io, Consumer<String> warning) {
        this.directory = directory;
        this.io = io;
        this.warning = warning;
    }

    public static void atomicWrite(Path destination, String contents) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = StandardCharsets.UTF_8.encode(contents);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path directory() {
        return directory;
    }

    public Path defaultMusic() {
        return directory.resolve("music");
    }

    public Settings load() {
        boolean recovered;
        try {
            recovered = RestoreJournal.recover(directory);
            if (recovered)
                warning.accept(Text.get("backup.recovered"));
        } catch (IOException failure) {
            // Never load or save partially restored data when recovery cannot finish.
            throw new java.util.concurrent.CompletionException(failure);
        }
        Settings settings = new Settings();
        try {
            Files.createDirectories(defaultMusic());
            Path file = directory.resolve("config.json");
            if (Files.exists(file)) {
                try (var reader = Files.newBufferedReader(file)) {
                    settings = JSON.fromJson(reader, Settings.class);
                    if (settings == null || settings.schemaVersion != 1)
                        throw new IOException("Unsupported configuration version");
                } catch (Exception e) {
                    preserveInvalid = true;
                    settings = new Settings();
                    warning.accept(Text.failure(e, "error.config_load"));
                }
            }
        } catch (IOException e) {
            warning.accept(Text.failure(e, "error.music_folder_create"));
        }
        settings.validate();
        Path game = directory.toAbsolutePath().getParent().getParent();
        Path options = game.resolve("options.txt");
        if (Files.isRegularFile(options)) try (var lines = Files.lines(options)) {
            var bindings = lines.filter(s -> s.startsWith("key_key.darkmusic.") && s.indexOf(':') > 4).toList();
            settings.nativeShortcutNames = recovered ? java.util.Set.of() : bindings.stream()
                .map(s -> s.substring(4, s.indexOf(':'))).collect(java.util.stream.Collectors.toUnmodifiableSet());
            // Minecraft loaded options before the mod recovered the file. Reapply recovered keys in ready().
            if (recovered) for (String binding : bindings) {
                int colon = binding.indexOf(':');
                settings.shortcuts.put(binding.substring(4, colon), binding.substring(colon + 1));
            }
        } catch (IOException e) {
            warning.accept(Text.failure(e, "error.shortcut_load"));
        }
        snapshotJson = JSON.toJson(settings);
        return settings;
    }

    /**
     * Writes only DarkMusic's native bindings; Options.save() would broadcast unrelated options.
     */
    public void resetShortcuts(java.util.Map<String, String> defaults) throws IOException {
        Path options = directory.toAbsolutePath().getParent().getParent().resolve("options.txt");
        if (Files.isSymbolicLink(options) || Files.exists(options, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS) || Files.size(options) > 16_000_000))
            throw new IOException(Text.get("reset.darkmusic.options_unsafe"));
        atomicWrite(options, shortcutsContents(defaults));
    }

    public String shortcutsContents(java.util.Map<String, String> defaults) throws IOException {
        Path options = directory.toAbsolutePath().getParent().getParent().resolve("options.txt");
        if (Files.isSymbolicLink(options) || Files.exists(options) && (!Files.isRegularFile(options) || Files.size(options) > 16_000_000))
            throw new IOException("Unsafe options file");
        var lines = Files.exists(options) ? Files.readAllLines(options, StandardCharsets.UTF_8) : java.util.List.<String>of();
        var remaining = new java.util.LinkedHashMap<>(defaults);
        var updated = new java.util.ArrayList<String>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            String name = colon < 0 ? "" : line.substring(0, colon);
            if (defaults.containsKey(name)) {
                String value = remaining.remove(name);
                if (value != null) updated.add(name + ":" + value);
            } else updated.add(line);
        }
        remaining.forEach((name, value) -> updated.add(name + ":" + value));
        return String.join("\n", updated) + "\n";
    }

    public void save(Settings settings) {
        String json = JSON.toJson(settings);
        snapshotJson = json;
        synchronized (this) {
            pending = json;
            if (writing) return;
            writing = true;
            try {
                io.execute(this::drainWrites);
            } catch (RuntimeException failure) {
                writing = false;
                throw failure;
            }
        }
    }

    private void drainWrites() {
        while (true) {
            String json;
            synchronized (this) {
                json = pending;
                pending = null;
                if (json == null) {
                    writing = false;
                    return;
                }
            }
            try {
                if (preserveInvalid && Files.exists(directory.resolve("config.json"))) {
                    Files.copy(directory.resolve("config.json"), directory.resolve("config.invalid-" + System.currentTimeMillis() + ".json"));
                    preserveInvalid = false;
                }
                atomicWrite(directory.resolve("config.json"), json);
            } catch (Exception e) {
                warning.accept(Text.failure(e, "error.save_settings"));
            }
        }
    }
}
