package com.darkmusic.client.media;

import javax.sound.sampled.AudioInputStream;

/**
 * A decoded stream and the position, in seconds, where it actually starts.
 */
public record OpenedAudio(AudioInputStream stream, double offset) {
}
