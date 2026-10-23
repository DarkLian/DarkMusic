package com.darkmusic.client.library;

import java.awt.Desktop;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * JDK-only desktop helper: Minecraft deliberately initializes its own AWT as headless.
 */
public final class DesktopTrashApp {
    private DesktopTrashApp() {
    }

    public static void main(String[] args) {
        long parent = Long.parseLong(args[0]);
        Thread.ofPlatform().daemon().start(() -> {
            while (ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            System.exit(1);
        });
        try (var input = new DataInputStream(System.in); var output = new DataOutputStream(System.out)) {
            boolean supported = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
            output.writeBoolean(supported);
            output.flush();
            while (input.readBoolean()) {
                Path file = Path.of(input.readUTF());
                long size = input.readLong(), modified = input.readLong();
                String identity = input.readUTF();
                boolean moved = false;
                try {
                    var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (supported && attributes.isRegularFile() && !Files.isSymbolicLink(file)
                        && attributes.size() == size && attributes.lastModifiedTime().toMillis() == modified
                        && String.valueOf(attributes.fileKey()).equals(identity))
                        moved = Desktop.getDesktop().moveToTrash(file.toFile());
                } catch (Exception failure) {
                    failure.printStackTrace(System.err);
                }
                output.writeBoolean(moved);
                output.flush();
            }
        } catch (EOFException ignored) {
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
        }
        System.exit(0);
    }
}
