package com.darkmusic.client.library;

import java.io.IOException;
import java.nio.file.*;

/**
 * Preserve the original even on filesystems without atomic replacement.
 */
final class AudioReplacement {
    @FunctionalInterface
    interface Move {
        void run(Path source, Path target, CopyOption... options) throws IOException;
    }

    static void replace(Path staged, Path target, Path backup) throws IOException {
        replace(staged, target, backup, Files::move);
    }

    static void replace(Path staged, Path target, Path backup, Move move) throws IOException {
        try {
            move.run(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.copy(target, backup);
            try {
                move.run(staged, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                try {
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                    throw new RecoveryRequired(backup, failure);
                }
                throw failure;
            }
        }
    }

    static final class RecoveryRequired extends IOException {
        RecoveryRequired(Path backup, IOException cause) {
            super("Original audio retained in " + backup, cause);
        }
    }
}
