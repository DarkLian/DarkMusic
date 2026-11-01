package com.darkmusic.client.gui;

import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DesktopFolderPickerTest {
    @TempDir
    Path temporary;

    private static Process exited(int exit) {
        return new Process() {
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }

            public InputStream getInputStream() {
                return InputStream.nullInputStream();
            }

            public InputStream getErrorStream() {
                return InputStream.nullInputStream();
            }

            public int waitFor() {
                return exit;
            }

            public boolean waitFor(long timeout, TimeUnit unit) {
                return true;
            }

            public int exitValue() {
                return exit;
            }

            public boolean isAlive() {
                return false;
            }

            public void destroy() {
            }
        };
    }

    @Test
    void cancellationClosesInputThenDestroysAndForcesAnUnresponsiveChild() throws Exception {
        var order = new ArrayList<String>();
        assertNull(DesktopFolderPicker.choose(temporary, "cancel", "folder", (i, t, m, r, l) -> {
            DesktopFolderPicker.cancel();
            return new Process() {
                int waits;

                public OutputStream getOutputStream() {
                    return new OutputStream() {
                        public void write(int b) {
                        }

                        public void close() {
                            order.add("stdin");
                        }
                    };
                }

                public InputStream getInputStream() {
                    return InputStream.nullInputStream();
                }

                public InputStream getErrorStream() {
                    return InputStream.nullInputStream();
                }

                public int waitFor() {
                    return 1;
                }

                public boolean waitFor(long timeout, TimeUnit unit) {
                    return ++waits >= 3;
                }

                public int exitValue() {
                    fail("Cancellation wins over exit failure");
                    return 1;
                }

                public boolean isAlive() {
                    return false;
                }

                public void destroy() {
                    order.add("destroy");
                }

                public Process destroyForcibly() {
                    order.add("force");
                    return this;
                }
            };
        }).get(5, TimeUnit.SECONDS));
        assertEquals(List.of("stdin", "destroy", "force"), order);
        assertFalse(DesktopFolderPicker.isOpen());
    }

    @Test
    void admittedJobOwnsCancellationAndRefusesASecondPicker() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resultFile = new AtomicReference<Path>();
        var logFile = new AtomicReference<Path>();
        var first = DesktopFolderPicker.choose(temporary, "test", "folder", (initial, title, mode, result, log) -> {
            assertTrue(DesktopFolderPicker.isOpen());
            resultFile.set(result);
            logFile.set(log);
            Files.writeString(result, temporary.toString());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return exited(0);
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            DesktopFolderPicker.cancel();
            assertNull(DesktopFolderPicker.choose(temporary, "second").get(5, TimeUnit.SECONDS));
            release.countDown();
            assertNull(first.get(5, TimeUnit.SECONDS), "Refused admission must not clear the first job's cancellation");
            assertFalse(DesktopFolderPicker.isOpen());
            assertFalse(Files.exists(resultFile.get()));
            assertFalse(Files.exists(logFile.get()));
            assertEquals(temporary, DesktopFolderPicker.choose(temporary, "next", "folder", (i, t, m, r, l) -> {
                Files.writeString(r, temporary.toString());
                return exited(0);
            }).get(5, TimeUnit.SECONDS), "The next admitted job clears stale cancellation");
        } finally {
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedStartAndFailedExitCleanScratchAndReleaseAdmission() throws Exception {
        for (boolean startFails : new boolean[]{true, false}) {
            var resultFile = new AtomicReference<Path>();
            var logFile = new AtomicReference<Path>();
            var job = DesktopFolderPicker.choose(temporary, "failure", "folder", (i, t, m, result, log) -> {
                resultFile.set(result);
                logFile.set(log);
                if (startFails) throw new IOException("start failure");
                Files.writeString(log, "diagnostic".repeat(2000));
                return exited(1);
            });
            assertThrows(CompletionException.class, job::join);
            assertFalse(DesktopFolderPicker.isOpen());
            assertFalse(Files.exists(resultFile.get()));
            assertFalse(Files.exists(logFile.get()));
        }
    }

    private Path dialog(boolean approve) throws Exception {
        Path directory = TestMusic.featured().getParent();
        Path result = Files.createTempFile(temporary, "dialog", ".txt");
        Path log = temporary.resolve("dialog.log");
        String binary = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        String classes = Path.of(DialogAutomation.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + File.pathSeparator + Path.of(System.getProperty("darkmusic.jar"));
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

    @Test
    void refusedScratchCleanupKeepsTheSelectionAndReleasesAdmission() throws Exception {
        var scratch = new ArrayList<Path>();
        var attempted = new ArrayList<Path>();
        try {
            var selected = DesktopFolderPicker.choose(temporary, "cleanup", "folder", (initial, title, mode, result, log) -> {
                scratch.add(result);
                scratch.add(log);
                Files.writeString(result, temporary.toString());
                return exited(0);
            }, file -> {
                attempted.add(file);
                throw new IOException("folder scratch cleanup denied");
            }).get(5, TimeUnit.SECONDS);
            assertEquals(temporary, selected);
            assertFalse(DesktopFolderPicker.isOpen());
            assertEquals(scratch, attempted);
            for (Path file : scratch) assertTrue(Files.exists(file));
            assertNull(DesktopFolderPicker.choose(temporary, "next", "folder", (initial, title, mode, result, log) -> exited(0))
                .get(5, TimeUnit.SECONDS));
        } finally {
            for (Path file : scratch) Files.deleteIfExists(file);
        }
    }

}
