package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            }
        }
        assertEquals(List.of(), violations.stream().distinct().sorted().toList());
    }

    private static boolean allowed(String from, String to) {
        Integer source = CORE.get(from), target = CORE.get(to);
        if (source != null) return target != null && target < source;
        if (from.equals("render")) return !SCREENS.contains(to);
        if (from.equals("hud") || from.equals("pip")) return !to.equals("gui");
        return true;
    }
}
