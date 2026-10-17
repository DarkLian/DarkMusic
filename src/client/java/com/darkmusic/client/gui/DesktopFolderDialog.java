package com.darkmusic.client.gui;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JDK-only helper entry point. Minecraft deliberately makes its own AWT environment headless.
 */
public final class DesktopFolderDialog {
    private DesktopFolderDialog() {
    }

    public static void main(String[] args) {
        try {
            long parent = Long.parseLong(args[3]);
            SwingUtilities.invokeAndWait(() -> {
                var guard = new Timer(500, e -> {
                    if (!ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) System.exit(1);
                });
                guard.start();
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    var picker = new JFileChooser(args[0]);
                    picker.setDialogTitle(args[1]);
                    picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    picker.setAcceptAllFileFilterUsed(false);
                    if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                        Files.writeString(Path.of(args[2]), picker.getSelectedFile().toPath().toString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    guard.stop();
                }
            });
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
