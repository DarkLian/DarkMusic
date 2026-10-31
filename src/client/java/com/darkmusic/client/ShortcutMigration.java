package com.darkmusic.client;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Decisions over stored binding names; registration and key mutation stay in Shortcuts.
 */
final class ShortcutMigration {
    static boolean speed(Set<String> nativeNames, Map<String, String> saved, String back, String forward, String faster, String slower) {
        return !nativeNames.contains(back) && !nativeNames.contains(forward)
            && !saved.containsKey(back) && !saved.containsKey(forward)
            && faster.equals("key.keyboard.right") && slower.equals("key.keyboard.left");
    }

    static void clearSaved(Set<String> nativeNames, Map<String, String> saved, Collection<String> bindings) {
        if (nativeNames.containsAll(bindings)) saved.clear();
    }

    private ShortcutMigration() {
    }
}
