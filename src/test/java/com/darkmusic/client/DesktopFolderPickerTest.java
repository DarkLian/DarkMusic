package com.darkmusic.client;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DesktopFolderPickerTest {
    @TempDir
    Path temporary;

    private Path dialog(boolean approve) throws Exception {
        Path directory = TestMusic.featured().getParent();
        Path result = Files.createTempFile(temporary, "dialog", ".txt");
        Path log = temporary.resolve("dialog.log");
        String binary = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        String classes = Path.of(DialogAutomation.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + java.io.File.pathSeparator + Path.of(System.getProperty("darkmusic.jar"));
        Process child = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", binary).toString(), "-Djava.awt.headless=false", "-cp", classes, DialogAutomation.class.getName(), directory.toString(), "DarkMusic folder self-test", result.toString(), Long.toString(ProcessHandle.current().pid()), approve ? "approve" : "cancel"))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Desktop dialog timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
            String value = Files.readString(result);
            return value.isBlank() ? null : Path.of(value);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void actualDesktopDialogApprovesDirectoriesAndCancelsWithoutChangingSelection() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("darkmusic.test.desktop"), "Enable -PdesktopIntegration on a desktop");
        assertEquals(TestMusic.featured().getParent().toRealPath(), dialog(true).toRealPath());
        assertNull(dialog(false));
    }
}
