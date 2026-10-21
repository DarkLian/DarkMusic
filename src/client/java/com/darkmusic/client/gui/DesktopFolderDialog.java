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
                    String mode = args.length > 4 ? args[4] : "folder";
                    picker.setFileSelectionMode(mode.equals("folder") ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                    picker.setAcceptAllFileFilterUsed(false);
                    if (!mode.equals("folder"))
                        picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("DarkMusic ZIP", "zip"));
                    if ((mode.equals("save") ? picker.showSaveDialog(null) : picker.showOpenDialog(null)) == JFileChooser.APPROVE_OPTION) {
                        Path selected = picker.getSelectedFile().toPath();
                        if (mode.equals("save") && !selected.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
                            selected = selected.resolveSibling(selected.getFileName() + ".zip");
                        if (!mode.equals("save") || !Files.exists(selected) || JOptionPane.showConfirmDialog(picker, (args.length > 5 ? args[5] : args[1]) + "\n" + selected.getFileName(), args[1], JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                            Files.writeString(Path.of(args[2]), selected.toString());
                    }
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
