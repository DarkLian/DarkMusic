package com.darkmusic.client.library;

import com.darkmusic.client.text.Text;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Claims filesystem objects before deciding whether they belong to an edit. Never replaces by pathname.
 */
final class AudioReplacement {
    @FunctionalInterface
    interface Move {
        void run(Path source, Path target, CopyOption... options) throws IOException;
    }

    record Identity(long size, java.nio.file.attribute.FileTime modified, Object key, String hash) {
        static Identity read(Path path) throws IOException {
            var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) throw conflict();
            String hash = SourceLinks.contentHash(path);
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw conflict();
            return new Identity(before.size(), before.lastModifiedTime(), before.fileKey(), hash);
        }

        boolean matches(Path path) throws IOException {
            return equals(read(path));
        }
    }

    private static IOException conflict() {
        return new IOException(Text.get("library.darkmusic.file_changed"));
    }

    static void claim(Path source, Path retained, Identity expected) throws IOException {
        Files.move(source, retained); // No replacement, including during restoration.
        try {
            Identity moved = Identity.read(retained);
            if (!expected.equals(moved)) throw conflict();
        } catch (IOException changed) {
            try {
                Files.move(retained, source);
            } catch (IOException restore) {
                changed.addSuppressed(restore);
            }
            throw new RecoveryRequired(retained, changed);
        }
    }

    static final class Edit {
        private final Path source, target, retained, discarded;
        private final Identity original;
        private Identity installed;
        private boolean claimed, published;

        Edit(Path source, Path target, Path retained, Identity original) {
            this.source = source;
            this.target = target;
            this.retained = retained;
            this.original = original;
            discarded = retained.resolveSibling("rollback-edited" + retained.getFileName());
        }

        void claim() throws IOException {
            AudioReplacement.claim(source, retained, original);
            claimed = true;
        }

        void install(Path staged) throws IOException {
            install(staged, Files::move);
        }

        void install(Path staged, Move move) throws IOException {
            installed = Identity.read(staged);
            move.run(staged, target); // The original is held, so even in-place edits never overwrite.
            published = true;
        }

        String installedHash() {
            return installed.hash;
        }

        void rollback() throws IOException {
            if (!claimed) return;
            try {
                if (published) AudioReplacement.claim(target, discarded, installed);
                Files.move(retained, source);
                claimed = false;
            } catch (IOException failure) {
                // Unknown/new files at either path survive. Retain originals and edited content for recovery.
                throw new RecoveryRequired(retained, failure);
            }
        }
    }

    static void replace(Path staged, Path target, Path backup, Move move) throws IOException {
        var edit = new Edit(target, target, backup, Identity.read(target));
        edit.claim();
        try {
            edit.install(staged, move);
        } catch (IOException failure) {
            try {
                edit.rollback();
            } catch (IOException rollback) {
                failure.addSuppressed(rollback);
                throw new RecoveryRequired(backup, failure);
            }
            throw failure;
        }
    }

    static final class RecoveryRequired extends IOException {
        RecoveryRequired(Path backup, IOException cause) {
            super(Text.get("library.darkmusic.edit_recovery", backup.getParent()), cause);
        }
    }
}
