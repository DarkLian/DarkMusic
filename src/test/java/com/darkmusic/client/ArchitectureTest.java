package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package layering. Core packages form a strict order and never reach the interface; drawing helpers in render
 * stay independent of the screens and HUDs that use them.
 */
class ArchitectureTest {
    private static final Path SOURCES = Path.of("src/client/java/com/darkmusic/client");
    private static final Pattern REFERENCE = Pattern.compile("\\bcom\\.darkmusic\\.client\\.([a-z]\\w*)\\b(?=\\.[A-Z*])");
    private static final Pattern COMMENTS_AND_STRINGS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"", Pattern.DOTALL);
    // Lower ranks may not depend on higher ones; equal ranks may not depend on each other.
    private static final Map<String, Integer> CORE = Map.ofEntries(Map.entry("text", 0), Map.entry("config", 1),
        Map.entry("tools", 2), Map.entry("media", 3), Map.entry("library", 4), Map.entry("audio", 5),
        Map.entry("lyrics", 5), Map.entry("playlist", 5), Map.entry("cleanup", 6), Map.entry("download", 7),
        Map.entry("backup", 8));
    private static final Set<String> SCREENS = Set.of("gui", "hud", "pip");
    // These get their services handed in and may not reach back into DarkMusicClient or its root-package helpers.
    private static final Set<String> DECOUPLED = Set.of("command", "hud", "pip", "render", "update");
    // Files already moved off DarkMusicClient in packages that are not done yet; the package replaces them when it is.
    private static final Set<String> DECOUPLED_FILES = Set.of("gui/AudioAdvancedScreen.java", "gui/AudioAnalyzerScreen.java",
        "gui/AudioNodeScreen.java", "gui/AudioPage.java", "gui/AudioSettingsScreen.java", "gui/ColorsScreen.java",
        "gui/DesktopFolderPicker.java", "gui/EnvironmentPickerScreen.java", "gui/Help.java", "gui/ImportOverlay.java",
        "gui/LocalScreen.java", "gui/LyricCandidateScreen.java", "gui/LyricsConsent.java", "gui/LyricsLibraryScreen.java",
        "gui/LyricsOptionsScreen.java", "gui/LyricsScreen.java", "gui/LyricsSearchScreen.java", "gui/LyricsSettingsScreen.java",
        "gui/OutputDeviceScreen.java", "gui/PipEditorScreen.java", "gui/PipScreen.java", "gui/PipSourcesScreen.java",
        "gui/PipSpectrumScreen.java", "gui/PlayerProgress.java", "gui/RowList.java", "gui/SongCard.java",
        "gui/SoundEnvironmentScreen.java", "gui/TrackThumbnails.java");
    private static final Pattern ROOT_TYPE = Pattern.compile("\\bcom\\.darkmusic\\.client\\.[A-Z]\\w*");
    // Lower this as packages stop depending on DarkMusicClient; never raise it.
    private static final int MAX_CLIENT_DEPENDENTS = 28;

    @Test
    void packagesDependOnlyDownward() throws IOException {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String from = SOURCES.relativize(file).getNameCount() == 1 ? "" : SOURCES.relativize(file).getName(0).toString();
                String code = COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" ");
                var matcher = REFERENCE.matcher(code);
                while (matcher.find()) {
                    String to = matcher.group(1);
                    if (!to.equals(from) && !allowed(from, to))
                        violations.add(SOURCES.relativize(file) + " -> " + to);
                }
                if (CORE.containsKey(from) && code.contains("DarkMusicClient"))
                    violations.add(SOURCES.relativize(file) + " -> DarkMusicClient");
                var root = ROOT_TYPE.matcher(code);
                boolean decoupled = DECOUPLED.contains(from)
                    || DECOUPLED_FILES.contains(SOURCES.relativize(file).toString().replace('\\', '/'));
                if (decoupled && root.find())
                    violations.add(SOURCES.relativize(file) + " -> " + root.group());
            }
        }
        assertEquals(List.of(), violations.stream().distinct().sorted().toList());
    }

    @Test
    void darkMusicClientDependentsOnlyShrink() throws IOException {
        long dependents;
        try (var files = Files.walk(SOURCES)) {
            dependents = files.filter(p -> p.toString().endsWith(".java") && !p.endsWith("DarkMusicClient.java"))
                .filter(p -> {
                    try {
                        return COMMENTS_AND_STRINGS.matcher(Files.readString(p)).replaceAll(" ").contains("DarkMusicClient");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).count();
        }
        assertTrue(dependents <= MAX_CLIENT_DEPENDENTS, dependents + " files depend on DarkMusicClient; the limit is " + MAX_CLIENT_DEPENDENTS);
    }

    /**
     * Types are imported, not written out in full inside code: "java.util.List.of()" becomes "List.of()".
     */
    @Test
    void codeImportsTypesInsteadOfQualifyingThem() throws IOException {
        var qualified = Pattern.compile("(?<![\\w.$@])(?:java|javax|net|com|org|it)\\.(?:[a-z_][a-z0-9_]*\\.)+[A-Z]\\w*");
        var violations = new ArrayList<String>();
        try (var files = Files.walk(Path.of("src"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java") && p.getNameCount() > 2
                && p.getName(2).toString().equals("java")).toList()) {
                var code = COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" ");
                for (String line : code.lines().toList()) {
                    if (line.startsWith("import ") || line.startsWith("package ")) continue;
                    var matcher = qualified.matcher(line);
                    if (matcher.find()) violations.add(file + ": " + matcher.group());
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    private static boolean allowed(String from, String to) {
        Integer source = CORE.get(from), target = CORE.get(to);
        if (source != null) return target != null && target < source;
        if (from.equals("render")) return !SCREENS.contains(to);
        if (from.equals("hud") || from.equals("pip")) return !to.equals("gui");
        return true;
    }
}
