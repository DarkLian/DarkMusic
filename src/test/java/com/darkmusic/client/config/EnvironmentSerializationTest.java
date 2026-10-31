package com.darkmusic.client.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentSerializationTest {
    private static final Path EVIDENCE = Path.of(".local-tools/verification/deferred");
    private static final boolean RECORD = "true".equals(System.getenv("DARKMUSIC_ENVIRONMENT_RECORD"));

    private String hash(String json) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String roundTrip(Settings settings) {
        String json = ConfigStore.JSON.toJson(settings);
        assertEquals(json, ConfigStore.JSON.toJson(ConfigStore.JSON.fromJson(json, Settings.class)));
        return json;
    }

    private String savedPreset(Settings settings) {
        var environment = settings.environment;
        // Detached settings only; never write back to the player's config.
        environment.savePreset("Deferred verification", true);
        String json = ConfigStore.JSON.toJson(environment.userPresets.get("Deferred verification"));
        assertEquals(json, ConfigStore.JSON.toJson(ConfigStore.JSON.fromJson(json, SoundEnvironmentSettings.Saved.class)));
        return json;
    }

    private void write(Path file, Properties values) throws Exception {
        Files.createDirectories(file.getParent());
        try (var out = Files.newOutputStream(file)) {
            values.store(out, "Serialization SHA-256 only");
        }
    }

    @Test
    void everyRecipeAndASavedPresetMatchThePreMoveSerialization() throws Exception {
        var actual = new Properties();
        for (var preset : SoundEnvironmentSettings.Preset.values())
            actual.setProperty(preset.name(), hash(ConfigStore.JSON.toJson(SoundEnvironmentSettings.recipe(preset))));
        var settings = new Settings();
        settings.environment.select(SoundEnvironmentSettings.Preset.CATHEDRAL);
        settings.environment.parameters.pan = .3;
        actual.setProperty("saved", hash(savedPreset(settings)));
        roundTrip(settings);
        if (RECORD) {
            write(EVIDENCE.resolve("environment-recipes.properties"), actual);
            return;
        }
        var expected = new Properties();
        try (var in = getClass().getResourceAsStream("/environment-recipes-golden.properties")) {
            assertNotNull(in);
            expected.load(in);
        }
        assertEquals(expected, actual);
    }

    @Test
    void realConfigAndItsSavedPresetKeepIdenticalBytesAcrossTheMove() throws Exception {
        Path config = Path.of("run/config/darkmusic/config.json");
        Assumptions.assumeTrue(Files.isRegularFile(config), "Local player config unavailable on hosted CI");
        String source = Files.readString(config);
        var settings = ConfigStore.JSON.fromJson(source, Settings.class);
        var actual = new Properties();
        actual.setProperty("input", hash(source));
        actual.setProperty("config", hash(roundTrip(settings)));
        actual.setProperty("saved", hash(savedPreset(settings)));
        Path baseline = EVIDENCE.resolve("environment-local.properties");
        if (RECORD) {
            write(baseline, actual);
            return;
        }
        Assumptions.assumeTrue(Files.isRegularFile(baseline), "No local pre-move snapshot recorded");
        var expected = new Properties();
        try (var in = Files.newInputStream(baseline)) {
            expected.load(in);
        }
        Assumptions.assumeTrue(expected.getProperty("input").equals(actual.getProperty("input")), "Player config changed after the local snapshot");
        assertEquals(expected, actual);
    }
}
