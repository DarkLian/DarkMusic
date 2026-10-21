package com.darkmusic.client.audio;

/**
 * Temporary analyzer-only audition state, never serialized into a user's profile.
 */
public record AudioComparison(Mode mode, boolean bypassed, boolean levelMatched) {
    public enum Mode {AUDIO_OPTIONS, ENVIRONMENT, FULL}

    public static final AudioComparison OFF = new AudioComparison(Mode.FULL, false, false);
}
