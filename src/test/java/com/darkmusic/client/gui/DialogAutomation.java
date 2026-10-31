package com.darkmusic.client.gui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Arrays;

/**
 * Child-process driver for the actual JDK desktop dialog; not shipped in the mod.
 */
public final class DialogAutomation {
    private static JFileChooser find(Component component) {
        if (component instanceof JFileChooser picker) return picker;
        if (component instanceof Container c) for (Component child : c.getComponents()) {
            var found = find(child);
            if (found != null) return found;
        }
        return null;
    }

    static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            var timer = new Timer(300, event -> {
                for (Window window : Window.getWindows()) {
                    var picker = find(window);
                    if (picker == null || !window.isShowing()) continue;
                    if (picker.getFileSelectionMode() != JFileChooser.DIRECTORIES_ONLY) System.exit(2);
                    ((Timer) event.getSource()).stop();
                    if (args[4].equals("approve")) {
                        picker.setSelectedFile(new File(args[0]));
                        picker.approveSelection();
                    } else picker.cancelSelection();
                }
            });
            timer.start();
        });
        DesktopFolderDialog.main(Arrays.copyOf(args, 4));
    }
}
