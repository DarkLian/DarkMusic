package com.darkmusic.client.lyrics;

import java.util.EnumSet;

/**
 * Writing-system evidence only. Shared Han, Latin, Arabic, and Cyrillic scripts do not establish a language.
 */
public enum LyricsScript {
    UNKNOWN, LATIN, JAPANESE, KOREAN, HAN, ARABIC, CYRILLIC, THAI, MIXED;

    public static LyricsScript detect(String text) {
        var scripts = EnumSet.noneOf(LyricsScript.class);
        text.codePoints().filter(Character::isLetter).forEach(cp -> {
            switch (Character.UnicodeScript.of(cp)) {
                case HIRAGANA, KATAKANA -> scripts.add(JAPANESE);
                case HANGUL -> scripts.add(KOREAN);
                case HAN -> scripts.add(HAN);
                case LATIN -> scripts.add(LATIN);
                case ARABIC -> scripts.add(ARABIC);
                case CYRILLIC -> scripts.add(CYRILLIC);
                case THAI -> scripts.add(THAI);
                default -> scripts.add(UNKNOWN);
            }
        });
        if (scripts.contains(JAPANESE) || scripts.contains(KOREAN)) scripts.remove(HAN);
        if (scripts.size() > 1) return MIXED;
        return scripts.isEmpty() ? UNKNOWN : scripts.iterator().next();
    }
}
