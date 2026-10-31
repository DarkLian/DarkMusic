package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke code reads and writes private members by name, so a renamed or moved field fails only when that smoke window
 * runs. Each reflection names the class that declares the member, and that class must declare it: a field moved to
 * another class fails here even when some other class happens to declare the same name. Tests need no such check:
 * they fail in the normal build when a member they reflect disappears. Both kinds of reflection only shrink.
 */
class SmokeReflectionTest {
    private static final Path SMOKE = Path.of("src/smoke/java");
    private static final Path TESTS = Path.of("src/test/java");
    // SmokeUi.field(owner, Type.class, "name"), SmokeUi.set(owner, "binary.Name", "name", value); unqualified inside SmokeUi.
    private static final Pattern CALL = Pattern.compile("(?<![\\w.])(?:SmokeUi\\.)?(field|set)\\(");
    private static final Pattern DECLARATION = Pattern.compile("static\\s+\\w+\\s+$");
    private static final Pattern CLASS_LITERAL = Pattern.compile("([A-Z]\\w*(?:\\.[A-Z]\\w*)*)\\.class");
    private static final Pattern STRING = Pattern.compile("\"([\\w.$]+)\"");
    // Type.class.getDeclaredField("name") or Class.forName("binary.Name").getDeclaredMethod("name", ...).
    private static final Pattern DECLARED = Pattern.compile("\\bgetDeclared(?:Field|Method|Constructor)s?\\(");
    private static final Pattern NAMED_DECLARED = Pattern.compile("(?:([A-Z]\\w*(?:\\.[A-Z]\\w*)*)\\.class|Class\\.forName\\(\"([\\w.$]+)\"\\))\\.getDeclared(Field|Method)\\(\"(\\w+)\"");
    private static final Pattern COMMENTS_AND_STRINGS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"", Pattern.DOTALL);
    private static final Pattern IMPORT = Pattern.compile("^import ([\\w.]+?)(\\.\\*)?;", Pattern.MULTILINE);
    // Reflection on private members, counted as the patterns above find it. Lower these as code stops reflecting;
    // never raise them.
    private static final int MAX_SMOKE_REFLECTION = 105;
    private static final int MAX_TEST_REFLECTION = 72;

    record Reflection(String file, String type, String name) {
    }

    @Test
    void smokeReflectionNamesItsDeclaringClass() throws IOException {
        var problems = new ArrayList<String>();
        var found = smokeReflections(problems);
        assertTrue(found.size() > 50, "Found only " + found.size() + " reflections; the check no longer reads the smoke code");
        for (var r : found) {
            Class<?> type = resolve(r.file(), r.type());
            if (type == null) problems.add(r.file() + ": no class " + r.type());
            else if (!declaresField(type, r.name()))
                problems.add(r.file() + ": " + type.getName() + " declares no field " + r.name());
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void reflectionOnlyShrinks() throws IOException {
        int smoke = smokeReflections(new ArrayList<>()).size() + count(SMOKE, DECLARED);
        assertTrue(smoke <= MAX_SMOKE_REFLECTION, "Smoke reflects " + smoke + " times; drive the screen or add a query instead");
        int tests = count(TESTS, DECLARED);
        assertTrue(tests <= MAX_TEST_REFLECTION, "Tests reflect " + tests + " times; call a package-private API instead");
        assertEquals(List.of(MAX_SMOKE_REFLECTION, MAX_TEST_REFLECTION), List.of(smoke, tests), "Lower the limits to the new counts");
    }

    private static List<Reflection> smokeReflections(List<String> problems) throws IOException {
        var found = new ArrayList<Reflection>();
        for (Path file : javaFiles(SMOKE)) {
            String code = Files.readString(file), name = file.getFileName().toString();
            var call = CALL.matcher(code);
            while (call.find()) {
                int lineStart = code.lastIndexOf('\n', call.start()) + 1;
                if (DECLARATION.matcher(code.substring(lineStart, call.start())).find()) continue;
                var args = arguments(code, call.end());
                // SmokeUi's own overloads pass their parameters on.
                if (args.size() >= 3 && args.get(2).equals("name")) continue;
                var type = args.size() >= 3 ? CLASS_LITERAL.matcher(args.get(1)) : null;
                var string = args.size() >= 3 ? STRING.matcher(args.get(1)) : null;
                var member = args.size() >= 3 ? STRING.matcher(args.get(2)) : null;
                if (member == null || !member.matches() || !(type.matches() || string.matches())) {
                    problems.add(name + ": reflection without a literal class and name: " + call.group() + String.join(",", args) + ")");
                    continue;
                }
                found.add(new Reflection(name, type.matches() ? type.group(1) : string.group(1), member.group(1)));
            }
            var declared = NAMED_DECLARED.matcher(code);
            while (declared.find()) {
                String type = declared.group(1) != null ? declared.group(1) : declared.group(2);
                Class<?> owner = resolve(name, type);
                if (owner == null) problems.add(name + ": no class " + type);
                else if (declared.group(3).equals("Field") ? !declaresField(owner, declared.group(4)) : Arrays.stream(owner.getDeclaredMethods()).noneMatch(m -> m.getName().equals(declared.group(4))))
                    problems.add(name + ": " + owner.getName() + " declares no " + declared.group(4));
            }
        }
        return found;
    }

    /**
     * The arguments of the call whose parentheses open just before at, trimmed; nested calls and strings stay whole.
     */
    static List<String> arguments(String code, int at) {
        var args = new ArrayList<String>();
        int depth = 0, start = at;
        for (int i = at; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                for (i++; code.charAt(i) != '"'; i++)
                    if (code.charAt(i) == '\\') i++;
                continue;
            }
            if (c == '(' || c == '{' || c == '[') depth++;
            else if ((c == ')' || c == '}' || c == ']') && depth-- == 0) {
                args.add(code.substring(start, i).strip());
                return args;
            } else if (c == ',' && depth == 0) {
                args.add(code.substring(start, i).strip());
                start = i + 1;
            }
        }
        return args;
    }

    /**
     * A class literal through the file's imports, or a binary name as written.
     */
    private static Class<?> resolve(String file, String type) throws IOException {
        if (type.contains(".") && Character.isLowerCase(type.charAt(0))) return load(type);
        String code = Files.readString(javaFiles(SMOKE).stream().filter(f -> f.getFileName().toString().equals(file)).findFirst().orElseThrow());
        String outer = type.split("\\.")[0], binary = type.replace('.', '$');
        Matcher imports = IMPORT.matcher(code);
        var candidates = new ArrayList<String>();
        while (imports.find()) {
            if (imports.group(2) != null) candidates.add(imports.group(1) + "." + binary);
            else if (imports.group(1).endsWith("." + outer))
                candidates.add(imports.group(1) + binary.substring(outer.length()));
        }
        candidates.add("java.lang." + binary);
        for (String candidate : candidates) {
            Class<?> found = load(candidate);
            if (found != null) return found;
        }
        return null;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, SmokeReflectionTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static boolean declaresField(Class<?> type, String name) {
        try {
            type.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static int count(Path root, Pattern pattern) throws IOException {
        int n = 0;
        // This check's own lookups are not reflection on the code under test.
        for (Path file : javaFiles(root).stream().filter(f -> !f.endsWith("SmokeReflectionTest.java")).toList()) {
            var matcher = pattern.matcher(COMMENTS_AND_STRINGS.matcher(Files.readString(file)).replaceAll(" "));
            while (matcher.find()) n++;
        }
        return n;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
