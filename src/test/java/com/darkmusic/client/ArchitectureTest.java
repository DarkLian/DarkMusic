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
    private static final Set<String> DECOUPLED = Set.of("command", "gui", "hud", "pip", "render", "update");
    private static final Pattern ROOT_TYPE = Pattern.compile("\\bcom\\.darkmusic\\.client\\.[A-Z]\\w*");
    // Lower this as packages stop depending on DarkMusicClient; never raise it.
    private static final int MAX_CLIENT_DEPENDENTS = 5;
    // A screen reaches the screens it opens through gui.Navigation, so its Sources never inherits theirs. These still
    // do; move each to Navigation and remove it here, never add one.
    private static final Set<String> NAVIGATION_BY_INHERITANCE = Set.of("DownloadScreen", "DownloadToolsScreen",
        "DuplicateComparisonScreen", "ExistingDownloadsScreen", "LibraryManagerScreen", "MetadataScreen",
        "MusicFoldersScreen", "PipScreen", "PlaylistScreen", "SmartCleanupScreen", "SongActionsScreen");
    private static final Pattern SOURCES_DECLARATION = Pattern.compile("interface Sources extends ([^{]*)\\{");
    private static final Pattern SCREEN_SOURCES = Pattern.compile("\\b(\\w+Screen)\\.Sources\\b");

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
                if (DECOUPLED.contains(from) && root.find())
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
     * Shared pieces (SettingsSource, AudioPage.Sources, ImportOverlay.Sources, PlayerProgress.Sources) are fine; another
     * screen's Sources is not, because it hands this screen everything that screen needs.
     */
    @Test
    void screensDoNotInheritTheSourcesOfScreensTheyOpen() throws IOException {
        var inheriting = new ArrayList<String>();
        try (var files = Files.list(SOURCES.resolve("gui"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                var declaration = SOURCES_DECLARATION.matcher(COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" "));
                if (declaration.find() && SCREEN_SOURCES.matcher(declaration.group(1)).find())
                    inheriting.add(file.getFileName().toString().replace(".java", ""));
            }
        }
        assertEquals(NAVIGATION_BY_INHERITANCE.stream().sorted().toList(), inheriting);
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
