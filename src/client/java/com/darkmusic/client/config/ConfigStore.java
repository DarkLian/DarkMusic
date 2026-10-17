package com.darkmusic.client.config;

import com.darkmusic.client.audio.PlaybackController.Repeat;
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
                    warning.accept("Configuration could not be loaded; original preserved. " + e.getMessage());
                }
            }
        } catch (IOException e) {
            warning.accept("Cannot create the music folder: " + e.getMessage());
        }
        settings.validate();
        Path game = directory.toAbsolutePath().getParent().getParent();
        Path options = game.resolve("options.txt");
        if (Files.isRegularFile(options)) try (var lines = Files.lines(options)) {
            settings.nativeShortcutNames = lines.filter(s -> s.startsWith("key_key.darkmusic.") && s.indexOf(':') > 4)
                .map(s -> s.substring(4, s.indexOf(':'))).collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            warning.accept("Could not read native shortcut migration state: " + e.getMessage());
        }
        return settings;
    }

    public void save(Settings settings) {
        String json = JSON.toJson(settings);
        synchronized (this) {
            pending = json;
            if (writing) return;
            writing = true;
            io.execute(this::drainWrites);
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
            } catch (IOException e) {
                warning.accept("Could not save settings: " + e.getMessage());
            }
        }
    }
}
