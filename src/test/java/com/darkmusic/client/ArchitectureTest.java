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
    private static final int MAX_CLIENT_DEPENDENTS = 1;
    // DarkMusicClient is the entry point and lifecycle only. Lower this as it shrinks; never raise it.
    private static final int MAX_CLIENT_LINES = 290;
    private static final Path CLIENT = SOURCES.resolve("DarkMusicClient.java");
    private static final Path CLIENT_SOURCES = SOURCES.resolve("ClientSources.java");
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bclass\\s+(\\w+)[^{;]*?\\bimplements\\s+([^{]*)\\{");
    private static final Pattern SUPPLIED = Pattern.compile("\\b\\w+\\.Sources\\b|\\bSettingsSource\\b");
    private static final Pattern ANONYMOUS_SOURCES = Pattern.compile("\\bnew\\s+(?:\\w+\\.Sources|SettingsSource)\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern METHOD = Pattern.compile("\\b(\\w+)\\s*\\([^()]*\\)\\s*(?:throws\\s[^{;]*)?\\{");
    // return a.b(x, y);  a.b(x);  return a.b;  return a;  with plain names as arguments.
    private static final Pattern FORWARD = Pattern.compile("\\s*(?:return\\s+)?\\w+(?:\\.\\w+)?(?:\\([\\w\\s,]*\\))?\\s*;\\s*");
    private static final Pattern PUBLIC_FIELD = Pattern.compile("\\bpublic\\s+((?:(?:static|volatile|transient|final)\\s+)*)[\\w.<>\\[\\]?, ]+?\\s+(\\w+)\\s*[=;]");
    // A screen reaches the screens it opens through gui.Navigation, so its Sources never inherits theirs.
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

    @Test
    void darkMusicClientOnlyShrinks() throws IOException {
        long lines = Files.readAllLines(CLIENT).size();
        assertTrue(lines <= MAX_CLIENT_LINES, "DarkMusicClient has " + lines + " lines; the limit is " + MAX_CLIENT_LINES);
    }

    /**
     * DarkMusicClient implements only the Fabric entry point. ClientSources is the one supplier of the screens'
     * Sources, the shared pieces (SettingsSource, AudioPage.Sources) included; SettingsStore is the settings themselves.
     */
    @Test
    void darkMusicClientIsOnlyTheEntryPoint() throws IOException {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String code = COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" ");
                var declaration = IMPLEMENTS.matcher(code);
                while (declaration.find()) {
                    String type = declaration.group(1), implemented = declaration.group(2);
                    if (type.equals("DarkMusicClient") && !implemented.strip().equals("ClientModInitializer"))
                        violations.add("DarkMusicClient implements " + implemented.strip());
                    var supplied = SUPPLIED.matcher(implemented);
                    while (supplied.find())
                        if (!type.equals("ClientSources") && !(type.equals("SettingsStore") && supplied.group().equals("SettingsSource")))
                            violations.add(type + " implements " + supplied.group());
                }
                if (ANONYMOUS_SOURCES.matcher(code).find())
                    violations.add(SOURCES.relativize(file) + " implements Sources anonymously");
            }
        }
        assertEquals(List.of(), violations);
    }

    /**
     * Every method of ClientSources is one forwarding statement with plain names as arguments. A method that needs a
     * condition, a loop, a new object or a lambda belongs to a named collaborator, and ClientSources forwards to it.
     */
    @Test
    void clientSourcesOnlyForwards() throws IOException {
        String code = COMMENTS_AND_STRINGS.matcher(Files.readString(CLIENT_SOURCES)).replaceAll(" ");
        var violations = new ArrayList<String>();
        int methods = 0;
        var method = METHOD.matcher(code);
        // Continues after each body, so a block inside one is never read as a method of its own.
        for (int from = 0; method.find(from); ) {
            int open = method.end() - 1, close = matchingBrace(code, open);
            from = close + 1;
            if (method.group(1).equals("ClientSources")) continue;
            methods++;
            if (!FORWARD.matcher(code.substring(open + 1, close)).matches()) violations.add(method.group(1));
        }
        assertTrue(methods > 50, "Found only " + methods + " ClientSources methods; the check no longer reads the file");
        assertEquals(List.of(), violations);
    }

    /**
     * Services are replaced through ClientServices on the client thread, never by assigning a public field.
     */
    @Test
    void noPublicMutableClientFields() throws IOException {
        var mutable = new ArrayList<String>();
        for (Path file : List.of(CLIENT, SOURCES.resolve("ClientServices.java"))) {
            var field = PUBLIC_FIELD.matcher(COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" "));
            while (field.find())
                if (!field.group(1).contains("final") && !field.group(2).equals("INSTANCE"))
                    mutable.add(file.getFileName() + ": " + field.group(2));
        }
        assertEquals(List.of(), mutable);
    }

    private static int matchingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            else if (code.charAt(i) == '}' && --depth == 0) return i;
        }
        throw new IllegalStateException("Unbalanced braces after " + open);
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
        assertEquals(List.of(), inheriting);
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
