package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Moving a document must not leave a dead link behind: every relative Markdown link names a file that exists, and an
 * anchor names a heading in it.
 */
class DocumentationLinksTest {
    private static final Set<String> SKIPPED = Set.of(".git", "build", "run", ".local-tools", ".gradle", ".idea");
    private static final Pattern LINK = Pattern.compile("\\]\\(<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*\\s*$");

    @Test
    void everyRelativeLinkReachesAFileAndHeading() throws IOException {
        var broken = new ArrayList<String>();
        var headings = new HashMap<Path, Set<String>>();
        for (Path document : documents()) {
            for (String target : links(document)) {
                if (target.contains(":") || target.startsWith("//")) continue;
                int hash = target.indexOf('#');
                String path = hash < 0 ? target : target.substring(0, hash);
                String anchor = hash < 0 ? "" : target.substring(hash + 1);
                Path file = path.isEmpty() ? document : document.resolveSibling(URI.create(path).getPath()).normalize();
                if (!Files.exists(file)) {
                    broken.add(document + " -> " + target + " (no such file)");
                } else if (!anchor.isEmpty() && file.toString().endsWith(".md")
                    && !headings.computeIfAbsent(file, DocumentationLinksTest::anchors).contains(anchor.toLowerCase(Locale.ROOT))) {
                    broken.add(document + " -> " + target + " (no such heading)");
                }
            }
        }
        assertEquals(List.of(), broken);
    }

    private static List<Path> documents() throws IOException {
        var result = new ArrayList<Path>();
        Files.walkFileTree(Path.of("."), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                var name = directory.getFileName().toString();
                return directory.getNameCount() > 1 && SKIPPED.contains(name)
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.toString().endsWith(".md")) result.add(file.normalize());
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /**
     * Links outside code: fenced blocks and inline code show syntax, they do not link.
     */
    private static List<String> links(Path document) throws IOException {
        var result = new ArrayList<String>();
        boolean fenced = false;
        for (String line : Files.readAllLines(document)) {
            if (line.stripLeading().startsWith("```")) fenced = !fenced;
            if (fenced) continue;
            var matcher = LINK.matcher(INLINE_CODE.matcher(line).replaceAll(" "));
            while (matcher.find()) result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * GitHub's heading slugs: lower case, punctuation dropped, spaces to hyphens, repeats numbered.
     */
    private static Set<String> anchors(Path document) {
        var result = new HashSet<String>();
        var seen = new HashMap<String, Integer>();
        try {
            boolean fenced = false;
            for (String line : Files.readAllLines(document)) {
                if (line.stripLeading().startsWith("```")) fenced = !fenced;
                var heading = HEADING.matcher(line);
                if (fenced || !heading.matches()) continue;
                String slug = heading.group(1).replaceAll("[`*_]|\\[([^\\]]*)]\\([^)]*\\)", "$1")
                    .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\- _]", "").replace(' ', '-');
                int count = seen.merge(slug, 1, Integer::sum) - 1;
                result.add(count == 0 ? slug : slug + "-" + count);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
