package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    // ClientServices holds the services; it is the next place a hurried change would land. Lower this; never raise it.
    private static final int MAX_SERVICES_LINES = 572;
    private static final Path TESTS = Path.of("src/test/java/com/darkmusic/client");
    private static final Set<String> ROOT_TESTS = new TreeSet<>(Set.of("ArchitectureTest", "BackupActionsTest",
        "BackupRestoreTest", "CleanupTest", "ClientStateTest", "DownloadActionsTest", "FolderActionsTest",
        "LibraryReconciliationTest", "LyricsActionsTest", "MediaActionsTest", "MethodLengthTest", "OutputActionsTest", "PackagedJarTest",
        "PlaybackCommandsTest", "PlaylistActionsTest", "ResetOperationTest", "ResetStopTest", "RestoreOperationTest",
        "ScreenOperationsTest", "SettingsSettersTest", "SmokeReflectionTest", "StartupShutdownTest", "WarningsTest"));
    // The structure guards name root classes to check them, not to test what they do.
    private static final Set<String> GUARDS = Set.of("ArchitectureTest", "MethodLengthTest", "PackagedJarTest",
        "ScreenOperationsTest", "SettingsSettersTest", "SmokeReflectionTest");
    // Root classes no test names, each with why. Only shrinks: a new root class needs a test or an entry here.
    private static final Map<String, String> UNTESTED_ROOT = Map.of(
        "DarkMusicClient", "the Fabric entry point; the smoke suite starts it",
        "ClientServices", "builds the real services and needs Minecraft and Fabric; the smoke suite covers it",
        "ScreenRequests", "opens Minecraft screens; the smoke suite covers it",
        "Shortcuts", "registers and reads Minecraft key mappings; the smoke suite covers it",
        "ClientActions", "chat command actions that open screens; the smoke suite covers it",
        "ClientReset", "the reset steps over the real services; ResetOperationTest covers the phases, smoke reset the rest",
        "ClientSources", "forwards only (clientSourcesOnlyForwards)",
        "ScreenNavigation", "forwards screen changes to ScreenRequests",
        "ClientDispatch", "forwards to the client thread");
    // A class name's words: "AudioUx231Test" is Audio, Ux, 231, Test.
    private static final Pattern WORDS = Pattern.compile("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Z])");
    private static final Pattern PROCESS_WORD = Pattern.compile("Review|Audit|Polish|Refinements?|Regression|Ux|\\d{3}");
    private static final Path CLIENT_SERVICES = SOURCES.resolve("ClientServices.java");
    private static final Path CLIENT = SOURCES.resolve("DarkMusicClient.java");
    private static final Path CLIENT_SOURCES = SOURCES.resolve("ClientSources.java");
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bclass\\s+(\\w+)[^{;]*?\\bimplements\\s+([^{]*)\\{");
    private static final Pattern SUPPLIED = Pattern.compile("\\b\\w+\\.Sources\\b|\\bSettingsSource\\b");
    private static final Pattern ANONYMOUS_SOURCES = Pattern.compile("\\bnew\\s+(?:\\w+\\.Sources|SettingsSource)\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern METHOD = Pattern.compile("\\b(\\w+)\\s*\\([^()]*\\)\\s*(?:throws\\s[^{;]*)?\\{");
    // return a.b(x, y);  a.b(x);  return a.b;  return a;  with plain names as arguments.
    // if (field == null) field = ...; return field;  or the same with a block that builds it.
    private static final Pattern LAZY = Pattern.compile("\\s*if\\s*\\(\\s*(\\w+)\\s*==\\s*null\\s*\\)\\s*(?:\\{[^{}]*\\}|[^{};]+;)\\s*return\\s+\\1\\s*;\\s*");
    private static final Pattern ANONYMOUS_CLASS = Pattern.compile("\\bnew\\s+[\\w.<>]+\\s*$");
    // ClientServices methods that hold the services' wiring and replacement, each with why it is not a plain getter.
    private static final Map<String, String> SERVICES_WIRING = Map.of(
        "browse", "submits to the browse pool and turns a full queue into the busy warning",
        "audition", "the download screens install and clear their preview player",
        "takeAudition", "reset takes the preview player before it stops the services",
        "closeCleanup", "reset closes the duplicate scanner if one was opened",
        "closeSourceIndex", "reset closes the source index if one was opened",
        "forgetCleanupReviews", "reset drops the review store",
        "reopenArtwork", "the smoke check replaces the artwork cache it closed",
        "downloadsConnected", "null-safe check of the current download service for the reset smoke check");
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
        long services = Files.readAllLines(CLIENT_SERVICES).size();
        assertTrue(services <= MAX_SERVICES_LINES, "ClientServices has " + services + " lines; the limit is " + MAX_SERVICES_LINES);
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
        var methods = methods(CLIENT_SOURCES, "ClientSources");
        assertTrue(methods.size() > 50, "Found only " + methods.size() + " ClientSources methods; the check no longer reads the file");
        assertEquals(List.of(), methods.stream().filter(m -> !FORWARD.matcher(m.getValue()).matches()).map(Map.Entry::getKey).toList());
    }

    /**
     * ClientServices holds and builds the services; it makes no decisions. Its build methods (build*, create*, apply,
     * withdraw) and the named wiring are exempt; every other method is a getter, a lazy getter or one forwarding
     * statement. The HUD, PiP and reconciliation adapters are anonymous classes whose methods read the current
     * service; they are not checked.
     */
    @Test
    void clientServicesHoldsNoPolicy() throws IOException {
        var methods = methods(CLIENT_SERVICES, "ClientServices");
        assertTrue(methods.size() > 40, "Found only " + methods.size() + " ClientServices methods; the check no longer reads the file");
        assertEquals(List.of(), methods.stream().filter(m -> {
            String name = m.getKey(), body = m.getValue();
            boolean builds = name.startsWith("build") || name.startsWith("create") || name.equals("apply") || name.equals("withdraw");
            return !builds && !SERVICES_WIRING.containsKey(name) && !FORWARD.matcher(body).matches() && !LAZY.matcher(body).matches();
        }).map(Map.Entry::getKey).toList());
    }

    /**
     * The methods of one class's source, name and body, without its constructors or the bodies of anonymous classes.
     * Each search continues after the body it found, so a block inside a method is never read as a method of its own.
     */
    private static List<Map.Entry<String, String>> methods(Path file, String type) throws IOException {
        String code = COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" ");
        var methods = new ArrayList<Map.Entry<String, String>>();
        var method = METHOD.matcher(code);
        for (int from = 0; method.find(from); ) {
            int open = method.end() - 1, close = matchingBrace(code, open);
            from = close + 1;
            boolean anonymous = ANONYMOUS_CLASS.matcher(code.substring(Math.max(0, method.start() - 80), method.start() + method.group(1).length())).find();
            if (!anonymous && !method.group(1).equals(type))
                methods.add(Map.entry(method.group(1), code.substring(open + 1, close)));
        }
        return methods;
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

    /**
     * Tests live in their subject's package. The root holds only tests of the root package's own classes, the
     * structure guards and the packaged-JAR check; fixtures go in testing.
     */
    @Test
    void rootTestsAreAllowlisted() throws IOException {
        try (var files = Files.list(TESTS)) {
            var root = new TreeSet<>(files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".java"))
                .map(n -> n.substring(0, n.length() - ".java".length())).toList());
            assertEquals(ROOT_TESTS, root, "Place a new test in its subject's package, or add it here on purpose");
        }
    }

    /**
     * Each root collaborator holds admission rules, so each has a test that names it; the exceptions are listed with
     * their reason.
     */
    @Test
    void rootCollaboratorsHaveTests() throws IOException {
        var tests = new ArrayList<String>();
        try (var files = Files.walk(TESTS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList())
                if (!GUARDS.contains(file.getFileName().toString().replace(".java", "")))
                    tests.add(COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" "));
        }
        var untested = new TreeSet<String>();
        try (var files = Files.list(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                var token = Pattern.compile("\\b" + name + "\\b");
                if (tests.stream().noneMatch(code -> token.matcher(code).find())) untested.add(name);
            }
        }
        assertEquals(new TreeSet<>(UNTESTED_ROOT.keySet()), untested, "Test the new root class, or list it with a reason");
    }

    /**
     * A test class is named after what it tests, not the review, audit or release that produced it.
     */
    @Test
    void testClassesNameASubject() throws IOException {
        try (var files = Files.walk(TESTS)) {
            var named = files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith("Test.java"))
                .filter(n -> Arrays.stream(WORDS.split(n.substring(0, n.length() - ".java".length())))
                    .anyMatch(word -> PROCESS_WORD.matcher(word).matches())).sorted().toList();
            assertEquals(List.of(), named);
        }
    }

    private static boolean allowed(String from, String to) {
        Integer source = CORE.get(from), target = CORE.get(to);
        if (source != null) return target != null && target < source;
        if (from.equals("render")) return !SCREENS.contains(to);
        if (from.equals("hud") || from.equals("pip")) return !to.equals("gui");
        return true;
    }
}
